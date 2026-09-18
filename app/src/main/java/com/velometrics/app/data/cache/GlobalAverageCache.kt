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
 * Singleton-scoped hot view of the equal-weight, all-rides average power/HR zone and speed
 * distributions, same pattern as [RepeatedRoutesCache], so every ride and route detail screen
 * shares one computation instead of recomputing per screen, and so the average stays current
 * automatically whenever [CyclingSessionRepository.getAllSessions] emits (import, delete) without
 * any manual invalidation wiring. ViewModels depend on this interface (see
 * [GlobalAverageCacheImpl]) so a test can supply a fake instead of standing up Hilt or a real
 * [CoroutineScope].
 *
 * The source session Flow is debounced — see [CACHE_DEBOUNCE_MS]'s doc for why.
 */
interface GlobalAverageCache {
    val powerZoneAverages: StateFlow<Map<String, Float>>
    val hrZoneAverages: StateFlow<Map<String, Float>>
    val speedHistogramAverages: StateFlow<Map<String, Float>>
}

@Singleton
class GlobalAverageCacheImpl @Inject constructor(
    repository: CyclingSessionRepository,
    @ApplicationScope scope: CoroutineScope
) : GlobalAverageCache {
    private val sessions = repository
        .getAllSessions()
        .debounced()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    override val powerZoneAverages: StateFlow<Map<String, Float>> = sessions
        .map { GlobalAverageCalculator.computePowerZoneAverages(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val hrZoneAverages: StateFlow<Map<String, Float>> = sessions
        .map { GlobalAverageCalculator.computeHrZoneAverages(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    override val speedHistogramAverages: StateFlow<Map<String, Float>> = sessions
        .map { GlobalAverageCalculator.computeSpeedHistogramAverages(it) }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
}
