package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.AllTimeStatsUiState
import com.velometrics.app.domain.repository.BestEffortRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.AllTimeStatsAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton-scoped hot view of AllTimeStatsViewModel's aggregates (records, distance splits,
 * power curve, per-year totals, power/speed cloud), same pattern as [GlobalAverageCache], so
 * re-opening the All-time Stats screen shows cached data instantly instead of recomputing over
 * every session, and the data stays current automatically on import/delete with no manual
 * invalidation wiring. ViewModels depend on this interface (see [AllTimeStatsCacheImpl]) so a
 * test can supply a fake instead of standing up Hilt or a real [CoroutineScope].
 *
 * [BestEffortRepository.getAllWithSessionDate] joins against `cycling_sessions`, so its Flow also
 * re-emits on a plain session insert/delete (not just a `session_best_efforts` write) — this
 * matters because [com.velometrics.app.data.fitimport.FitImportService] inserts the session
 * before the best-effort row, so [CyclingSessionRepository]'s own emission can briefly race ahead;
 * the best-effort Flow's own follow-up emission (once its row lands) is what settles [uiState] on
 * consistent data, without needing a single combined transaction.
 *
 * Both source Flows are debounced — see [CACHE_DEBOUNCE_MS]'s doc for why.
 */
interface AllTimeStatsCache {
    val uiState: StateFlow<AllTimeStatsUiState>
}

@Singleton
class AllTimeStatsCacheImpl @Inject constructor(
    sessionRepository: CyclingSessionRepository,
    bestEffortRepository: BestEffortRepository,
    @ApplicationScope scope: CoroutineScope
) : AllTimeStatsCache {
    override val uiState: StateFlow<AllTimeStatsUiState> = combine(
        sessionRepository.getAllSessions().debounced(),
        bestEffortRepository.getAllWithSessionDate().debounced()
    ) { sessions, bestEfforts ->
        AllTimeStatsAggregator.buildUiState(sessions, bestEfforts)
    }.stateIn(scope, SharingStarted.Eagerly, AllTimeStatsUiState())
}
