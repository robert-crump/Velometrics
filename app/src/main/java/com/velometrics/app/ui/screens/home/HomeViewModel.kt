package com.velometrics.app.ui.screens.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.dropbox.DropboxSyncOutcome
import com.velometrics.app.data.dropbox.DropboxSyncOutcomeStore
import com.velometrics.app.data.dropbox.DropboxSyncScheduler
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.domain.model.RideRevealContent
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.DeleteResult
import com.velometrics.app.domain.service.ImportProgress
import com.velometrics.app.domain.service.ImportSource
import com.velometrics.app.domain.service.RideLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject

sealed class ImportUiState {
    data object Idle : ImportUiState()
    data class Loading(val fileName: String = "", val phase: String = "") : ImportUiState()
    data class BatchLoading(
        val current: Int,
        val total: Int
    ) : ImportUiState()
    data class SmallFileWarning(
        val fileName: String,
        val dataPointCount: Int,
        val current: Int,
        val total: Int
    ) : ImportUiState()
    data class Done(val result: ImportResult) : ImportUiState()
    /** Shown instead of [Done]/the Dropbox snackbar when the batch's newest ride is genuinely new. */
    data class RideReveal(val content: RideRevealContent) : ImportUiState()
}

data class MonthlyRideSummary(
    val yearMonth: YearMonth,
    val rideCount: Int,
    val totalKm: Double,
    val totalNetDurationSec: Int
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val rideLifecycle: RideLifecycle,
    private val importSourceReader: UriImportSourceReader,
    private val dropboxSyncScheduler: DropboxSyncScheduler,
    private val dropboxSyncOutcomeStore: DropboxSyncOutcomeStore
) : ViewModel() {

    // Tracks whether the first real DB emission has arrived.
    // Prevents the empty-state placeholder from flashing on startup.
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    val sessions: StateFlow<List<CyclingSessionSummary>> = sessionRepository.getAllSessionSummaries()
        .onEach { _isInitialLoading.value = false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val sessionCount: StateFlow<Int> = sessions
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Local state of a manual file-picker import. A Dropbox reveal from the outcome store is
    // layered on top of it in [importState] whenever no manual import is in progress.
    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = combine(
        _importState,
        dropboxSyncOutcomeStore.outcome
    ) { local, outcome ->
        if (local is ImportUiState.Idle && outcome is DropboxSyncOutcome.Reveal) {
            ImportUiState.RideReveal(outcome.content)
        } else {
            local
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ImportUiState.Idle)

    // --- Monthly stats (last 12 months, index 0 = oldest, index 11 = current month) ---

    val monthlyData: StateFlow<List<MonthlyRideSummary>> = sessions.map { allSessions ->
        val now = YearMonth.now()
        val zone = ZoneId.systemDefault()
        (11 downTo 0).map { offset ->
            val month = now.minusMonths(offset.toLong())
            val monthSessions = allSessions.filter { session ->
                YearMonth.from(session.sessionStart.atZone(zone)) == month
            }
            MonthlyRideSummary(
                yearMonth = month,
                rideCount = monthSessions.size,
                totalKm = monthSessions.sumOf { it.distanceKm },
                totalNetDurationSec = monthSessions.sumOf { it.netDurationSec }
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Selected index into monthlyData (0 = oldest, 11 = current). Default = current month.
    private val _selectedMonthIndex = MutableStateFlow(11)
    val selectedMonthIndex: StateFlow<Int> = _selectedMonthIndex.asStateFlow()

    val selectedMonthSummary: StateFlow<MonthlyRideSummary?> =
        combine(monthlyData, _selectedMonthIndex) { data, idx ->
            data.getOrNull(idx)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun selectMonthIndex(index: Int) {
        _selectedMonthIndex.value = index.coerceIn(0, 11)
    }

    // --- Multiselect bulk delete (#194) ---

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    private val _selectedSessionIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedSessionIds: StateFlow<Set<Long>> = _selectedSessionIds.asStateFlow()

    private val _deleteError = MutableStateFlow<String?>(null)
    val deleteError: StateFlow<String?> = _deleteError.asStateFlow()

    /** Long-press on a Home ride card: enters selection mode with that card pre-selected. */
    fun enterSelectionMode(sessionId: Long) {
        _selectionMode.value = true
        _selectedSessionIds.value = setOf(sessionId)
    }

    /** Tapping a card while in selection mode: multiselect toggle, not single-select. */
    fun toggleSessionSelected(sessionId: Long) {
        if (!_selectionMode.value) return
        _selectedSessionIds.update { current ->
            if (sessionId in current) current - sessionId else current + sessionId
        }
    }

    /** Close (X) or back: exits selection mode and clears all selections. */
    fun exitSelectionMode() {
        _selectionMode.value = false
        _selectedSessionIds.value = emptySet()
    }

    fun clearDeleteError() {
        _deleteError.value = null
    }

    /**
     * Deletes every selected session via [RideLifecycle.delete]. On success, exits selection mode;
     * the Home list updates on its own via the existing Flow-backed query. On failure, leaves
     * selection mode active with the same items selected (so the user can retry) and surfaces
     * [deleteError].
     */
    fun deleteSelectedSessions() {
        val ids = _selectedSessionIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            when (rideLifecycle.delete(ids)) {
                DeleteResult.Deleted -> exitSelectionMode()
                DeleteResult.Failed -> _deleteError.value = "Couldn't delete rides"
            }
        }
    }

    // --- Import ---

    // Answered by the small-file dialog; the import batch is suspended on it in the meantime.
    private var smallFileDecision: CompletableDeferred<Boolean>? = null

    fun confirmSmallFileImport() {
        smallFileDecision?.complete(true)
    }

    fun skipSmallFile() {
        smallFileDecision?.complete(false)
    }

    fun importFromUri(uri: Uri) {
        importFromUris(listOf(uri))
    }

    fun importFromUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            runImport(importSourceReader.sourcesFor(uris))
        }
    }

    fun importFromSources(sources: List<ImportSource>) {
        viewModelScope.launch(Dispatchers.IO) { runImport(sources) }
    }

    private suspend fun runImport(sources: List<ImportSource>) {
        var latest = ImportProgress.Importing(current = 0, total = sources.size, fileName = "")
        rideLifecycle.import(sources) { smallFile ->
            val decision = CompletableDeferred<Boolean>()
            smallFileDecision = decision
            _importState.value = ImportUiState.SmallFileWarning(
                fileName = smallFile.fileName,
                dataPointCount = smallFile.dataPointCount,
                current = latest.current,
                total = latest.total
            )
            val shouldImport = try {
                decision.await()
            } finally {
                smallFileDecision = null
            }
            _importState.value = ImportUiState.BatchLoading(latest.current, latest.total)
            shouldImport
        }.collect { progress ->
            when (progress) {
                is ImportProgress.Importing -> {
                    latest = progress
                    _importState.value = ImportUiState.BatchLoading(progress.current, progress.total)
                }
                is ImportProgress.Finished -> {
                    _importState.value = if (progress.reveal != null) {
                        ImportUiState.RideReveal(progress.reveal)
                    } else {
                        ImportUiState.Done(progress.results.lastOrNull() ?: ImportResult.Error("No files"))
                    }
                }
            }
        }
    }

    fun clearImportState() {
        _importState.value = ImportUiState.Idle
        viewModelScope.launch {
            if (dropboxSyncOutcomeStore.outcome.first() is DropboxSyncOutcome.Reveal) {
                dropboxSyncOutcomeStore.consume()
            }
        }
    }

    // --- Dropbox sync (WorkManager-backed, see DropboxSyncScheduler) ---

    val isSyncing: StateFlow<Boolean> = dropboxSyncScheduler.isSyncing
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val dropboxSyncMessage: StateFlow<String?> = dropboxSyncOutcomeStore.outcome
        .map { (it as? DropboxSyncOutcome.Message)?.text }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun clearDropboxSyncMessage() {
        viewModelScope.launch {
            if (dropboxSyncOutcomeStore.outcome.first() is DropboxSyncOutcome.Message) {
                dropboxSyncOutcomeStore.consume()
            }
        }
    }

    /** Pull-to-refresh entry point. */
    fun syncDropbox(isUserInitiated: Boolean = true) {
        dropboxSyncScheduler.sync(isUserInitiated)
    }

    init {
        dropboxSyncScheduler.autoSync()
    }
}
