package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag

/**
 * Rule-based ride tagging (#169, thresholds replaced in #170's validation pass): assigns each
 * session at most one [RideTag], evaluated in priority order (Intervals -> Zone 2 -> Recovery)
 * over data already computed for the session — no new signal collection.
 *
 * No fallback/catch-all tag: a ride matching none of the three rules gets `null` rather than a
 * forced default (#170 dropped both Race and Endurance as categories after reviewing real ride
 * history turned up zero genuine Race rides and an Endurance bucket that was really just "didn't
 * match anything else").
 */
object RideClassifier {

    /** Detected-interval count (own data type, distinct from [CyclingSession.intervalTotalTimeSec]) to call it an Intervals ride. */
    private const val INTERVALS_MIN_COUNT = 2

    /** Fat-efficiency score floor to call the ride an aerobic Zone 2 session. */
    private const val ZONE_2_MIN_FAT_EFFICIENCY_SCORE = 75

    /** Fraction of zone-time in Zone 1 to call the ride a Recovery spin. */
    private const val RECOVERY_ZONE_1_SHARE_THRESHOLD = 0.50

    /** Cardiac-drift/fat-efficiency fallback for a Recovery ride when zone data doesn't already say so. */
    private const val RECOVERY_MAX_CARDIAC_DRIFT_PERCENT = 3.0
    private const val RECOVERY_MIN_FAT_EFFICIENCY_SCORE = 70

    fun classify(session: CyclingSession): RideTag? = when {
        isIntervals(session) -> RideTag.INTERVALS
        isZone2(session) -> RideTag.ZONE_2
        isRecovery(session) -> RideTag.RECOVERY
        else -> null
    }

    private fun isIntervals(session: CyclingSession): Boolean =
        session.intervalCount >= INTERVALS_MIN_COUNT

    private fun isZone2(session: CyclingSession): Boolean {
        val fatScore = session.fatEfficiencyScore ?: return false
        return fatScore >= ZONE_2_MIN_FAT_EFFICIENCY_SCORE
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
}
