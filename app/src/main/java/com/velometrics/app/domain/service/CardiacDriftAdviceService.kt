package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.FtpHistory
import com.velometrics.app.domain.repository.CyclingSessionRepository
import com.velometrics.app.util.CyclingConstants
import java.time.Duration
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Evaluates [CardiacDriftAdvisor.causes] against the rider's history (#222): at import for new
 * rides, and as a backfill for rides imported before the causes existed. Causes are judged
 * against the FTP on the ride date and the rides before it (ADR 0001), so re-running only
 * changes a ride's result when its inputs changed (e.g. older rides imported later).
 */
@Singleton
class CardiacDriftAdviceService @Inject constructor(
    private val sessionRepository: CyclingSessionRepository
) {
    /** [session] with its drift causes and duration baseline filled in; [ftp] is the ride-date FTP. */
    suspend fun evaluate(session: CyclingSession, ftp: Int): CyclingSession {
        if (session.cardiacDriftPercent == null) return session
        val windowStart = session.sessionStart.minus(Duration.ofDays(CyclingConstants.CARDIAC_DRIFT_BASELINE_WINDOW_DAYS))
        val baseline = CardiacDriftAdvisor.durationBaselineSec(
            sessionRepository.getNetDurationsBetween(windowStart, session.sessionStart)
        )
        val causes = CardiacDriftAdvisor.causes(session, ftp, baseline)
        return session.copy(
            cardiacDriftCauses = causes,
            cardiacDriftDurationBaselineSec = baseline.takeIf { causes != null }
        )
    }

    /** Evaluates every advice-worthy ride that has never been evaluated; a cheap no-op once done. */
    suspend fun backfillMissing(ftpHistory: FtpHistory) {
        sessionRepository.getSessionsMissingCardiacDriftCauses(MEDIUM_MIN_PERCENT).forEach { persist(it, ftpHistory) }
    }

    /** Re-evaluates every ride with drift, writing only rows whose result changed. */
    suspend fun recomputeAll(ftpHistory: FtpHistory) {
        sessionRepository.getAllSessions().first()
            .filter { it.cardiacDriftPercent != null }
            .forEach { persist(it, ftpHistory) }
    }

    private suspend fun persist(session: CyclingSession, ftpHistory: FtpHistory) {
        val ftp = ftpHistory.ftpOn(session.sessionStart.atZone(ZoneId.systemDefault()).toLocalDate())
        val evaluated = evaluate(session, ftp)
        if (evaluated.cardiacDriftCauses != session.cardiacDriftCauses ||
            evaluated.cardiacDriftDurationBaselineSec != session.cardiacDriftDurationBaselineSec
        ) {
            sessionRepository.updateCardiacDriftAdvice(
                session.id, evaluated.cardiacDriftCauses, evaluated.cardiacDriftDurationBaselineSec
            )
        }
    }

    private companion object {
        // Lower edge of CardiacDriftBand.MEDIUM: only MEDIUM/HIGH rides get causes.
        const val MEDIUM_MIN_PERCENT = 5.0
    }
}
