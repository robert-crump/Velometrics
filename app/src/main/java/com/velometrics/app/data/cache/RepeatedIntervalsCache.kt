package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton-scoped hot view of [RepeatedIntervalRepository.getAllRepeatedIntervals], mirroring
 * [RepeatedRoutesCache] so switching to the Intervals sub-tab does not restart collection (and
 * edge-geometry resolution) each time the screen's ViewModel is recreated. ViewModels depend on
 * this interface (see [RepeatedIntervalsCacheImpl]) so a test can supply a fake instead of
 * standing up Hilt or a real [CoroutineScope].
 *
 * [isLoading] reflects "have we ever received a DB emission?" — it flips to false on the
 * first emission and stays false. Tab switches after that show the cached data instantly.
 */
interface RepeatedIntervalsCache {
    val isLoading: StateFlow<Boolean>
    val repeatedIntervals: StateFlow<List<RepeatedInterval>>
}

@Singleton
class RepeatedIntervalsCacheImpl @Inject constructor(
    repository: RepeatedIntervalRepository,
    @ApplicationScope scope: CoroutineScope
) : RepeatedIntervalsCache {
    private val cache = HotFlowCache(repository.getAllRepeatedIntervals(), scope, emptyList<RepeatedInterval>())

    override val isLoading: StateFlow<Boolean> = cache.isLoading
    override val repeatedIntervals: StateFlow<List<RepeatedInterval>> = cache.value
}
