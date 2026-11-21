package com.ashavaani.app.data.remote

import com.ashavaani.app.model.AshaJson
import com.ashavaani.app.model.ConnectionState
import com.ashavaani.app.model.WsBargeIn
import com.ashavaani.app.model.WsClientMessage
import com.ashavaani.app.model.WsErrorEvent
import com.ashavaani.app.model.WsFinalizeTurn
import com.ashavaani.app.model.WsLanguageChange
import com.ashavaani.app.model.WsPatientSelect
import com.ashavaani.app.model.WsRegisterSelect
import com.ashavaani.app.model.WsResetDraft
import com.ashavaani.app.model.WsServerEvent
import com.ashavaani.app.model.WsSessionStart
import com.ashavaani.app.model.WsTextTurn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Bounded exponential-backoff policy for WebSocket auto-reconnect in rural low-signal zones:
 * attempt 1 -> 1s, attempt 2 -> 2s, attempt 3 -> 4s, attempt 4 -> 8s, attempt 5 -> 8s, >5 -> stop.
 */
data class ReconnectBackoffPolicy(
    val baseDelayMs: Long = 1_000L,
    val maxDelayMs: Long = 8_000L,
    val maxAttempts: Int = 5,
) {
    fun nextDelayMs(attempt: Int): Long? {
        if (attempt < 1 || attempt > maxAttempts) return null
        val shift = (attempt - 1).coerceAtMost(10)
        val computed = baseDelayMs * (1L shl shift)
        return computed.coerceAtMost(maxDelayMs)
    }
}

/**
 * Full-duplex OkHttp WebSocket client for `/ws/session`.
 *
 * Streams binary 16kHz 16-bit mono PCM audio frames upstream + JSON control events,
 * and routes downstream binary Timbre v3.0 PCM audio + typed [WsServerEvent]s with bounded
 * exponential-backoff reconnect (1s -> 2s -> 4s -> 8s, max 5 retries) preserving session state.
 */
