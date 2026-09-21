package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.SessionNarrativeAssembler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-computes the Session Detail recaps for the most recent rides at app start so opening one
 * right after launch shows its tag / Repeated Route recap on the first frame (see [SessionRecapCache]).
 */
@Singleton
class RecapWarmer @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val assembler: SessionNarrativeAssembler,
    private val repeatedRoutesCache: RepeatedRoutesCache,
    @ApplicationScope private val scope: CoroutineScope
) {
    fun start() {
        // Re-runs when a ride is imported/re-tagged (recent list changes) so new top-of-list rides warm too.
        scope.launch {
            sessionRepository.getRecentSessions(WARM_COUNT).collectLatest { sessions ->
                try {
                    sessions.forEach { assembler.warmNarrative(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Warm-up is best-effort; Session Detail computes its own recap regardless.
                }
            }
        }
        scope.launch {
            combine(
                sessionRepository.getRecentSessions(WARM_COUNT),
                repeatedRoutesCache.routes,
                repeatedRoutesCache.isLoading
            ) { sessions, routes, loading -> Triple(sessions, routes, loading) }
                .filter { (_, _, loading) -> !loading }
                .collect { (sessions, routes, _) ->
                    assembler.warmRouteRecaps(sessions.map { it.id }, routes)
                }
        }
    }

    private companion object {
        const val WARM_COUNT = 10
    }
}
