package com.cyclegraph.app.ui.screens.dashboard

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyclegraph.app.data.fitimport.FitImportService
import com.cyclegraph.app.data.fitimport.ImportResult
import com.cyclegraph.app.domain.model.CyclingSession
import com.cyclegraph.app.domain.repository.CyclingSessionRepository
import com.cyclegraph.app.di.ApplicationScope
import com.cyclegraph.app.domain.service.RouteClusteringService
import com.cyclegraph.app.domain.service.SessionComparator
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
import kotlinx.coroutines.flow.first
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
    private val sessionComparator: SessionComparator,
    private val routeClusteringService: RouteClusteringService,
    @ApplicationScope private val appScope: CoroutineScope,
    @ApplicationContext private val context: Context
) : ViewModel() {

    // Tracks whether the first real DB emission has arrived.
    // Prevents the empty-state placeholder from flashing on startup.
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    val sessions: StateFlow<List<CyclingSession>> = sessionRepository.getAllSessions()
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

                recalculateAllStats()
                _importState.value = ImportUiState.Done(lastResult)
            }

            // Re-cluster on a scope that survives navigation away from the dashboard.
            appScope.launch { routeClusteringService.runClustering() }
        }
    }

    private suspend fun recalculateAllStats() {
        val allSessions = sessionRepository.getAllSessions().first()
        for (session in allSessions) {
            sessionComparator.computeComparison(session)
        }
    }

    fun clearImportState() {
        _importState.value = ImportUiState.Idle
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
}
