package com.ashavaani.app.ui.walkmode

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.audio.AudioDspCapabilities
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.theme.AshaColors
import com.ashavaani.app.ui.theme.ClinicalValueTextStyle

/**
 * Settings & Live Speech Telemetry HUD Sheet.
 * Opened by tapping the compact top-bar TTFA/Status pill.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    uiState: WalkModeUiState,
    onSaveAndReconnect: (backendUrl: String, phcPhone: String) -> Unit,
    onSyncOutboxNow: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var backendUrlInput by remember(uiState.backendUrl) { mutableStateOf(uiState.backendUrl) }
    var phcPhoneInput by remember(uiState.phcPhoneNumber) { mutableStateOf(uiState.phcPhoneNumber) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AshaColors.PureWhite,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Settings & Speech Telemetry",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = "Hackathon Judge Diagnostics & Server Config",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = AshaColors.InkSecondary,
                    )
                }
            }

            HorizontalDivider(color = AshaColors.DividerSubtle)

            // Expanded Judge Telemetry HUD (P3-04)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "SPEECH-LAYER TELEMETRY (TARGET TTFA < 800ms)",
                    style = MaterialTheme.typography.labelSmall,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val ttfa = uiState.metrics.ttfaMs
                    val ttfaGood = ttfa != null && ttfa < 800
                    MetricTile(
                        label = "TTFA",
                        value = ttfa?.let { "${it}ms" } ?: "—",
                        highlight = ttfaGood,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "STT",
                        value = (uiState.metrics.sttMs ?: uiState.metrics.asrMs)?.let { "${it}ms" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "LLM",
                        value = uiState.metrics.llmMs?.let { "${it}ms" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "TTS TTFA",
                        value = (uiState.metrics.ttsTtfaMs ?: uiState.metrics.ttsMs)?.let { "${it}ms" } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val bargeMs = uiState.metrics.bargeInFlushMs ?: uiState.metrics.serverBargeInCutoffMs
                    MetricTile(
                        label = "BARGE-IN",
                        value = bargeMs?.let { String.format("%.1fms", it) } ?: "—",
                        highlight = bargeMs != null && bargeMs < 150f,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "NOISE RMS",
                        value = uiState.metrics.noiseFloorRms?.let {
                            String.format("%.4f", it)
                        } ?: "—",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        label = "FAST-PATH",
                        value = if (uiState.metrics.dangerFastPath) "ON" else "STD",
                        highlight = uiState.metrics.dangerFastPath,
                        modifier = Modifier.weight(1f),
                    )
                }

                // Active LLM model & Offline Sync Outbox status strip
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val llmName = uiState.metrics.activeLlmModel.ifBlank { "Auto (Groq / Fallback)" }
                    StatusPill(
                        text = "LLM: $llmName",
                        containerColor = AshaColors.ClinicalTealTint,
                        contentColor = AshaColors.ClinicalTeal,
                    )
                    if (uiState.pendingSyncCount > 0 || uiState.isSyncingOutbox) {
                        StatusPill(
                            text = if (uiState.isSyncingOutbox) {
                                "Syncing outbox..."
                            } else {
                                "Offline · ${uiState.pendingSyncCount} queued"
                            },
                            containerColor = AshaColors.VoiceDiffSurface,
                            contentColor = AshaColors.VoiceDiffAmberDark,
                        )
                    } else {
                        StatusPill(
                            text = "Outbox: Synced",
                            containerColor = AshaColors.VerifiedSurface,
                            contentColor = AshaColors.VerifiedEmerald,
                        )
                    }
                }

                if (uiState.pendingSyncCount > 0 && !uiState.isSyncingOutbox) {
                    Button(
                        onClick = onSyncOutboxNow,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AshaColors.VoiceDiffAmber,
                            contentColor = AshaColors.PureWhite,
                        ),
                    ) {
                        Text(
                            text = "Sync ${uiState.pendingSyncCount} Queued Record${if (uiState.pendingSyncCount == 1) "" else "s"} Now",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // Hardware Audio DSP Verification Chips
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "ANDROID HARDWARE AUDIO LAYER",
                    style = MaterialTheme.typography.labelSmall,
                )
                DspBadgesRow(caps = uiState.dspCapabilities)
            }

            // Editable Backend Server URL
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "FASTAPI SERVER URL (ADB REVERSE DEFAULT)",
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = { backendUrlInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("http://127.0.0.1:8000") },
                )
            }

            // Editable PHC / Emergency Number
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "PHC / EMERGENCY HELPLINE NUMBER",
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedTextField(
                    value = phcPhoneInput,
                    onValueChange = { phcPhoneInput = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    placeholder = { Text("108") },
                )
            }

            Button(
                onClick = {
                    onSaveAndReconnect(backendUrlInput, phcPhoneInput)
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AshaColors.ClinicalTeal,
                    contentColor = AshaColors.PureWhite,
                ),
            ) {
                Text(
                    text = "Save & Reconnect WebSocket",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (highlight) AshaColors.VerifiedSurface else AshaColors.SunlitCanvas,
        border = BorderStroke(
            1.dp,
            if (highlight) AshaColors.VerifiedBorder else AshaColors.DividerSubtle,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = label,
                color = if (highlight) AshaColors.VerifiedEmerald else AshaColors.InkMuted,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = ClinicalValueTextStyle.copy(fontSize = 14.sp),
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DspBadgesRow(caps: AudioDspCapabilities) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DspPill(name = "AEC", active = caps.aecActive, available = caps.aecAvailable)
        DspPill(name = "NoiseSuppressor", active = caps.nsActive, available = caps.nsAvailable)
        DspPill(name = "AGC", active = caps.agcActive, available = caps.agcAvailable)
        StatusPill(
            text = "16kHz PCM · 20ms",
            containerColor = AshaColors.SurfaceMuted,
            contentColor = AshaColors.InkSecondary,
        )
    }
}

@Composable
private fun DspPill(name: String, active: Boolean, available: Boolean) {
    val (bg, fg, prefix) = when {
        active -> Triple(AshaColors.ClinicalTealTint, AshaColors.ClinicalTeal, "✓ ")
        available -> Triple(AshaColors.SurfaceMuted, AshaColors.InkSecondary, "• ")
        else -> Triple(AshaColors.SurfaceMuted, AshaColors.InkMuted, "— ")
    }
    StatusPill(
        text = "$prefix$name${if (active) " Active" else if (available) " Ready" else ""}",
        containerColor = bg,
        contentColor = fg,
    )
}
