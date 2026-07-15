package com.velometrics.app.ui.screens.mapview

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.service.FastWayHomeResult
import com.velometrics.app.domain.service.FastWayHomeService
import com.velometrics.app.domain.service.GpxExporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class FastWayHomeViewModel @Inject constructor(
    private val fastWayHomeService: FastWayHomeService,
    private val userSettingsRepository: UserSettingsRepository,
    private val gpxExporter: GpxExporter,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _fastWayHomeResult = MutableStateFlow<FastWayHomeResult?>(null)
    val fastWayHomeResult: StateFlow<FastWayHomeResult?> = _fastWayHomeResult.asStateFlow()

    private val _isFindingFastWayHome = MutableStateFlow(false)
    val isFindingFastWayHome: StateFlow<Boolean> = _isFindingFastWayHome.asStateFlow()

    private val _fastWayHomeMessage = MutableStateFlow<String?>(null)
    val fastWayHomeMessage: StateFlow<String?> = _fastWayHomeMessage.asStateFlow()

    private val _shareIntent = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val shareIntent: SharedFlow<Intent> = _shareIntent.asSharedFlow()

    val homeLocation: StateFlow<LatLng?> = combine(
        userSettingsRepository.homeLat, userSettingsRepository.homeLon
    ) { lat, lon -> LatLng(lat, lon) }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun findFastWayHome(currentLocation: StateFlow<LatLng?>, locationAccuracy: StateFlow<Float?>) {
        viewModelScope.launch {
            _isFindingFastWayHome.value = true
            _fastWayHomeMessage.value = null
            _fastWayHomeResult.value = null
            try {
                if (currentLocation.value == null) {
                    _fastWayHomeMessage.value = "Waiting for GPS signal…"
                    return@launch
                }
                val result = fastWayHomeService.findFastWayHome(currentLocation, locationAccuracy)
                if (result == null) {
                    _fastWayHomeMessage.value = "No known route home from here"
                } else {
                    _fastWayHomeResult.value = result
                }
            } finally {
                _isFindingFastWayHome.value = false
            }
        }
    }

    fun clearFastWayHome() {
        _fastWayHomeResult.value = null
        _fastWayHomeMessage.value = null
    }

    fun exportGpx() {
        val result = _fastWayHomeResult.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val date = LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
                val routeName = "${date}_home"

                val file = File(context.cacheDir, "$routeName.gpx")
                file.outputStream().use { gpxExporter.export(result.path, routeName, it) }

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
                    _fastWayHomeMessage.value = "Export failed: ${e.message}"
                }
            }
        }
    }
}
