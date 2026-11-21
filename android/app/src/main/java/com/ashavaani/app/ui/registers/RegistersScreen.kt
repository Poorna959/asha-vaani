package com.ashavaani.app.ui.registers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.RegisterFieldCreate
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.theme.AshaColors
import com.ashavaani.app.ui.theme.ClinicalValueTextStyle
import kotlinx.coroutines.delay

/**
 * Custom First-Visit Registers list screen (FR-7.1).
 * Displays sunlit high-contrast register cards with patient counts, field preview chips,
 * and an extended `+ New Register` FAB.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RegistersScreen(
    registers: List<CustomRegister>,
    patientCounts: Map<String, Int>,
    statusToast: String?,
    errorBanner: String?,
    onRefresh: () -> Unit,
    onOpenRegister: (CustomRegister) -> Unit,
    onCreateRegister: (name: String, description: String, fields: List<RegisterFieldCreate>) -> Unit,
    onClearToast: () -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showNewRegisterSheet by remember { mutableStateOf(false) }

    LaunchedEffect(statusToast) {
        if (statusToast != null) {
            delay(3_200L)
            onClearToast()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 92.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "YOUR FIELD REGISTERS (${registers.size})",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "● Local-First",
                            color = AshaColors.VerifiedEmerald,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh registers",
                                tint = AshaColors.ClinicalTeal,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            if (statusToast != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AshaColors.VerifiedEmerald,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = statusToast,
                                color = AshaColors.PureWhite,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearToast, modifier = Modifier.size(24.dp)) {
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

            if (errorBanner != null) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        color = AshaColors.DangerBadgeSurface,
                        border = BorderStroke(1.dp, AshaColors.DangerCrimson),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = errorBanner,
                                color = AshaColors.DangerCrimsonDark,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = onClearError, modifier = Modifier.size(24.dp)) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Dismiss",
                                    tint = AshaColors.DangerCrimsonDark,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }

            if (registers.isEmpty()) {
                item {
                    SunlitCard(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "No registers synced yet",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Connect to the ASHA Vaani server or tap “+ New Register” to create a custom field intake register.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(registers, key = { it.id }) { register ->
                    val count = patientCounts[register.id] ?: 0
                    val categoryTag = RegisterFieldHelpers.inferRegisterCategory(register.name)

                    SunlitCard(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onOpenRegister(register) },
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                StatusPill(
                                    text = categoryTag,
                                    containerColor = AshaColors.ClinicalTealTint,
                                    contentColor = AshaColors.ClinicalTeal,
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = register.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (register.description.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = register.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = count.toString(),
                                    style = ClinicalValueTextStyle.copy(
                                        fontSize = 22.sp,
                                        color = AshaColors.ClinicalTeal,
                                    ),
                                )
                                Text(
                                    text = if (count == 1) "Patient" else "Patients",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                )
                            }
                        }

                        if (register.fields.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = AshaColors.DividerSubtle)
                            Spacer(modifier = Modifier.height(8.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                register.fields.take(5).forEach { field ->
                                    StatusPill(
                                        text = field.fieldName,
                                        containerColor = AshaColors.SurfaceMuted,
                                        contentColor = AshaColors.InkSecondary,
                                    )
                                }
                                if (register.fields.size > 5) {
                                    StatusPill(
                                        text = "+${register.fields.size - 5} more",
                                        containerColor = AshaColors.SurfaceMuted,
                                        contentColor = AshaColors.InkMuted,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Extended FAB inside lower thumb arc
        ExtendedFloatingActionButton(
            onClick = { showNewRegisterSheet = true },
            containerColor = AshaColors.ClinicalTeal,
            contentColor = AshaColors.PureWhite,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            icon = { Icon(imageVector = Icons.Rounded.Add, contentDescription = null) },
            text = { Text("New Register", style = MaterialTheme.typography.labelLarge) },
        )
    }

    if (showNewRegisterSheet) {
        NewRegisterBuilderSheet(
            onCreate = { name, desc, fields ->
                onCreateRegister(name, desc, fields)
                showNewRegisterSheet = false
            },
            onDismiss = { showNewRegisterSheet = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun NewRegisterBuilderSheet(
    onCreate: (name: String, description: String, fields: List<RegisterFieldCreate>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var customFieldName by remember { mutableStateOf("") }
    var customFieldType by remember { mutableStateOf("TEXT") }

    val selectedFields = remember {
        mutableStateListOf(
            PresetClinicalField("Age", "NUMBER"),
            PresetClinicalField("Blood Group", "TEXT"),
            PresetClinicalField("House / Ward", "TEXT"),
        )
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Create Custom Register", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(color = AshaColors.DividerSubtle)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Register Name") },
                placeholder = { Text("e.g. Immunisation Camp Ward 4") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Short Description (optional)") },
                placeholder = { Text("e.g. First-visit village survey") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "QUICK-ADD CLINICAL FIELDS",
                style = MaterialTheme.typography.labelSmall,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RegisterFieldHelpers.PRESET_FIELDS.forEach { preset ->
                    val isSelected = selectedFields.any { it.name.equals(preset.name, ignoreCase = true) }
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (isSelected) AshaColors.ClinicalTeal else AshaColors.SunlitCanvas,
                        border = BorderStroke(
                            1.5.dp,
                            if (isSelected) AshaColors.ClinicalTeal else AshaColors.CardBorder,
                        ),
                        modifier = Modifier.clickable {
                            if (isSelected) {
                                selectedFields.removeAll { it.name.equals(preset.name, ignoreCase = true) }
                            } else {
                                selectedFields.add(preset)
                            }
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = AshaColors.PureWhite,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                            Text(
                                text = preset.name,
                                color = if (isSelected) AshaColors.PureWhite else AshaColors.InkSecondary,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }

            Text(
                text = "ADD CUSTOM FIELD",
                style = MaterialTheme.typography.labelSmall,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = customFieldName,
                    onValueChange = { customFieldName = it },
                    placeholder = { Text("Field name (e.g. Hemoglobin)") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        val trimmed = customFieldName.trim()
                        if (trimmed.isNotEmpty() && selectedFields.none { it.name.equals(trimmed, ignoreCase = true) }) {
                            selectedFields.add(PresetClinicalField(trimmed, customFieldType))
                            customFieldName = ""
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AshaColors.ClinicalTealTint,
                        contentColor = AshaColors.ClinicalTeal,
                    ),
                ) {
                    Text("+ Add", style = MaterialTheme.typography.labelLarge)
                }
            }

            // Custom field type selector
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                RegisterFieldHelpers.FIELD_TYPES.forEach { type ->
                    val active = type == customFieldType
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = if (active) AshaColors.InkPrimary else AshaColors.SurfaceMuted,
                        modifier = Modifier.clickable { customFieldType = type },
                    ) {
                        Text(
                            text = type,
                            color = if (active) AshaColors.PureWhite else AshaColors.InkSecondary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            // Active field preview
            AnimatedVisibility(visible = selectedFields.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SELECTED FIELDS (${selectedFields.size})",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        selectedFields.forEach { field ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = AshaColors.VerifiedSurface,
                                border = BorderStroke(1.dp, AshaColors.VerifiedBorder),
                                modifier = Modifier.clickable { selectedFields.remove(field) },
                            ) {
                                Text(
                                    text = "${field.name} (${field.type}) ✕",
                                    color = AshaColors.VerifiedEmerald,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }

            Button(
                onClick = {
                    val ordered = RegisterFieldHelpers.buildOrderedFields(selectedFields)
                    onCreate(name.ifBlank { "Custom Field Register" }, description, ordered)
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
                Text("Create Register", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}
