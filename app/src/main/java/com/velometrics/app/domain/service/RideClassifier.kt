package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag

/**
 * Rule-based ride tagging (#169): assigns each session exactly one [RideTag], evaluated in
 * priority order (Intervals -> Race -> Zone 2 -> Recovery, falling back to Endurance) over
 * data already computed for the session — no new signal collection.
 *
 * Thresholds below are a best-effort starting point, not validated against real ride history
 * (explicitly out of scope for #169 — see the follow-up threshold-tuning issue, which is why
 * [com.velometrics.app.domain.service.RideClassificationService.reclassifyAll] exists: to
 * re-run this same rule set once those thresholds change).
 */
object RideClassifier {

    /** Fraction of net ride time spent in a detected high-intensity interval to call it an Intervals ride. */
    private const val INTERVAL_TIME_SHARE_THRESHOLD = 0.12

    /** Fraction of zone-time in [HIGH_INTENSITY_ZONES] to consider the ride race-paced. */
    private const val RACE_HIGH_ZONE_SHARE_THRESHOLD = 0.50

    /** Max NP:AP ("variability index") for a Race effort to read as steady rather than spiky. */
    private const val RACE_MAX_VARIABILITY_INDEX = 1.05

    /** Fraction of zone-time in Zone 2 to call the ride an aerobic Zone 2 session. */
    private const val ZONE_2_SHARE_THRESHOLD = 0.50

    /** Fraction of zone-time in Zone 1 to call the ride a Recovery spin. */
    private const val RECOVERY_ZONE_1_SHARE_THRESHOLD = 0.50

    /** Cardiac-drift/fat-efficiency fallback for a Recovery ride when zone data doesn't already say so. */
    private const val RECOVERY_MAX_CARDIAC_DRIFT_PERCENT = 3.0
    private const val RECOVERY_MIN_FAT_EFFICIENCY_SCORE = 70

    private val HIGH_INTENSITY_ZONES = setOf("Zone 4", "Zone 5", "Zone 6")

    fun classify(session: CyclingSession): RideTag = when {
        isIntervals(session) -> RideTag.INTERVALS
        isRace(session) -> RideTag.RACE
        isZone2(session) -> RideTag.ZONE_2
        isRecovery(session) -> RideTag.RECOVERY
        else -> RideTag.ENDURANCE
    }

    private fun isIntervals(session: CyclingSession): Boolean {
        if (session.netDurationSec <= 0) return false
        val share = session.intervalTotalTimeSec.toDouble() / session.netDurationSec
        return share >= INTERVAL_TIME_SHARE_THRESHOLD
    }

    private fun isRace(session: CyclingSession): Boolean {
        val zoneShare = zoneTimeShare(session, HIGH_INTENSITY_ZONES) ?: return false
        if (zoneShare < RACE_HIGH_ZONE_SHARE_THRESHOLD) return false

        // NP:AP is a power-only signal. On an HR-only ride (no power meter) it's simply
        // unavailable, so the zone-time majority alone is what's left to go on.
        val variabilityIndex = variabilityIndex(session) ?: return true
        return variabilityIndex <= RACE_MAX_VARIABILITY_INDEX
    }

    private fun isZone2(session: CyclingSession): Boolean {
        val zoneShare = zoneTimeShare(session, setOf("Zone 2")) ?: return false
        return zoneShare >= ZONE_2_SHARE_THRESHOLD
    }

    private fun isRecovery(session: CyclingSession): Boolean {
        val zone1Share = zoneTimeShare(session, setOf("Zone 1"))
        if (zone1Share != null && zone1Share >= RECOVERY_ZONE_1_SHARE_THRESHOLD) return true

        val drift = session.cardiacDriftPercent
        val fatScore = session.fatEfficiencyScore
        return drift != null && fatScore != null &&
            drift <= RECOVERY_MAX_CARDIAC_DRIFT_PERCENT && fatScore >= RECOVERY_MIN_FAT_EFFICIENCY_SCORE
    }

    /**
     * Share of recorded time falling in [zoneLabels], preferring power zones (a more direct
     * effort signal) over heart-rate zones when both exist; `null` if neither distribution is
     * available at all.
     */
    private fun zoneTimeShare(session: CyclingSession, zoneLabels: Set<String>): Double? {
        val distribution = session.powerZoneDistribution ?: session.hrZoneDistribution ?: return null
        val total = distribution.values.sum()
        if (total <= 0) return null
        val matched = zoneLabels.sumOf { distribution[it] ?: 0 }
        return matched.toDouble() / total
    }

    private fun variabilityIndex(session: CyclingSession): Double? {
        val np = session.normalizedPower ?: return null
        val ap = session.averagePower ?: return null
        if (ap <= 0) return null
        return np.toDouble() / ap
    }
}
