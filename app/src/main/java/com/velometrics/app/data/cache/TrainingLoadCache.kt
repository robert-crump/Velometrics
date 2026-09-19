package com.velometrics.app.data.cache

import com.velometrics.app.data.preferences.FtpHistoryRepository
import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.ui.screens.trainingload.TrainingLoadUiState
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.TrainingLoadAggregator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Singleton-scoped hot view of TrainingLoadViewModel's CTL/ATL/TSB series (#190), same pattern as
 * [AllTimeStatsCache] — recomputed from scratch on every emission of either source Flow, which is
 * cheap enough for a daily-bucketed EMA over a rider's full history (see [TrainingLoadAggregator])
 * that no persisted/incremental storage is warranted. ViewModels depend on this interface (see
 * [TrainingLoadCacheImpl]) so a test can supply a fake instead of standing up Hilt or a real
 * [CoroutineScope].
 *
 * Recomputing on [FtpHistoryRepository.history] as well as the session list means adding or
 * editing an FTP entry reshapes Training Load from that entry's effective date onward (each ride
 * is scored against its ride-date FTP, ADR 0001); rides before it are unaffected.
 *
 * The session Flow is debounced — see [CACHE_DEBOUNCE_MS]'s doc for why.
 */
interface TrainingLoadCache {
    val uiState: StateFlow<TrainingLoadUiState>
}

@Singleton
class TrainingLoadCacheImpl @Inject constructor(
    sessionRepository: CyclingSessionRepository,
    ftpHistoryRepository: FtpHistoryRepository,
    @ApplicationScope scope: CoroutineScope
) : TrainingLoadCache {
    override val uiState: StateFlow<TrainingLoadUiState> = combine(
        sessionRepository.getAllSessions().debounced(),
        ftpHistoryRepository.history
    ) { sessions, ftpHistory ->
        TrainingLoadAggregator.buildUiState(sessions, ftpHistory)
    }.stateIn(scope, SharingStarted.Eagerly, TrainingLoadUiState())
}
