package com.velometrics.app.ui.screens.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.dropbox.DropboxAuthRepository
import com.velometrics.app.data.dropbox.DropboxSyncService
import com.velometrics.app.data.fitimport.FitImportService
import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.model.CyclingSessionSummary
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.di.ApplicationScope
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
    private val routeClusteringService: RouteClusteringService,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
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
                var lastResult: ImportResult = ImportResult.Error("No files")

                for (index in uris.indices) {
                    val uri = uris[index]
                    val current = index + 1
                    val fileName = getFileName(uri) ?: "unknown.fit"
                    _importState.value = ImportUiState.BatchLoading(current, total)

                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    if (bytes == null) {
                        lastResult = ImportResult.Error("Could not read file: $fileName")
                        _importState.value = ImportUiState.Done(lastResult)
                        return@withLock
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

                    lastResult = result
                }

                _importState.value = ImportUiState.Done(lastResult)
            }

            // Re-cluster on a scope that survives navigation away from the home screen.
            appScope.launch { routeClusteringService.runClustering() }
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

    /** Pull-to-refresh entry point: syncs new .fit files from the configured Dropbox folder. */
    fun syncDropbox() {
        if (_isSyncing.value) return

        viewModelScope.launch(Dispatchers.IO) {
            _isSyncing.value = true
            try {
                if (!dropboxAuthRepository.isConnected.value) {
                    _dropboxSyncMessage.value = "Connect Dropbox in Settings to sync rides"
                    return@launch
                }

                val results = dropboxSyncService.sync()
                if (results.any { it is ImportResult.Success }) {
                    appScope.launch { routeClusteringService.runClustering() }
                }
                _dropboxSyncMessage.value = buildSyncMessage(results)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /** Auto-sync entry point: silently syncs Dropbox on app open, if connected. */
    private fun autoSyncDropbox() {
        if (!dropboxAuthRepository.isConnected.value) return
        syncDropbox()
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
