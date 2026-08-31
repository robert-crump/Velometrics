package com.velometrics.app.domain.service

import com.velometrics.app.domain.repository.CyclingSessionRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Backfill for [RideClassifier] (#169): recomputes and persists a tag for every existing
 * session, so rides imported before rule-based tagging shipped still get one. Wired into
 * [com.velometrics.app.ui.screens.settings.SettingsViewModel.recalculateAllStats] as the
 * existing "recompute everything" trigger — the same routine the follow-up threshold-tuning
 * issue re-runs once its thresholds change.
 *
 * Safe to re-run: each session's tag is a pure function of its already-persisted data, so
 * repeated runs are idempotent and only write rows whose tag actually changed.
 */
@Singleton
class RideClassificationService @Inject constructor(
    private val sessionRepository: CyclingSessionRepository
) {
    suspend fun reclassifyAll() {
        val sessions = sessionRepository.getAllSessions().first()
        for (session in sessions) {
            val tag = RideClassifier.classify(session)?.label
            if (session.tag != tag) {
                sessionRepository.updateTag(session.id, tag)
            }
        }
    }
}
