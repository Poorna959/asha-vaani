package com.ashavaani.app.ui.registers

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.ashavaani.app.data.remote.ExportedFile
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.toDisplayString
import com.ashavaani.app.ui.components.StatusPill
import com.ashavaani.app.ui.components.SunlitCard
import com.ashavaani.app.ui.theme.AshaColors
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Custom Register Detail Screen (FR-7.2, FR-7.3, FR-7.5):
 * - Sticky instant name search bar (Room SQLite offline-capable)
 * - Clean mobile patient summary cards (NO spreadsheet grid)
 * - 1-tap Export `.xlsx` / `.csv` Bottom Sheet via Android FileProvider
 * - Prominent `Add Patient by Voice` extended FAB (FR-7.2) + optional manual `+` fallback
 */
@Composable
fun RegisterDetailScreen(
    register: CustomRegister?,
    patients: List<PatientRecord>,
    searchQuery: String,
    isBusy: Boolean,
    errorBanner: String?,
    onBack: () -> Unit,
    onSearchChange: (String) -> Unit,
    onOpenPatient: (PatientRecord) -> Unit,
    onAddPatientByVoice: (CustomRegister) -> Unit,
    onAddManualPatient: (registerId: String, name: String, category: String, answers: Map<String, JsonElement>) -> Unit,
    onExportRegister: (registerId: String, format: String, exportsDir: File, onReady: (ExportedFile) -> Unit) -> Unit,
    onClearError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showExportSheet by remember { mutableStateOf(false) }
    var showManualAddSheet by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AshaColors.SunlitCanvas),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Sub-header Action Bar
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
                            text = "Registers",
                            style = MaterialTheme.typography.labelMedium,
                            color = AshaColors.InkSecondary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AshaColors.PureWhite,
                        border = BorderStroke(1.5.dp, AshaColors.ClinicalTeal),
                        modifier = Modifier.clickable {
                            if (register != null) showExportSheet = true
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "Export",
                                tint = AshaColors.ClinicalTeal,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                text = "Export .xlsx/.csv",
                                color = AshaColors.ClinicalTeal,
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.ExtraBold),
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = AshaColors.PureWhite,
                        border = BorderStroke(1.dp, AshaColors.CardBorder),
                        modifier = Modifier.clickable {
                            if (register != null) showManualAddSheet = true
                        },
                    ) {
                        Box(
                            modifier = Modifier.padding(8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "Manual add patient",
                                tint = AshaColors.InkSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }

            // Sticky Live Name Search Bar (FR-7.3)
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                placeholder = { Text("Search patient by name or ID...", fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = AshaColors.InkMuted,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(
                                imageVector = Icons.Rounded.Close,
                                contentDescription = "Clear search",
                                tint = AshaColors.InkMuted,
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (errorBanner != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(12.dp),
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

            // Patient List Cards
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 92.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (patients.isEmpty()) {
                    item {
                        SunlitCard(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = if (searchQuery.isBlank()) {
                                    "No patients recorded in this register yet"
                                } else {
                                    "No patients match “$searchQuery”"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap “Add Patient by Voice” below to onboard a beneficiary hands-free.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                } else {
                    items(patients, key = { it.id }) { patient ->
                        PatientSummaryCard(
                            patient = patient,
                            onClick = { onOpenPatient(patient) },
                        )
                    }
                }
            }
        }

        // Prominent FR-7.2 Voice Intake Extended FAB
        if (register != null) {
            ExtendedFloatingActionButton(
                onClick = { onAddPatientByVoice(register) },
                containerColor = AshaColors.ClinicalTeal,
                contentColor = AshaColors.PureWhite,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
                icon = { Icon(imageVector = Icons.Rounded.Mic, contentDescription = null) },
                text = { Text("Add Patient by Voice", style = MaterialTheme.typography.labelLarge) },
            )
        }
    }

    if (showExportSheet && register != null) {
        ExportRegisterSheet(
            registerName = register.name,
            patientCount = patients.size,
            isBusy = isBusy,
            onExportFormat = { format ->
                val exportsDir = File(context.cacheDir, "exports")
                onExportRegister(register.id, format, exportsDir) { exported ->
                    showExportSheet = false
                    launchFileProviderShare(context, exported)
                }
            },
            onDismiss = { showExportSheet = false },
        )
    }

    if (showManualAddSheet && register != null) {
        ManualAddPatientSheet(
            register = register,
            onSubmit = { name, category, answers ->
                onAddManualPatient(register.id, name, category, answers)
                showManualAddSheet = false
            },
            onDismiss = { showManualAddSheet = false },
        )
    }
}

@Composable
private fun PatientSummaryCard(
    patient: PatientRecord,
    onClick: () -> Unit,
) {
    val initials = patient.patientName
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .joinToString("") { it.first().uppercase() }
        .ifEmpty { "P" }

    val snippet = patient.fieldAnswers.entries
        .take(3)
        .joinToString(" · ") { "${it.key}: ${it.value.toDisplayString()}" }

    SunlitCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(AshaColors.ClinicalTealTint),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = initials,
                    color = AshaColors.ClinicalTeal,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.ExtraBold),
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = patient.patientName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    StatusPill(
                        text = patient.category,
                        containerColor = AshaColors.SurfaceMuted,
                        contentColor = AshaColors.InkSecondary,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (snippet.isNotBlank()) "${patient.id} · $snippet" else patient.id,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = "Open patient profile",
                tint = AshaColors.ClinicalTeal,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportRegisterSheet(
    registerName: String,
    patientCount: Int,
    isBusy: Boolean,
    onExportFormat: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AshaColors.PureWhite,
        shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Export Register (FR-7.5)", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "$registerName · $patientCount patients",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(color = AshaColors.DividerSubtle)

            Text(
                text = "Downloads a clean tabular file from FastAPI and launches Android’s native Share Sheet (WhatsApp, PHC Email, Drive) via FileProvider.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Button(
                onClick = { onExportFormat("xlsx") },
                enabled = !isBusy,
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
                    text = if (isBusy) "Downloading..." else "Export Excel Spreadsheet (.xlsx)",
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            OutlinedButton(
                onClick = { onExportFormat("csv") },
                enabled = !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.5.dp, AshaColors.ClinicalTeal),
            ) {
                Text(
                    text = "Export CSV File (.csv)",
                    color = AshaColors.ClinicalTeal,
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualAddPatientSheet(
    register: CustomRegister,
    onSubmit: (name: String, category: String, answers: Map<String, JsonElement>) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var patientName by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(RegisterFieldHelpers.inferRegisterCategory(register.name)) }
    val fieldInputs = remember(register.id) { mutableStateMapOf<String, String>() }

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
                Text(text = "Add Patient (Manual Fallback)", style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close")
                }
            }

            HorizontalDivider(color = AshaColors.DividerSubtle)

            OutlinedTextField(
                value = patientName,
                onValueChange = { patientName = it },
                label = { Text("Patient Name *") },
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

            register.fields.sortedBy { it.orderIndex }.forEach { field ->
                OutlinedTextField(
                    value = fieldInputs[field.fieldName].orEmpty(),
                    onValueChange = { fieldInputs[field.fieldName] = it },
                    label = { Text("${field.fieldName} (${field.fieldType})") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    val typedAnswers = buildMap<String, JsonElement> {
                        register.fields.forEach { field ->
                            val raw = fieldInputs[field.fieldName]?.trim().orEmpty()
                            if (raw.isNotEmpty()) {
                                put(field.fieldName, RegisterFieldHelpers.parseTypedAnswer(raw, field.fieldType))
                            }
                        }
                    }
                    onSubmit(patientName, category, typedAnswers)
                },
                enabled = patientName.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AshaColors.ClinicalTeal,
                    contentColor = AshaColors.PureWhite,
                ),
            ) {
                Text("Save Patient", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

private fun launchFileProviderShare(context: Context, exported: ExportedFile) {
    runCatching {
        val authority = RegisterFieldHelpers.fileProviderAuthority(context.packageName)
        val uri = FileProvider.getUriForFile(context, authority, exported.file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = exported.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, exported.filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share ${exported.filename}"))
    }
}
