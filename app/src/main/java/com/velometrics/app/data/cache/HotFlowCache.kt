package com.velometrics.app.data.cache

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Hoists a cold [Flow] into a singleton-scoped hot [StateFlow] with an [isLoading] flag that
 * flips to false on the first emission and stays false. [RepeatedRoutesCacheImpl] and
 * [RepeatedIntervalsCacheImpl] were byte-identical modulo type before being collapsed onto this
 * shared building block.
 *
 * [source] is debounced by [CACHE_DEBOUNCE_MS]: clustering (see `RouteClusteringService`/
 * `IntervalClusteringService`) deletes and reinserts one row per cluster on every run, so without
 * debouncing, a single clustering pass would re-emit [value] once per intermediate write instead
 * of once after the run settles — the same class of storm [CACHE_DEBOUNCE_MS]'s doc describes for
 * bulk import.
 */
class HotFlowCache<T>(
    source: Flow<T>,
    scope: CoroutineScope,
    initialValue: T
) {
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val value: StateFlow<T> = source
        .debounced()
        .onEach { _isLoading.value = false }
        .stateIn(scope, SharingStarted.Eagerly, initialValue)
}
