package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.FtpHistory
import com.velometrics.app.domain.repository.CyclingSessionRepository
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Backfill for [RideClassifier] (#169): recomputes and persists a tag for every existing
 * session, so rides imported before rule-based tagging shipped still get one. Triggered only
 * from the debug-only "Apply re-tag" Settings row (paired with the tag dump), for threshold
 * tuning; tags are otherwise frozen at import (ADR 0001).
 *
 * Safe to re-run: each session's tag is a pure function of its already-persisted data, so
 * repeated runs are idempotent and only write rows whose tag actually changed.
 */
/**
 * One session's persisted tag next to a freshly-recomputed one, for reviewing/tuning
 * [RideClassifier] thresholds (#170). [isStale] is the single definition of "persisted tag no
 * longer matches what the classifier would produce". [ftp] is the ride-date FTP the computed tag
 * was judged against (ADR 0001).
 */
data class TagReviewRow(
    val session: CyclingSession,
    val storedTag: String?,
    val computedTag: String?,
    val ftp: Int
) {
    val isStale: Boolean get() = storedTag != computedTag
}

@Singleton
class RideClassificationService @Inject constructor(
    private val sessionRepository: CyclingSessionRepository
) {
    suspend fun reclassifyAll(ftpHistory: FtpHistory) {
        for (row in reviewRows(ftpHistory)) {
            if (row.isStale) {
                sessionRepository.updateTag(row.session.id, row.computedTag)
            }
        }
    }

    suspend fun reviewRows(ftpHistory: FtpHistory): List<TagReviewRow> =
        sessionRepository.getAllSessions().first().map { session ->
            val ftp = ftpHistory.ftpOn(session.sessionStart.atZone(ZoneId.systemDefault()).toLocalDate())
            TagReviewRow(session, session.tag, RideClassifier.classify(session, ftp)?.label, ftp)
        }
}
