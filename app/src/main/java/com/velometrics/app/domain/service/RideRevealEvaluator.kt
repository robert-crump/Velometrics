package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideRevealCandidate
import com.velometrics.app.domain.model.RideRevealContent
import com.velometrics.app.domain.model.RideRevealPriority
import com.velometrics.app.domain.repository.CyclingSessionRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects whether a completed import batch produced a genuinely new ride (see [evaluate]) and,
 * if so, resolves the Ride Reveal hero content for it via [RideRevealResolver]. Tier 1
 * achievement candidates come from [RideMilestoneEvaluator] (ride-level milestones) and, once
 * #160 lands, a power-curve best-effort source; the guaranteed Tier 2 plain-stats fallback is
 * always registered too, so the resolver never runs on an empty list.
 */
@Singleton
class RideRevealEvaluator @Inject constructor(
    private val sessionRepository: CyclingSessionRepository,
    private val milestoneEvaluator: RideMilestoneEvaluator
) {

    /**
     * Call once per import batch, before the first file in that batch is imported, to capture
     * the baseline the batch's newest ride will later be compared against in [evaluate]. Null
     * baseline means the database was empty before this sync began (fresh install, or a
     * first-time backfill) — [evaluate] never fires the reveal in that case.
     */
    suspend fun captureBaseline(): Instant? = sessionRepository.getMaxSessionStart()

    /**
     * Call once after an import batch completes, with [baseline] as returned by [captureBaseline]
     * before that same batch started. Identifies the chronologically newest ride actually
     * imported in this batch (by parsed FIT start-time, not file name or import order) and, if
     * its start-time is later than [baseline], resolves and returns the reveal content for it.
     * Returns null if the reveal shouldn't fire: nothing imported, or nothing in the batch is
     * chronologically newer than what already existed in the database before this sync began.
     */
    suspend fun evaluate(results: List<ImportResult>, baseline: Instant?): RideRevealContent? {
        if (baseline == null) return null

        val newest = results.filterIsInstance<ImportResult.Success>()
            .maxByOrNull { it.sessionStart }
            ?: return null
        if (!newest.sessionStart.isAfter(baseline)) return null

        val session = sessionRepository.getSessionById(newest.sessionId) ?: return null

        val candidates = milestoneEvaluator.candidates(session) + fallbackCandidate(session)
        val winner = RideRevealResolver.resolve(candidates)

        return RideRevealContent(
            sessionId = session.id,
            headline = winner.headline,
            distanceKm = session.distanceKm,
            netDurationSec = session.netDurationSec,
            elevationGainM = session.elevationGainM
        )
    }

    private fun fallbackCandidate(session: CyclingSession): RideRevealCandidate =
        RideRevealCandidate(
            headline = "Nice ride!",
            priority = RideRevealPriority.FALLBACK
        )
}
