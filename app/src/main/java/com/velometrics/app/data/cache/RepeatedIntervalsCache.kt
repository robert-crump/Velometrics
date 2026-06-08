package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hoists [RepeatedIntervalRepository.getAllRepeatedIntervals] into a singleton-scoped hot
 * [StateFlow], mirroring [RepeatedRoutesCache] so switching to the Intervals sub-tab does not
 * restart collection (and edge-geometry resolution) each time the screen's ViewModel is recreated.
 *
 * [isLoading] reflects "have we ever received a DB emission?" — it flips to false on the
 * first emission and stays false. Tab switches after that show the cached data instantly.
 */
@Singleton
class RepeatedIntervalsCache @Inject constructor(
    repository: RepeatedIntervalRepository,
    @ApplicationScope scope: CoroutineScope
) {
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val repeatedIntervals: StateFlow<List<RepeatedInterval>> = repository
        .getAllRepeatedIntervals()
        .onEach { _isLoading.value = false }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())
}
