package com.ashavaani.app.ui.registers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.model.PatientProfileView
import com.ashavaani.app.model.toDisplayString
import com.ashavaani.app.ui.theme.AshaColors
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Clean Mobile `Field : Answer` Patient Profile Screen (FR-7.4 & FR-7.6).
 *
 * Explicitly renders a vertical high-contrast `Field : Answer` card stack (NEVER a cramped
 * Excel spreadsheet grid), with a pinned 56dp `Start Follow-Up Visit by Voice` CTA (FR-7.6)
 * and optional manual Edit fallback.
 */
@Composable
fun PatientProfileScreen(
    profile: PatientProfileView?,
    isBusy: Boolean,
    onBack: () -> Unit,
    onStartFollowUpVisit: (patientId: String) -> Unit,
    onUpdatePatient: (patientId: String, name: String, category: String, answers: Map<String, JsonElement>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showEditSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas),
    ) {
        // Sub-header bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = AshaColors.PureWhite,
                border = BorderStroke(1.dp, AshaColors.CardBorder),
                modifier = Modifier.clickable(onClick = onBack),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = AshaColors.InkSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = profile?.registerName ?: "Register",
                        style = MaterialTheme.typography.labelMedium,
                        color = AshaColors.InkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (profile != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AshaColors.PureWhite,
                    border = BorderStroke(1.dp, AshaColors.CardBorder),
                    modifier = Modifier.clickable { showEditSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Edit,
                            contentDescription = "Edit fields",
                            tint = AshaColors.ClinicalTeal,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Edit",
                            color = AshaColors.ClinicalTeal,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        if (profile == null) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (isBusy) {
                    CircularProgressIndicator(color = AshaColors.ClinicalTeal)
                } else {
                    Text(
                        text = "Patient profile not available.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Deep Clinical Teal Hero Header
                val initials = profile.patientName
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .take(2)
                    .joinToString("") { it.first().uppercase() }
                    .ifEmpty { "P" }

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = AshaColors.ClinicalTeal,
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(AshaColors.PureWhite.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = initials,
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.titleLarge,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = profile.patientName,
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${profile.patientId} · ${profile.category} Baseline",
                                color = AshaColors.ClinicalTealTint,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }

                // Clean Mobile `Field : Answer` Card (Explicitly NO Excel Grid)
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = AshaColors.PureWhite,
                    border = BorderStroke(1.5.dp, AshaColors.CardBorder),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AshaColors.SurfaceMuted)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "PATIENT DETAILS (FIELD : ANSWER)",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                                text = "Mobile View",
                                color = AshaColors.ClinicalTeal,
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                        HorizontalDivider(color = AshaColors.CardBorder)

                        if (profile.items.isEmpty()) {
                            Text(
                                text = "No custom field answers recorded yet.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        } else {
                            profile.items.forEachIndexed { index, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top,
                                ) {
                                    Text(
                                        text = item.fieldName.uppercase(),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = AshaColors.InkMuted,
                                            fontSize = 11.sp,
                                        ),
                                        modifier = Modifier
                                            .weight(0.44f)
                                            .padding(end = 10.dp, top = 2.dp),
                                    )
                                    Text(
                                        text = item.answer.toDisplayString(),
                                        style = MaterialTheme.typography.bodyLarge.copy(
                                            fontWeight = FontWeight.ExtraBold,
                                            color = AshaColors.InkPrimary,
                                        ),
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.weight(0.56f),
                                    )
                                }
                                if (index < profile.items.lastIndex) {
                                    HorizontalDivider(color = AshaColors.DividerSubtle)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Pinned Bottom Primary FR-7.6 CTA inside Natural One-Thumb Arc
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AshaColors.PureWhite,
                shadowElevation = 2.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Button(
                        onClick = { onStartFollowUpVisit(profile.patientId) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AshaColors.ClinicalTeal,
                            contentColor = AshaColors.PureWhite,
                        ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Start Follow-Up Visit by Voice",
                            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                        )
                    }
                }
            }
        }
    }

    if (showEditSheet && profile != null) {
        EditPatientFieldsSheet(
            profile = profile,
            onSave = { name, category, answers ->
                onUpdatePatient(profile.patientId, name, category, answers)
                showEditSheet = false
            },
            onDismiss = { showEditSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditPatientFieldsSheet(
    profile: PatientProfileView,
    onSave: (name: String, category: String, answers: Map<String, JsonElement>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember(profile.patientId) { mutableStateOf(profile.patientName) }
    var category by remember(profile.patientId) { mutableStateOf(profile.category) }
    val edits = remember(profile.patientId) {
        mutableStateMapOf<String, String>().apply {
            profile.items.forEach { item ->
                val text = item.answer.toDisplayString(fallback = "")
                put(item.fieldName, text)
            }
        }
    }

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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Edit Patient Profile", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(color = AshaColors.DividerSubtle)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Patient Name") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RegisterFieldHelpers.PATIENT_CATEGORIES.forEach { cat ->
                    val active = cat == category
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (active) AshaColors.ClinicalTeal else AshaColors.SurfaceMuted,
                        modifier = Modifier.clickable { category = cat },
                    ) {
                        Text(
                            text = cat,
                            color = if (active) AshaColors.PureWhite else AshaColors.InkSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        )
                    }
                }
            }

            profile.items.forEach { item ->
                OutlinedTextField(
                    value = edits[item.fieldName].orEmpty(),
                    onValueChange = { edits[item.fieldName] = it },
                    label = { Text(item.fieldName) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Button(
                onClick = {
                    val payload = edits.entries
                        .filter { it.value.isNotBlank() }
                        .associate { (k, v) -> k to JsonPrimitive(v.trim()) }
                    onSave(name, category, payload)
                },
                enabled = name.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AshaColors.ClinicalTeal,
                    contentColor = AshaColors.PureWhite,
                ),
            ) {
                Text("Save Changes", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
