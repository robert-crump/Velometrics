package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton-scoped hot view of [RepeatedRouteRepository.getAllRoutes] so that switching to the
 * Routes tab does not restart collection (and re-parse representative GPS tracks) each time the
 * screen's ViewModel is recreated. ViewModels depend on this interface (see
 * [RepeatedRoutesCacheImpl]) so a test can supply a fake instead of standing up Hilt or a real
 * [CoroutineScope].
 *
 * [isLoading] reflects "have we ever received a DB emission?" — it flips to false on the
 * first emission and stays false. Tab switches after that show the cached data instantly.
 */
interface RepeatedRoutesCache {
    val isLoading: StateFlow<Boolean>
    val routes: StateFlow<List<RepeatedRoute>>
}

@Singleton
class RepeatedRoutesCacheImpl @Inject constructor(
    repository: RepeatedRouteRepository,
    @ApplicationScope scope: CoroutineScope
) : RepeatedRoutesCache {
    private val cache = HotFlowCache(repository.getAllRoutes(), scope, emptyList<RepeatedRoute>())

    override val isLoading: StateFlow<Boolean> = cache.isLoading
    override val routes: StateFlow<List<RepeatedRoute>> = cache.value
}
