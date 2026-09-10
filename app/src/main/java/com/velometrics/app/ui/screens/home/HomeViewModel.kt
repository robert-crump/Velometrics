package com.velometrics.app.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncResult
import com.velometrics.app.data.dropbox.DropboxSyncService
import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.domain.model.RideRevealContent
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.DropboxSyncCursorRepository
import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.domain.service.RideRevealEvaluator
import com.velometrics.app.domain.service.RouteClusteringService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val fitImportService: FitImportService,
    private val dropboxSyncService: DropboxSyncService,
    private val dropboxAuthRepository: DropboxAuthRepository,
    private val dropboxSyncCursorRepository: DropboxSyncCursorRepository,
    private val routeClusteringService: RouteClusteringService,
    private val intervalClusteringService: IntervalClusteringService,
    private val rideRevealEvaluator: RideRevealEvaluator,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "HomeViewModel"
    }

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

    private val _importState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val importState: StateFlow<ImportUiState> = _importState.asStateFlow()

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
     * Deletes every selected session atomically (via [CyclingSessionRepository.deleteSessions]).
     * On success, exits selection mode; the Home list updates on its own via the existing
     * Flow-backed query. On failure, leaves selection mode active with the same items selected
     * (so the user can retry) and surfaces [deleteError]. No recluster here, per #187/#192 — that
     * stays on the lazy pull-to-refresh path.
     *
     * Also invalidates the saved Dropbox sync cursor, same as Session Detail's single delete
     * (#193/a756bae) — without this, a bulk-deleted ride that was previously synced from Dropbox
     * would never be reconsidered for import again, since the delta cursor has no visibility into
     * local deletions.
     */
    fun deleteSelectedSessions() {
        val ids = _selectedSessionIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                sessionRepository.deleteSessions(ids)
                dropboxSyncCursorRepository.invalidateSyncCursor()
                exitSelectionMode()
            } catch (e: Exception) {
                Log.e(TAG, "Bulk delete failed", e)
                _deleteError.value = "Couldn't delete rides"
            }
        }
    }

    // --- Import ---

    private val importMutex = Mutex()
    private var smallFileDecisionChannel: Channel<Boolean>? = null

    fun confirmSmallFileImport() {
        smallFileDecisionChannel?.trySend(true)
    }

    fun skipSmallFile() {
        smallFileDecisionChannel?.trySend(false)
    }

    fun importFromUri(uri: Uri) {
        importFromUris(listOf(uri))
    }

    fun importFromUris(uris: List<Uri>) {
        viewModelScope.launch(Dispatchers.IO) {
            importMutex.withLock {
                val total = uris.size
                val results = mutableListOf<ImportResult>()
                val revealBaseline = rideRevealEvaluator.captureBaseline()

                for (index in uris.indices) {
                    val uri = uris[index]
                    val current = index + 1
                    val fileName = getFileName(uri) ?: "unknown.fit"
                    _importState.value = ImportUiState.BatchLoading(current, total)

                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        results.add(ImportResult.Error("Could not read file: $fileName"))
                        break
                    }

                    var result = fitImportService.importFile(fileName, bytes)

                    if (result is ImportResult.SmallFile) {
                        val channel = Channel<Boolean>(1)
                        smallFileDecisionChannel = channel
                        _importState.value = ImportUiState.SmallFileWarning(
                            fileName = result.fileName,
                            dataPointCount = result.dataPointCount,
                            current = current,
                            total = total
                        )
                        val shouldImport = channel.receive()
                        smallFileDecisionChannel = null
                        if (!shouldImport) continue
                        _importState.value = ImportUiState.BatchLoading(current, total)
                        result = fitImportService.importFile(fileName, bytes, forceImport = true)
                    }

                    results.add(result)
                }

                val reveal = rideRevealEvaluator.evaluate(results, revealBaseline)
                _importState.value = if (reveal != null) {
                    ImportUiState.RideReveal(reveal)
                } else {
                    ImportUiState.Done(results.lastOrNull() ?: ImportResult.Error("No files"))
                }
            }

            recluster()
        }
    }

    /**
     * Re-clusters routes and repeated intervals, on a scope that survives navigation away from
     * the home screen. Called once per import batch, and unconditionally on every pull-to-refresh
     * (#192) so cluster badges stay correct after a ride delete even without a new import.
     */
    private fun recluster() {
        appScope.launch {
            try {
                routeClusteringService.runClustering()
            } catch (e: Exception) {
                Log.e(TAG, "Route clustering failed", e)
            }
        }
        appScope.launch {
            try {
                intervalClusteringService.runClustering()
            } catch (e: Exception) {
                Log.e(TAG, "Interval clustering failed", e)
            }
        }
    }

    fun clearImportState() {
        _importState.value = ImportUiState.Idle
    }

    // --- Dropbox sync ---

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _dropboxSyncMessage = MutableStateFlow<String?>(null)
    val dropboxSyncMessage: StateFlow<String?> = _dropboxSyncMessage.asStateFlow()

    fun clearDropboxSyncMessage() {
        _dropboxSyncMessage.value = null
    }

    /**
     * Pull-to-refresh entry point: syncs new .fit files from the configured Dropbox folder.
     *
     * Reclusters unconditionally (#192) — regardless of Dropbox connection state or whether new
     * files were found — so this is also the general "resync cluster state" action for a user who
     * just deleted a ride and wants Repeated Routes/Intervals badges to reflect it. It's placed in
     * `finally` so it still runs on every early-return path (not connected, needs reauth) as well
     * as after a completed sync, without duplicating the call in each branch.
     */
    fun syncDropbox(isUserInitiated: Boolean = true) {
        if (_isSyncing.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                if (!dropboxAuthRepository.isConnected.value) {
                    if (isUserInitiated) {
                        _dropboxSyncMessage.value = "Connect Dropbox in Settings to sync rides"
                    }
                    return@launch
                }
                if (dropboxAuthRepository.needsReauth.value) {
                    return@launch
                }

                val revealBaseline = rideRevealEvaluator.captureBaseline()
                when (val result = dropboxSyncService.sync()) {
                    is DropboxSyncResult.Completed -> {
                        val reveal = rideRevealEvaluator.evaluate(result.importResults, revealBaseline)
                        if (reveal != null) {
                            _importState.value = ImportUiState.RideReveal(reveal)
                        } else if (isUserInitiated || result.importResults.isNotEmpty()) {
                            _dropboxSyncMessage.value = buildSyncMessage(result.importResults)
                        }
                    }
                    DropboxSyncResult.TransientFailure -> {
                        // Fail silently; will retry on the next sync trigger.
                    }
                    DropboxSyncResult.NeedsReauth -> {
                        dropboxAuthRepository.markNeedsReauth()
                    }
                }
            } finally {
                _isSyncing.value = false
                recluster()
            }
        }
    }

    /** Auto-sync entry point: silently syncs Dropbox on app open, if connected. */
    private fun autoSyncDropbox() {
        if (!dropboxAuthRepository.isConnected.value) return
        syncDropbox(isUserInitiated = false)
    }

    private fun buildSyncMessage(results: List<ImportResult>): String {
        val successCount = results.count { it is ImportResult.Success }
        val errors = results.filterIsInstance<ImportResult.Error>()
        val smallFileCount = results.count { it is ImportResult.SmallFile }

        val parts = mutableListOf<String>()
        if (successCount > 0) {
            parts.add("Imported $successCount new ride${if (successCount == 1) "" else "s"}")
        }
        if (errors.isNotEmpty()) {
            parts.add("${errors.size} failed: ${errors.first().message}")
        }
        if (smallFileCount > 0) {
            parts.add("$smallFileCount skipped (too short)")
        }

        return if (parts.isEmpty()) "No new rides found in Dropbox" else parts.joinToString(", ")
    }

    private fun getFileName(uri: Uri): String? {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        return cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) it.getString(nameIndex) else null
            } else null
        }
    }

    init {
        autoSyncDropbox()
    }
}
