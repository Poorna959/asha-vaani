package com.ashavaani.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.ashavaani.app.AppContainer
import com.ashavaani.app.data.AshaRepository
import com.ashavaani.app.model.FollowUp
import com.ashavaani.app.model.VisitRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class FollowUpFilter(val label: String) {
    ALL("All"),
    ESCALATED("PHC Urgent"),
    PENDING("Pending"),
    COMPLETED("Completed"),
}

enum class VisitFilter(val label: String) {
    ALL("All Visits"),
    ESCALATED("PHC Escalated Only"),
}

object HistoryFilterHelpers {
    fun filterFollowUps(items: List<FollowUp>, filter: FollowUpFilter): List<FollowUp> = when (filter) {
        FollowUpFilter.ALL -> items
        FollowUpFilter.ESCALATED -> items.filter { it.status.equals("ESCALATED", ignoreCase = true) }
        FollowUpFilter.PENDING -> items.filter { it.status.equals("PENDING", ignoreCase = true) }
        FollowUpFilter.COMPLETED -> items.filter { it.status.equals("COMPLETED", ignoreCase = true) }
    }

    fun filterVisits(items: List<VisitRecord>, filter: VisitFilter): List<VisitRecord> = when (filter) {
        VisitFilter.ALL -> items
        VisitFilter.ESCALATED -> items.filter { it.hasDangerSign || it.dangerSigns.isNotEmpty() }
    }

    fun formatIsoDateShort(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return iso.substringBefore("T").ifBlank { iso }
    }
}

/**
 * ViewModel powering both Tab 3 (Follow-Up Queue) and Tab 4 (Saved Home Visits History).
 * Observes Room Flows directly so new voice saves appear immediately and survive offline drops.
 */
class HistoryViewModel(
    private val repository: AshaRepository,
) : ViewModel() {

    private val _followUpFilter = MutableStateFlow(FollowUpFilter.ALL)
    val followUpFilter: StateFlow<FollowUpFilter> = _followUpFilter.asStateFlow()

    private val _visitFilter = MutableStateFlow(VisitFilter.ALL)
    val visitFilter: StateFlow<VisitFilter> = _visitFilter.asStateFlow()

    val allFollowUps: StateFlow<List<FollowUp>> = repository.observeFollowUps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredFollowUps: StateFlow<List<FollowUp>> =
        combine(allFollowUps, _followUpFilter) { list, filter ->
            HistoryFilterHelpers.filterFollowUps(list, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allVisits: StateFlow<List<VisitRecord>> = repository.observeVisits(onlyDanger = false)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val filteredVisits: StateFlow<List<VisitRecord>> =
        combine(allVisits, _visitFilter) { list, filter ->
            HistoryFilterHelpers.filterVisits(list, filter)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _errorBanner = MutableStateFlow<String?>(null)
    val errorBanner: StateFlow<String?> = _errorBanner.asStateFlow()

    fun setFollowUpFilter(filter: FollowUpFilter) {
        _followUpFilter.value = filter
    }

    fun setVisitFilter(filter: VisitFilter) {
        _visitFilter.value = filter
    }

    fun refreshAll() {
        viewModelScope.launch {
            repository.refreshAllFromServer()
        }
    }

    fun markFollowUpCompleted(followUp: FollowUp) {
        viewModelScope.launch {
            _errorBanner.value = null
            repository.updateFollowUpStatus(followUp.id, "COMPLETED")
                .onSuccess {
                    _statusToast.value = "Completed follow-up for ${followUp.beneficiaryName}"
                }
                .onFailure { err ->
                    _errorBanner.value = "Could not update follow-up: ${err.message}"
                }
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
                    return HistoryViewModel(repository = container.repository) as T
                }
            }
    }
}
