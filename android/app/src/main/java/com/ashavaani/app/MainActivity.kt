package com.ashavaani.app

import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.ashavaani.app.ui.navigation.AshaVaaniAppShell
import com.ashavaani.app.ui.theme.AshaVaaniTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var appContainer: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Bind hardware volume keys directly to ASHA Vaani's loudspeaker TTS stream
        volumeControlStream = AudioManager.STREAM_MUSIC
        appContainer = AppContainer(applicationContext)
        handleOAuthIntent(intent)
        setContent {
            AshaVaaniTheme {
                AshaVaaniAppShell(container = appContainer)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOAuthIntent(intent)
    }

    private fun handleOAuthIntent(intent: Intent?) {
        val uriString = intent?.data?.toString() ?: return
        if (!uriString.startsWith("ashavaani://auth/callback")) return
        lifecycleScope.launch {
            appContainer.supabaseAuth.parseOAuthCallbackUri(uriString).onSuccess { session ->
                appContainer.settingsStore.saveAuthSession(session)
            }
        }
    }

    override fun onDestroy() {
        if (isFinishing && ::appContainer.isInitialized) {
            appContainer.release()
        }
        super.onDestroy()
    }
}