class VoiceSessionClient(
    private val httpClient: OkHttpClient = defaultWebSocketClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val backoffPolicy: ReconnectBackoffPolicy = ReconnectBackoffPolicy(),
) {
    private val _connectionState = MutableStateFlow(ConnectionState.OFFLINE_CACHE)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _reconnectAttemptCount = MutableStateFlow(0)
    val reconnectAttemptCount: StateFlow<Int> = _reconnectAttemptCount.asStateFlow()

    val sessionId: String = "ANDROID-${UUID.randomUUID().toString().take(8).uppercase()}"

    private val _events = MutableSharedFlow<WsServerEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WsServerEvent> = _events.asSharedFlow()

    @Volatile
    var onBinaryAudio: ((ByteArray) -> Unit)? = null

    private val wsLock = Any()
    private var activeSocket: WebSocket? = null
    private var reconnectJob: Job? = null

    @Volatile
    private var manuallyClosed: Boolean = false

    @Volatile
    private var lastBaseUrl: String = ServerUrlResolver.DEFAULT_BASE_URL

    @Volatile
    private var lastLanguageCode: String = "hi-IN"

    @Volatile
    private var lastPatientId: String? = null

    @Volatile
    private var lastRegisterId: String? = null

    private var reconnectAttempt: Int = 0

    fun connect(
        baseUrl: String,
        languageCode: String = "hi-IN",
        patientId: String? = null,
        registerId: String? = null,
    ) {
        manuallyClosed = false
        lastBaseUrl = ServerUrlResolver.normalizeBaseUrl(baseUrl)
        lastLanguageCode = languageCode
        lastPatientId = patientId
        lastRegisterId = registerId
        reconnectAttempt = 0
        _reconnectAttemptCount.value = 0
        openSocketInternal()
    }

    fun disconnect() {
        manuallyClosed = true
        reconnectJob?.cancel()
        reconnectJob = null
        synchronized(wsLock) {
            activeSocket?.close(NORMAL_CLOSURE_STATUS, "client_disconnect")
            activeSocket = null
        }
        _connectionState.value = ConnectionState.OFFLINE_CACHE
    }

    fun release() {
        disconnect()
        onBinaryAudio = null
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Upstream Audio & JSON Control Senders
    // ------------------------------------------------------------------

    fun sendPcmFrame(pcmBytes: ByteArray): Boolean {
        if (pcmBytes.isEmpty()) return false
        val socket = synchronized(wsLock) { activeSocket } ?: return false
        if (socket.queueSize() > MAX_AUDIO_QUEUE_BYTES) {
            return false // Backpressure guard: drop stale audio rather than accumulating lag
        }
        return socket.send(pcmBytes.toByteString(0, pcmBytes.size))
    }

    fun sendLanguageChange(languageCode: String): Boolean {
        lastLanguageCode = languageCode
        return sendControl(WsLanguageChange(languageCode = languageCode))
    }

    fun sendPatientSelect(patientId: String): Boolean {
        lastPatientId = patientId
        lastRegisterId = null
        return sendControl(WsPatientSelect(patientId = patientId))
    }

    fun sendRegisterSelect(registerId: String): Boolean {
        lastRegisterId = registerId
        lastPatientId = null
        return sendControl(WsRegisterSelect(registerId = registerId))
    }

    fun sendBargeIn(): Boolean = sendControl(WsBargeIn)

    fun sendFinalizeTurn(): Boolean = sendControl(WsFinalizeTurn)

    fun sendResetDraft(mode: String? = null): Boolean {
        if (mode == "visit") {
            lastRegisterId = null
            lastPatientId = null
        }
        return sendControl(WsResetDraft(mode = mode))
    }

    fun sendTextTurn(text: String, languageCode: String? = null): Boolean =
        sendControl(WsTextTurn(text = text, languageCode = languageCode))

    fun sendControl(message: WsClientMessage): Boolean {
        val socket = synchronized(wsLock) { activeSocket } ?: return false
        val json = AshaJson.encodeToString<WsClientMessage>(message)
        return socket.send(json)
    }

    // ------------------------------------------------------------------
    // Internal Socket Lifecycle & Auto-Reconnect
    // ------------------------------------------------------------------

    private fun openSocketInternal() {
        reconnectJob?.cancel()
        val wsUrl = ServerUrlResolver.toWebSocketUrl(lastBaseUrl)
        _connectionState.value = ConnectionState.RECONNECTING

        synchronized(wsLock) {
            activeSocket?.cancel()
            activeSocket = null
        }

        val request = Request.Builder()
            .url(wsUrl)
            .header("X-Session-ID", sessionId)
            .build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                _reconnectAttemptCount.value = 0
                _connectionState.value = ConnectionState.CONNECTED
                val startMsg = WsSessionStart(
                    languageCode = lastLanguageCode,
                    patientId = lastPatientId,
                    registerId = lastRegisterId,
                )
                webSocket.send(AshaJson.encodeToString<WsClientMessage>(startMsg))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val parsed = runCatching {
                    AshaJson.decodeFromString<WsServerEvent>(text)
                }.getOrElse { err ->
                    WsErrorEvent(
                        message = "Failed to decode server event: ${err.message}",
                        code = "CLIENT_DECODE_ERROR",
                    )
                }
                _events.tryEmit(parsed)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                onBinaryAudio?.invoke(bytes.toByteArray())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE_STATUS, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleSocketTermination(webSocket)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                handleSocketTermination(webSocket)
            }
        }

        val socket = httpClient.newWebSocket(request, listener)
        synchronized(wsLock) {
            activeSocket = socket
        }
    }

    private fun handleSocketTermination(closedSocket: WebSocket) {
        val isCurrent = synchronized(wsLock) {
            if (activeSocket === closedSocket) {
                activeSocket = null
                true
            } else {
                false
            }
        }
        if (!isCurrent) return

        if (manuallyClosed) {
            _connectionState.value = ConnectionState.OFFLINE_CACHE
            return
        }

        _connectionState.value = ConnectionState.OFFLINE_CACHE
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectAttempt += 1
        val delayMs = backoffPolicy.nextDelayMs(reconnectAttempt)
        if (delayMs == null) {
            _reconnectAttemptCount.value = backoffPolicy.maxAttempts
            _connectionState.value = ConnectionState.OFFLINE_CACHE
            return
        }
        _reconnectAttemptCount.value = reconnectAttempt
        _connectionState.value = ConnectionState.RECONNECTING
        reconnectJob = scope.launch {
            delay(delayMs)
            if (!manuallyClosed) {
                openSocketInternal()
            }
        }
    }

    companion object {
        private const val NORMAL_CLOSURE_STATUS = 1000
        private const val MAX_AUDIO_QUEUE_BYTES = 64L * 1024L

        fun defaultWebSocketClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Required for long-lived full-duplex WS
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
