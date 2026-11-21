package com.ashavaani.app.ui.walkmode

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ashavaani.app.model.FieldDiff
import com.ashavaani.app.model.RegisterIntakeDraft
import com.ashavaani.app.model.SupportedLanguage
import com.ashavaani.app.model.VisitDraft
import com.ashavaani.app.model.toDisplayString
import com.ashavaani.app.ui.components.AshaHaptics
import com.ashavaani.app.ui.components.HapticCue
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.theme.AshaColors
import com.ashavaani.app.ui.theme.ClinicalValueTextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow

/**
 * Primary Hands-Free Walk-Mode Home Screen (Outdoor Daylight High-Contrast).
 * Implements zero-typing visit capture, FR-7.2 custom register voice intake, Amber FieldDiff
 * voice-correction highlights, animated barge-in waveform, collapsible mock simulator drawer,
 * and one-thumb 80dp Mic Orb dock.
 */
@Composable
fun WalkModeScreen(
    uiState: WalkModeUiState,
    hapticEvents: SharedFlow<HapticCue>,
    onMicClick: () -> Unit,
    onSelectLanguage: (SupportedLanguage) -> Unit,
    onResetDraft: () -> Unit,
    onClearFollowUpContext: () -> Unit,
    onExitRegisterIntake: () -> Unit,
    onToggleSimDrawer: () -> Unit,
    onSendSimulatedUtterance: (String) -> Unit,
    onDismissDanger: () -> Unit,
    onClearToast: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Trigger Android haptics on ViewModel cue emissions
    LaunchedEffect(hapticEvents) {
        hapticEvents.collect { cue -> AshaHaptics.trigger(context, cue) }
    }

    // Auto-dismiss transient Emerald save toast
    LaunchedEffect(uiState.toastMessage) {
        if (uiState.toastMessage != null) {
            delay(3_600L)
            onClearToast()
        }
    }

    // Runtime RECORD_AUDIO permission request on first mic tap
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) onMicClick()
    }

    val handleMicPress: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onMicClick()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas),
    ) {
        // Scrollable top/middle voice workspace
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 1-Tap Native Indic Language Pills
            LanguagePillRow(
                activeLanguage = uiState.activeLanguage,
                onSelect = onSelectLanguage,
            )

            // Transient Emerald Toast Banner
            AnimatedVisibility(visible = uiState.toastMessage != null) {
                uiState.toastMessage?.let { msg ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AshaColors.VerifiedEmerald,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = msg,
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = onClearToast,
                                modifier = Modifier.size(28.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = AshaColors.PureWhite,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Error Banner (if any)
            AnimatedVisibility(visible = uiState.errorBanner != null) {
                uiState.errorBanner?.let { err ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AshaColors.DangerBadgeSurface,
                        border = BorderStroke(1.dp, AshaColors.DangerCrimson),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = err,
                                color = AshaColors.DangerCrimsonDark,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearError, modifier = Modifier.size(28.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss error",
                                    tint = AshaColors.DangerCrimsonDark,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            // Contextual Mode Banner (FR-7.2 Register Intake OR FR-7.6 Patient Follow-Up Baseline)
            if (uiState.inputMode == WalkInputMode.REGISTER_INTAKE && uiState.registerIntakeDraft != null) {
                ModeBanner(
                    overline = "FR-7.2 VOICE REGISTER INTAKE",
                    title = uiState.registerIntakeDraft.registerName,
                    containerColor = AshaColors.ClinicalTealTint,
                    accentColor = AshaColors.ClinicalTeal,
                    actionLabel = "Back to Visits",
                    onAction = onExitRegisterIntake,
                )
            } else if (uiState.followUpContext != null) {
                val baselineSummary = uiState.followUpContext.baselineFields.entries
                    .take(2)
                    .joinToString(" · ") { "${it.key}: ${it.value.toDisplayString()}" }
                ModeBanner(
                    overline = "FR-7.6 FOLLOW-UP BASELINE LOADED",
                    title = buildString {
                        append(uiState.followUpContext.beneficiaryName)
                        if (baselineSummary.isNotBlank()) append(" · $baselineSummary")
                    },
                    containerColor = AshaColors.VerifiedSurface,
                    accentColor = AshaColors.VerifiedEmerald,
                    actionLabel = "Clear",
                    onAction = onClearFollowUpContext,
                )
            }

            // Primary Live Structured Card
            if (uiState.inputMode == WalkInputMode.REGISTER_INTAKE && uiState.registerIntakeDraft != null) {
                RegisterIntakeCard(
                    draft = uiState.registerIntakeDraft,
                    sessionStatus = uiState.sessionStatus,
                    activeDiff = uiState.activeFieldDiff,
                )
            } else {
                StructuredVisitCard(
                    draft = uiState.visitDraft,
                    sessionStatus = uiState.sessionStatus,
                    activeDiff = uiState.activeFieldDiff,
                    onQuickSave = { onSendSimulatedUtterance("Haan save") },
                )
            }

            // Live Transcript & Agent Read-Back Strip
            TranscriptAndReadbackCard(
                partialTranscript = uiState.partialTranscript,
                finalTranscript = uiState.finalTranscript,
                agentReadback = uiState.agentReadback,
                isAgentSpeaking = uiState.isAgentSpeaking,
                isIntakeMode = uiState.inputMode == WalkInputMode.REGISTER_INTAKE,
            )

            // Collapsible Mock Simulator Quick-Test Drawer (Mock mode only)
            AnimatedVisibility(visible = uiState.isMockMode && uiState.isSimDrawerOpen) {
                MockSimulatorDrawer(
                    isIntakeMode = uiState.inputMode == WalkInputMode.REGISTER_INTAKE,
                    onSendUtterance = onSendSimulatedUtterance,
                    onClose = onToggleSimDrawer,
                )
            }
        }

        // Pinned One-Thumb Mic Control Dock (Lower Natural Thumb Arc)
        OneThumbMicDock(
            isMicActive = uiState.isMicActive,
            isAgentSpeaking = uiState.isAgentSpeaking,
            rmsLevel = uiState.rmsLevel,
            showSimButton = uiState.isMockMode,
            isSimDrawerOpen = uiState.isSimDrawerOpen,
            onMicClick = handleMicPress,
            onResetClick = onResetDraft,
            onSimClick = onToggleSimDrawer,
        )
    }

    // Urgent Crimson PHC Referral Modal Sheet (PS1 Pillar #3)
    uiState.activeDangerEscalation?.let { escalation ->
        PhcReferralSheet(
            escalation = escalation,
            beneficiaryName = uiState.visitDraft.beneficiaryName
                ?: uiState.registerIntakeDraft?.patientName
                ?: uiState.followUpContext?.beneficiaryName,
            phcPhoneNumber = uiState.phcPhoneNumber,
            onDismiss = onDismissDanger,
        )
    }
}

@Composable
private fun LanguagePillRow(
    activeLanguage: SupportedLanguage,
    onSelect: (SupportedLanguage) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SupportedLanguage.entries.forEach { lang ->
            val selected = lang == activeLanguage
            val shape = RoundedCornerShape(999.dp)
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(if (selected) AshaColors.ClinicalTeal else AshaColors.PureWhite)
                    .border(
                        width = 1.5.dp,
                        color = if (selected) AshaColors.ClinicalTeal else AshaColors.CardBorder,
                        shape = shape,
                    )
                    .clickable { onSelect(lang) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = lang.nativeLabel,
                    color = if (selected) AshaColors.PureWhite else AshaColors.InkSecondary,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ModeBanner(
    overline: String,
    title: String,
    containerColor: Color,
    accentColor: Color,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        border = BorderStroke(1.5.dp, accentColor),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = overline,
                    color = accentColor,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                )
                Text(
                    text = title,
                    color = AshaColors.InkPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = AshaColors.PureWhite,
                border = BorderStroke(1.dp, accentColor),
                modifier = Modifier.clickable(onClick = onAction),
            ) {
                Text(
                    text = actionLabel,
                    color = accentColor,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StructuredVisitCard(
    draft: VisitDraft,
    sessionStatus: String,
    activeDiff: FieldDiff?,
    onQuickSave: () -> Unit,
) {
    val (badgeBg, badgeFg, badgeBorder, badgeText) = when (sessionStatus) {
        "AWAITING_CONFIRMATION", "SPEAKING_READBACK" ->
            Quad(AshaColors.VoiceDiffSurface, AshaColors.VoiceDiffAmberDark, AshaColors.VoiceDiffBorder, "AWAITING READ-BACK")
        "ESCALATED" ->
            Quad(AshaColors.DangerBadgeSurface, AshaColors.DangerCrimson, AshaColors.DangerCrimson, "PHC ESCALATED")
        "PROCESSING" ->
            Quad(AshaColors.ClinicalTealTint, AshaColors.ClinicalTeal, AshaColors.ClinicalTealBorder, "PROCESSING...")
        else ->
            Quad(AshaColors.SurfaceMuted, AshaColors.InkMuted, AshaColors.CardBorder, "READY FOR VISIT")
    }

    SunlitCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "STRUCTURED VISIT RECORD",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = draft.beneficiaryName ?: "Speak beneficiary name...",
                    color = if (draft.beneficiaryName != null) AshaColors.InkPrimary else AshaColors.InkPlaceholder,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            StatusPill(
                text = badgeText,
                containerColor = badgeBg,
                contentColor = badgeFg,
                borderColor = badgeBorder,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = AshaColors.DividerSubtle)
        Spacer(modifier = Modifier.height(10.dp))

        // Row 1: Visit Type & Weight
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldTile(
                label = "VISIT TYPE",
                value = draft.visitType ?: "—",
                diff = activeDiff?.takeIf { it.field == "visit_type" },
                modifier = Modifier.weight(1f),
            )
            FieldTile(
                label = "WEIGHT",
                value = draft.weightKg?.let { "$it kg" } ?: "—",
                diff = activeDiff?.takeIf { it.field == "weight_kg" },
                unitSuffix = " kg",
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 2: IFA Tablets & Next Visit Date
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FieldTile(
                label = "IFA TABLETS",
                value = draft.ifaTabletsGiven?.let { "$it goli" } ?: "—",
                diff = activeDiff?.takeIf { it.field == "ifa_tablets_given" },
                unitSuffix = " goli",
                modifier = Modifier.weight(1f),
            )
            FieldTile(
                label = "NEXT VISIT",
                value = draft.nextVisitDate ?: "—",
                diff = activeDiff?.takeIf { it.field == "next_visit_date" },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Row 3: Vaccinations Due
        val vacDiff = activeDiff?.takeIf { it.field == "vaccinations_due" }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (vacDiff != null) AshaColors.VoiceDiffSurface else AshaColors.SunlitCanvas,
            border = BorderStroke(
                if (vacDiff != null) 2.dp else 1.dp,
                if (vacDiff != null) AshaColors.VoiceDiffAmber else AshaColors.DividerSubtle,
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "VACCINATIONS DUE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (vacDiff != null) AshaColors.VoiceDiffAmberDark else AshaColors.InkMuted,
                    ),
                )
                if (draft.vaccinationsDue.isEmpty()) {
                    Text(
                        text = "—",
                        style = ClinicalValueTextStyle.copy(fontSize = 14.sp, color = AshaColors.InkPlaceholder),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        draft.vaccinationsDue.forEach { vaccine ->
                            StatusPill(
                                text = vaccine,
                                containerColor = AshaColors.ClinicalTeal,
                                contentColor = AshaColors.PureWhite,
                            )
                        }
                    }
                }
            }
        }

        // Danger Flags strip (if flagged)
        if (draft.dangerSigns.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = AshaColors.DangerBadgeSurface,
                border = BorderStroke(1.dp, AshaColors.DangerCrimson),
            ) {
                Text(
                    text = "🚨 FLAGGED: ${draft.dangerSigns.joinToString(", ")}",
                    color = AshaColors.DangerCrimsonDark,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        // Confirmation prompt footer
        if (sessionStatus == "AWAITING_CONFIRMATION") {
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = AshaColors.DividerSubtle)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Say “Haan save” or speak a correction",
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onQuickSave,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.VerifiedEmerald,
                        contentColor = AshaColors.PureWhite,
                    ),
                ) {
                    Text(text = "Confirm Save", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun FieldTile(
    label: String,
    value: String,
    diff: FieldDiff?,
    unitSuffix: String = "",
    modifier: Modifier = Modifier,
) {
    val isDiff = diff != null
    val bgColor by animateColorAsState(
        targetValue = if (isDiff) AshaColors.VoiceDiffSurface else AshaColors.SunlitCanvas,
        animationSpec = tween(300),
        label = "diffBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (isDiff) AshaColors.VoiceDiffAmber else AshaColors.DividerSubtle,
        animationSpec = tween(300),
        label = "diffBorder",
    )

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bgColor,
        border = BorderStroke(if (isDiff) 2.dp else 1.dp, borderColor),
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.sp,
                        color = if (isDiff) AshaColors.VoiceDiffAmberDark else AshaColors.InkMuted,
                    ),
                )
                if (isDiff) {
                    StatusPill(
                        text = "VOICE FIX",
                        containerColor = AshaColors.VoiceDiffAmber,
                        contentColor = AshaColors.PureWhite,
                    )
                }
            }
            Spacer(modifier = Modifier.height(3.dp))
            if (diff != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text(
                        text = diff.oldValue.toDisplayString(),
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = AshaColors.VoiceDiffAmberDark,
                            textDecoration = TextDecoration.LineThrough,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    Text(
                        text = "→",
                        color = AshaColors.VoiceDiffAmberDark,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "${diff.newValue.toDisplayString()}$unitSuffix",
                        style = ClinicalValueTextStyle.copy(fontSize = 15.sp),
                        maxLines = 1,
                    )
                }
            } else {
                Text(
                    text = value,
                    style = ClinicalValueTextStyle.copy(
                        fontSize = 15.sp,
                        color = if (value == "—") AshaColors.InkPlaceholder else AshaColors.InkPrimary,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun RegisterIntakeCard(
    draft: RegisterIntakeDraft,
    sessionStatus: String,
    activeDiff: FieldDiff?,
) {
    SunlitCard(
        modifier = Modifier.fillMaxWidth(),
        borderColor = AshaColors.ClinicalTeal,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "NEW PATIENT · FIELD : ANSWER",
                    style = MaterialTheme.typography.labelSmall.copy(color = AshaColors.ClinicalTeal),
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = draft.patientName ?: "Speak patient name & details...",
                    color = if (draft.patientName != null) AshaColors.InkPrimary else AshaColors.InkPlaceholder,
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            StatusPill(
                text = if (sessionStatus == "AWAITING_CONFIRMATION") "CONFIRM SAVE" else "VOICE INTAKE",
                containerColor = AshaColors.ClinicalTealTint,
                contentColor = AshaColors.ClinicalTeal,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = AshaColors.DividerSubtle)
        Spacer(modifier = Modifier.height(8.dp))

        val fieldsToShow = if (draft.expectedFields.isNotEmpty()) {
            draft.expectedFields
        } else {
            draft.fieldAnswers.keys.toList()
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            fieldsToShow.forEach { fieldName ->
                val answer = draft.fieldAnswers[fieldName]
                val isDiff = activeDiff?.field.equals(fieldName, ignoreCase = true)
                val rowBg = when {
                    isDiff -> AshaColors.VoiceDiffSurface
                    answer != null -> AshaColors.VerifiedSurface
                    else -> AshaColors.SunlitCanvas
                }
                val rowBorder = when {
                    isDiff -> AshaColors.VoiceDiffAmber
                    answer != null -> AshaColors.VerifiedBorder
                    else -> AshaColors.DividerSubtle
                }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = rowBg,
                    border = BorderStroke(if (isDiff) 2.dp else 1.dp, rowBorder),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = fieldName,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isDiff) AshaColors.VoiceDiffAmberDark else AshaColors.InkSecondary,
                            ),
                        )
                        if (isDiff && activeDiff != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = activeDiff.oldValue.toDisplayString(),
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = AshaColors.VoiceDiffAmberDark,
                                        textDecoration = TextDecoration.LineThrough,
                                    ),
                                )
                                Text(
                                    text = "→ ${activeDiff.newValue.toDisplayString()}",
                                    style = ClinicalValueTextStyle.copy(fontSize = 14.sp),
                                )
                            }
                        } else {
                            Text(
                                text = answer?.toDisplayString() ?: "Listening...",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    color = if (answer != null) AshaColors.InkPrimary else AshaColors.InkPlaceholder,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptAndReadbackCard(
    partialTranscript: String,
    finalTranscript: String,
    agentReadback: String,
    isAgentSpeaking: Boolean,
    isIntakeMode: Boolean,
) {
    val hasSpeech = partialTranscript.isNotBlank() || finalTranscript.isNotBlank() || agentReadback.isNotBlank()
    if (!hasSpeech) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = AshaColors.SurfaceMuted,
            border = BorderStroke(1.dp, AshaColors.CardBorder),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Tap the mic once and speak naturally in your language",
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isIntakeMode) {
                        "e.g. “Savitha Rao, Age 26, Blood Group O+, House 19”"
                    } else {
                        "e.g. “Sunita Devi ANC checkup, weight 3.2 kilo, IFA 30, TT2 due”"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        return
    }

    SunlitCard(modifier = Modifier.fillMaxWidth()) {
        if (partialTranscript.isNotBlank() || finalTranscript.isNotBlank()) {
            Text(
                text = "YOU SPOKE (PRISMA v3.0)",
                style = MaterialTheme.typography.labelSmall,
            )
            Spacer(modifier = Modifier.height(4.dp))
            if (partialTranscript.isNotBlank()) {
                Text(
                    text = "$partialTranscript...",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontStyle = FontStyle.Italic,
                        color = AshaColors.InkMuted,
                    ),
                )
            } else {
                Text(
                    text = "“$finalTranscript”",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }

        if (agentReadback.isNotBlank()) {
            if (partialTranscript.isNotBlank() || finalTranscript.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = AshaColors.DividerSubtle)
                Spacer(modifier = Modifier.height(10.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "AGENT READ-BACK (TIMBRE v3.0)",
                    style = MaterialTheme.typography.labelSmall.copy(color = AshaColors.ClinicalTeal),
                )
                if (isAgentSpeaking) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SpeakingWaveformBars()
                        Text(
                            text = "Speak to interrupt",
                            color = AshaColors.ClinicalTeal,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = agentReadback,
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun SpeakingWaveformBars() {
    val transition = rememberInfiniteTransition(label = "ttsWaveform")
    val bar1 by transition.animateFloat(
        initialValue = 5f,
        targetValue = 16f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar1",
    )
    val bar2 by transition.animateFloat(
        initialValue = 14f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(360, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar2",
    )
    val bar3 by transition.animateFloat(
        initialValue = 7f,
        targetValue = 18f,
        animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar3",
    )
    val bar4 by transition.animateFloat(
        initialValue = 12f,
        targetValue = 5f,
        animationSpec = infiniteRepeatable(tween(390, easing = LinearEasing), RepeatMode.Reverse),
        label = "bar4",
    )

    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.height(18.dp),
    ) {
        listOf(bar1, bar2, bar3, bar4).forEach { h ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(h.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(AshaColors.ClinicalTeal)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MockSimulatorDrawer(
    isIntakeMode: Boolean,
    onSendUtterance: (String) -> Unit,
    onClose: () -> Unit,
) {
    var customInput by remember { mutableStateOf("") }

    val quickChips = if (isIntakeMode) {
        listOf(
            "Savitha Rao, Age 26, Blood Group O+, House 19",
            "No, Age is 28",
            "Yes save",
        )
    } else {
        listOf(
            "Sunita Devi ANC checkup weight 3.2 kilo IFA goli 30 TT2 due",
            "No, weight is 2.8 kilo",
            "Haan theek hai",
            "Lakshmi baby high fever 103, what medicine should I give?",
        )
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = AshaColors.VoiceDiffSurface,
        border = BorderStroke(1.5.dp, AshaColors.VoiceDiffBorder),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "MOCK SIMULATOR QUICK-TEST",
                    color = AshaColors.VoiceDiffAmberDark,
                    style = MaterialTheme.typography.labelSmall,
                )
                Text(
                    text = "Close",
                    color = AshaColors.VoiceDiffAmberDark,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(onClick = onClose),
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                quickChips.forEach { text ->
                    val isDanger = text.contains("fever", ignoreCase = true)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isDanger) AshaColors.DangerBadgeSurface else AshaColors.PureWhite,
                        border = BorderStroke(
                            1.dp,
                            if (isDanger) AshaColors.DangerCrimson else AshaColors.VoiceDiffBorder,
                        ),
                        modifier = Modifier.clickable { onSendUtterance(text) },
                    ) {
                        Text(
                            text = text,
                            color = if (isDanger) AshaColors.DangerCrimsonDark else AshaColors.InkPrimary,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    placeholder = { Text("Type simulated utterance...", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AshaColors.ClinicalTeal,
                    modifier = Modifier
                        .size(48.dp)
                        .clickable {
                            if (customInput.isNotBlank()) {
                                onSendUtterance(customInput)
                                customInput = ""
                            }
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = "Send simulated turn",
                            tint = AshaColors.PureWhite,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OneThumbMicDock(
    isMicActive: Boolean,
    isAgentSpeaking: Boolean,
    rmsLevel: Float,
    showSimButton: Boolean,
    isSimDrawerOpen: Boolean,
    onMicClick: () -> Unit,
    onResetClick: () -> Unit,
    onSimClick: () -> Unit,
) {
    val ringScale by animateFloatAsState(
        targetValue = if (isMicActive) 1.08f + (rmsLevel * 0.45f) else if (isAgentSpeaking) 1.18f else 1f,
        animationSpec = tween(90),
        label = "rmsRingScale",
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AshaColors.PureWhite,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 10.dp, start = 20.dp, end = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Secondary Left: Reset Draft (48dp+)
                DockSideButton(
                    label = "Reset",
                    icon = Icons.Rounded.Refresh,
                    highlighted = false,
                    onClick = onResetClick,
                )

                // Giant Center 80dp Mic Orb with Live Concentric RMS Pulse Ring
                Box(
                    modifier = Modifier.size(104.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isMicActive || isAgentSpeaking) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .scale(ringScale)
                                .clip(CircleShape)
                                .background(
                                    if (isMicActive) AshaColors.ClinicalTeal.copy(alpha = 0.25f)
                                    else AshaColors.ClinicalTealTint
                                )
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onMicClick),
                        shape = CircleShape,
                        color = if (isMicActive) AshaColors.DangerCrimson else AshaColors.ClinicalTeal,
                        border = BorderStroke(3.dp, AshaColors.PureWhite),
                        shadowElevation = 6.dp,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isMicActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = if (isMicActive) "Stop microphone" else "Start hands-free microphone",
                                tint = AshaColors.PureWhite,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }
                }

                // Secondary Right: Mock Simulator Drawer Toggle (visible only in mock mode)
                if (showSimButton) {
                    DockSideButton(
                        label = "Sim",
                        icon = Icons.Rounded.Bolt,
                        highlighted = isSimDrawerOpen,
                        onClick = onSimClick,
                    )
                } else {
                    Spacer(modifier = Modifier.size(54.dp))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when {
                    isAgentSpeaking -> "Reading back · Tap or speak to barge-in"
                    isMicActive -> "Listening · Auto-VAD Active"
                    else -> "Tap to Start Hands-Free Mic"
                },
                color = if (isMicActive) AshaColors.DangerCrimson else AshaColors.InkSecondary,
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
private fun DockSideButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = if (highlighted) AshaColors.VoiceDiffSurface else AshaColors.PureWhite,
        border = BorderStroke(
            1.5.dp,
            if (highlighted) AshaColors.VoiceDiffAmber else AshaColors.CardBorder,
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) AshaColors.VoiceDiffAmberDark else AshaColors.InkSecondary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                color = if (highlighted) AshaColors.VoiceDiffAmberDark else AshaColors.InkSecondary,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            )
        }
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
