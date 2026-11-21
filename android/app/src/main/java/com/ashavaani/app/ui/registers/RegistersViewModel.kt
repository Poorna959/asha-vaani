package com.ashavaani.app.ui.registers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashavaani.app.AppContainer
import com.ashavaani.app.data.AshaRepository
import com.ashavaani.app.data.remote.ExportedFile
import com.ashavaani.app.model.CustomRegister
import com.ashavaani.app.model.CustomRegisterCreate
import com.ashavaani.app.model.PatientProfileView
import com.ashavaani.app.model.PatientRecord
import com.ashavaani.app.model.PatientRecordCreate
import com.ashavaani.app.model.PatientRecordUpdate
import com.ashavaani.app.model.RegisterFieldCreate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * ViewModel powering the entire Custom Registers flow (FR-7.1 – FR-7.6):
 * Registers list, + New Register builder, Register Detail + live search, Field:Answer Patient Profile,
 * optional manual add/edit fallback, and `.xlsx`/`.csv` FileProvider export downloads.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RegistersViewModel(
    private val repository: AshaRepository,
) : ViewModel() {

    val registers: StateFlow<List<CustomRegister>> = repository.observeRegisters()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val patientCounts: StateFlow<Map<String, Int>> = repository.observePatientCountsByRegister()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _selectedRegisterId = MutableStateFlow<String?>(null)
    val selectedRegisterId: StateFlow<String?> = _selectedRegisterId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val patientsInSelectedRegister: StateFlow<List<PatientRecord>> =
        combine(_selectedRegisterId, _searchQuery) { regId, query -> regId to query }
            .flatMapLatest { (regId, query) ->
                if (regId.isNullOrBlank()) {
                    flowOf(emptyList())
                } else {
                    repository.observePatients(regId, query)
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedProfile = MutableStateFlow<PatientProfileView?>(null)
    val selectedProfile: StateFlow<PatientProfileView?> = _selectedProfile.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    init {
        refreshAll(force = false)
    }

    fun refreshAll(force: Boolean = true) {
        viewModelScope.launch {
            repository.refreshAllFromServer(force = force)
        }
    }

    fun openRegister(registerId: String) {
        _selectedRegisterId.value = registerId
        _searchQuery.value = ""
        viewModelScope.launch {
            repository.refreshRegisterPatients(registerId)
        }
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun createRegister(
        name: String,
        description: String,
        fields: List<RegisterFieldCreate>,
        onSuccess: (CustomRegister) -> Unit = {},
    ) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _errorBanner.value = "Please enter a register name."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            repository.createRegister(
                CustomRegisterCreate(
                    name = cleanName,
                    description = description.trim(),
                    fields = fields,
                )
            ).onSuccess { created ->
                _statusToast.value = "Created “${created.name}” (${created.fields.size} fields)"
                onSuccess(created)
            }.onFailure { err ->
                _errorBanner.value = "Could not create register: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun addManualPatient(
        registerId: String,
        patientName: String,
        category: String,
        answers: Map<String, JsonElement>,
        onSuccess: (PatientRecord) -> Unit = {},
    ) {
        val cleanName = patientName.trim()
        if (cleanName.isEmpty()) {
            _errorBanner.value = "Patient name is required."
            return
        }
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            repository.addPatient(
                registerId = registerId,
                payload = PatientRecordCreate(
                    registerId = registerId,
                    patientName = cleanName,
                    category = category,
                    fieldAnswers = answers,
                ),
            ).onSuccess { created ->
                _statusToast.value = if (created.matchedExisting) {
                    "Updated existing ${created.patientName}"
                } else {
                    "Added ${created.patientName}"
                }
                onSuccess(created)
            }.onFailure { err ->
                _errorBanner.value = "Could not add patient: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun loadPatientProfile(patientId: String) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            repository.getPatientProfile(patientId)
                .onSuccess { profile ->
                    _selectedProfile.value = profile
                }
                .onFailure { err ->
                    _errorBanner.value = "Could not load patient profile: ${err.message}"
                }
            _isBusy.value = false
        }
    }

    fun updatePatientFields(
        patientId: String,
        patientName: String,
        category: String,
        answers: Map<String, JsonElement>,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            repository.updatePatient(
                patientId = patientId,
                payload = PatientRecordUpdate(
                    patientName = patientName.trim().takeIf { it.isNotEmpty() },
                    category = category,
                    fieldAnswers = answers,
                ),
            ).onSuccess {
                _statusToast.value = "Updated patient profile"
                loadPatientProfile(patientId)
                onSuccess()
            }.onFailure { err ->
                _errorBanner.value = "Could not update patient: ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun exportRegister(
        registerId: String,
        format: String,
        exportsDir: File,
        onExportReady: (ExportedFile) -> Unit,
    ) {
        viewModelScope.launch {
            _isBusy.value = true
            _errorBanner.value = null
            repository.downloadRegisterExport(
                registerId = registerId,
                format = format,
                exportsDir = exportsDir,
            ).onSuccess { exported ->
                _statusToast.value = "Exported ${exported.filename}"
                onExportReady(exported)
            }.onFailure { err ->
                _errorBanner.value = "Export failed (requires server connection): ${err.message}"
            }
            _isBusy.value = false
        }
    }

    fun clearToast() {
        _statusToast.value = null
    }

    fun clearError() {
        _errorBanner.value = null
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return RegistersViewModel(repository = container.repository) as T
                }
            }
    }
}
