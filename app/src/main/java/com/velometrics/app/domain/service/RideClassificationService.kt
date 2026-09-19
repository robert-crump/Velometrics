package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
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
/**
 * One session's persisted tag next to a freshly-recomputed one, for reviewing/tuning
 * [RideClassifier] thresholds (#170). [isStale] is the single definition of "persisted tag no
 * longer matches what the classifier would produce".
 */
data class TagReviewRow(
    val session: CyclingSession,
    val storedTag: String?,
    val computedTag: String?
) {
    val isStale: Boolean get() = storedTag != computedTag
}

@Singleton
class RideClassificationService @Inject constructor(
    private val sessionRepository: CyclingSessionRepository
) {
    suspend fun reclassifyAll(ftp: Int) {
        for (row in reviewRows(ftp)) {
            if (row.isStale) {
                sessionRepository.updateTag(row.session.id, row.computedTag)
            }
        }
    }

    suspend fun reviewRows(ftp: Int): List<TagReviewRow> =
        sessionRepository.getAllSessions().first().map { session ->
            TagReviewRow(session, session.tag, RideClassifier.classify(session, ftp)?.label)
        }
}
