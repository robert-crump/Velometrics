package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.GlobalAverageCalculator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hoists the equal-weight, all-rides average power/HR zone and speed distributions into
 * singleton-scoped hot [StateFlow]s (same pattern as [RepeatedRoutesCache]) so every ride and
 * route detail screen shares one computation instead of recomputing per screen, and so the
 * average stays current automatically whenever [CyclingSessionRepository.getAllSessions] emits
 * (import, delete) without any manual invalidation wiring.
 */
@Singleton
class GlobalAverageCache @Inject constructor(
    repository: CyclingSessionRepository,
    @ApplicationScope scope: CoroutineScope
) {
    private val sessions = repository
        .getAllSessions()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val powerZoneAverages: StateFlow<Map<String, Float>> = sessions
        .map { GlobalAverageCalculator.computePowerZoneAverages(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val hrZoneAverages: StateFlow<Map<String, Float>> = sessions
        .map { GlobalAverageCalculator.computeHrZoneAverages(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val speedHistogramAverages: StateFlow<Map<String, Float>> = sessions
        .map { GlobalAverageCalculator.computeSpeedHistogramAverages(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
}
