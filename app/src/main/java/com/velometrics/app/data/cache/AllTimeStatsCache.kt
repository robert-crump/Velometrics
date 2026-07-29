package com.velometrics.app.data.cache

import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.AllTimeStatsUiState
import com.velometrics.app.domain.repository.BestEffortRepository
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.AllTimeStatsAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hoists AllTimeStatsViewModel's aggregates (records, distance splits, power curve, per-year
 * totals, power/speed cloud) into a singleton-scoped hot [StateFlow] (same pattern as
 * [GlobalAverageCache]) so re-opening the All-time Stats screen shows cached data instantly
 * instead of recomputing over every session, and the data stays current automatically on
 * import/delete with no manual invalidation wiring.
 *
 * [BestEffortRepository.getAllWithSessionDate] joins against `cycling_sessions`, so its Flow also
 * re-emits on a plain session insert/delete (not just a `session_best_efforts` write) — this
 * matters because [com.velometrics.app.data.fitimport.FitImportService] inserts the session
 * before the best-effort row, so [sessionRepository]'s own emission can briefly race ahead; the
 * best-effort Flow's own follow-up emission (once its row lands) is what settles [uiState] on
 * consistent data, without needing a single combined transaction.
 *
 * Both source Flows are debounced: a bulk import inserts one session (and best-effort row) per
 * file, so without debouncing, a 150-file import would re-run [AllTimeStatsAggregator.buildUiState]
 * up to 150 times over a growing session list. Debouncing lets [uiState] settle once, shortly
 * after the last write in the batch, instead of on every intermediate write.
 */
@Singleton
class AllTimeStatsCache @Inject constructor(
    sessionRepository: CyclingSessionRepository,
    bestEffortRepository: BestEffortRepository,
    @ApplicationScope scope: CoroutineScope
) {
    @OptIn(FlowPreview::class)
    val uiState: StateFlow<AllTimeStatsUiState> = combine(
        sessionRepository.getAllSessions().debounce(300),
        bestEffortRepository.getAllWithSessionDate().debounce(300)
    ) { sessions, bestEfforts ->
        AllTimeStatsAggregator.buildUiState(sessions, bestEfforts)
    }.stateIn(scope, SharingStarted.Eagerly, AllTimeStatsUiState())
}
