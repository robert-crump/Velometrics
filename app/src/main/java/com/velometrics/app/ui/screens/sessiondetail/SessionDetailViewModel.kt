package com.velometrics.app.ui.screens.sessiondetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.velometrics.app.data.cache.GlobalAverageCache
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.PowerCurvePoint
import com.velometrics.app.domain.repository.BestEffortRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.service.SessionComparison
import com.velometrics.app.domain.service.SessionComparator
import com.velometrics.app.domain.service.TagComparisonNarrative
import com.velometrics.app.util.CyclingConstants
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: CyclingSessionRepository,
    private val intervalRepository: IntervalRepository,
    private val bestEffortRepository: BestEffortRepository,
    private val sessionComparator: SessionComparator,
    globalAverageCache: GlobalAverageCache
) : ViewModel() {

    val powerZoneAverages: StateFlow<Map<String, Float>> = globalAverageCache.powerZoneAverages
    val hrZoneAverages: StateFlow<Map<String, Float>> = globalAverageCache.hrZoneAverages
    val speedHistogramAverages: StateFlow<Map<String, Float>> = globalAverageCache.speedHistogramAverages

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: 0L

    private val _session = MutableStateFlow<CyclingSession?>(null)
    val session: StateFlow<CyclingSession?> = _session.asStateFlow()

    /** This ride's own speed distribution as a percentage (0–100) per bin. */
    val speedHistogram: StateFlow<Map<String, Float>> = _session
        .map { s ->
            val hist = s?.speedHistogram ?: return@map emptyMap()
            val total = hist.values.sum().toFloat().coerceAtLeast(1f)
            CyclingConstants.SPEED_HISTOGRAM_BINS.associate { (label, _) ->
                label to (hist[label] ?: 0).toFloat() / total * 100f
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _comparison = MutableStateFlow<SessionComparison?>(null)
    val comparison: StateFlow<SessionComparison?> = _comparison.asStateFlow()

    /** Tag-scoped comparison narrative (#171), or null if this ride has no tag. */
    private val _tagNarrative = MutableStateFlow<String?>(null)
    val tagNarrative: StateFlow<String?> = _tagNarrative.asStateFlow()

    /** This ride's own best-effort power curve (#173), or empty if it has no power data at all. */
    private val _powerCurve = MutableStateFlow<List<PowerCurvePoint>>(emptyList())
    val powerCurve: StateFlow<List<PowerCurvePoint>> = _powerCurve.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val intervals: StateFlow<List<IntervalSession>> = intervalRepository.getIntervalsForSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            val loaded = sessionRepository.getSessionById(sessionId)
            _session.value = loaded
            _isLoading.value = false

            if (loaded != null) {
                _comparison.value = sessionComparator.computeComparison(loaded)
                _powerCurve.value = bestEffortRepository.getForSession(sessionId)?.toPowerCurvePoints().orEmpty()

                val tag = loaded.tag
                if (tag != null) {
                    val tagScopedComparison = sessionComparator.computeComparison(loaded, tag)
                    _tagNarrative.value = TagComparisonNarrative.generate(loaded, tag, tagScopedComparison)
                }
            }
        }
    }
}
