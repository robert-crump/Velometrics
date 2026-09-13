package com.velometrics.app.data.cache

import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.di.ApplicationScope
import com.velometrics.app.domain.model.TrainingLoadUiState
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.domain.service.TrainingLoadAggregator
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
 * Hoists TrainingLoadViewModel's CTL/ATL/TSB series (#190) into a singleton-scoped hot
 * [StateFlow], same pattern as [AllTimeStatsCache] — recomputed from scratch on every emission
 * of either source Flow, which is cheap enough for a daily-bucketed EMA over a rider's full
 * history (see [TrainingLoadAggregator]) that no persisted/incremental storage is warranted.
 *
 * Recomputing on [UserSettingsRepository.ftp] as well as the session list means changing FTP in
 * Settings immediately reshapes the whole historical chart — the intended (if debatable)
 * behavior given FTP isn't historized anywhere in this app.
 *
 * The session Flow is debounced for the same reason as [AllTimeStatsCache]: a bulk import
 * inserts one session at a time, so without debouncing a large import would re-run
 * [TrainingLoadAggregator.buildUiState] once per file instead of once after the batch settles.
 */
@Singleton
class TrainingLoadCache @Inject constructor(
    sessionRepository: CyclingSessionRepository,
    userSettingsRepository: UserSettingsRepository,
    @ApplicationScope scope: CoroutineScope
) {
    @OptIn(FlowPreview::class)
    val uiState: StateFlow<TrainingLoadUiState> = combine(
        sessionRepository.getAllSessions().debounce(300),
        userSettingsRepository.ftp
    ) { sessions, ftp ->
        TrainingLoadAggregator.buildUiState(sessions, ftp)
    }.stateIn(scope, SharingStarted.Eagerly, TrainingLoadUiState())
}
