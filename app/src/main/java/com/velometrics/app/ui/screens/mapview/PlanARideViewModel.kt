package com.velometrics.app.ui.screens.mapview

import android.app.Application
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.service.GeneratorConfig
import com.velometrics.app.domain.service.GpxExporter
import com.velometrics.app.domain.service.RankedCandidate
import com.velometrics.app.domain.service.RideDirection
import com.velometrics.app.domain.service.RouteGenerator
import com.velometrics.app.domain.service.RoutePlanResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

@HiltViewModel
class PlanARideViewModel @Inject constructor(
    application: Application,
    private val repository: MapGraphRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val gpxExporter: GpxExporter,
) : AndroidViewModel(application) {

    private val _candidate = MutableStateFlow<RankedCandidate?>(null)
    val candidate: StateFlow<RankedCandidate?> = _candidate.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _shareIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    private var generationJob: Job? = null

    fun planARide(targetDistanceKm: Double, direction: RideDirection? = null) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _message.value = null
            _candidate.value = null
            try {
                val homeLat = userSettingsRepository.homeLat.first()
                val homeLon = userSettingsRepository.homeLon.first()
                val targetDistanceM = targetDistanceKm * 1000.0

                val result = withContext(Dispatchers.Default) {
                    RouteGenerator.generate(
                        homeLat, homeLon, targetDistanceM, repository,
                        config = GeneratorConfig(direction = direction),
                    )
                }

                when (result) {
                    is RoutePlanResult.Success -> _candidate.value = result.candidate
                    is RoutePlanResult.Failure -> _message.value = result.reason
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Route generation failed: ${e.message}"
            } finally {
                _isGenerating.value = false
            }
        }
    }

    fun exportGpx() {
        val candidate = _candidate.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val distanceKm = (candidate.refinedRoute.actualDistanceM / 1000.0).roundToInt()
                val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
                val routeName = "${distanceKm}k_Velometrics_$date"

                val file = File(context.cacheDir, "$routeName.gpx")
                file.outputStream().use { gpxExporter.export(candidate.refinedRoute.edges, routeName, it) }

                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/gpx+xml"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, routeName)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                _shareIntent.emit(Intent.createChooser(intent, routeName))
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _message.value = "Export failed: ${e.message}"
                }
            }
        }
    }

    fun clearPlan() {
        generationJob?.cancel()
        generationJob = null
        _candidate.value = null
        _message.value = null
        _isGenerating.value = false
    }
}
