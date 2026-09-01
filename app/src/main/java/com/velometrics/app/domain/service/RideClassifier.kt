package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RideTag
import com.velometrics.app.util.CyclingConstants

/**
 * Rule-based ride tagging (#169, thresholds replaced in #170's validation pass, Recovery redefined
 * and priority order flipped after that): assigns each session at most one [RideTag], evaluated in
 * priority order (Recovery -> Zone 2 -> Intervals) over data already computed for the session plus
 * the rider's current FTP — no new signal collection.
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

    /** Ride duration must fall short of this to be eligible for Recovery. */
    private const val RECOVERY_MAX_DURATION_SEC = 75 * 60

    /** Average power, as a fraction of FTP, must fall within this range (inclusive) to be eligible for Recovery. */
    private const val RECOVERY_MIN_POWER_FTP_FRACTION = 0.50
    private const val RECOVERY_MAX_POWER_FTP_FRACTION = CyclingConstants.RECOVERY_TIME_BELOW_FTP_FRACTION

    fun classify(session: CyclingSession, ftp: Int): RideTag? = when {
        isRecovery(session, ftp) -> RideTag.RECOVERY
        isZone2(session) -> RideTag.ZONE_2
        isIntervals(session) -> RideTag.INTERVALS
        else -> null
    }

    private fun isIntervals(session: CyclingSession): Boolean =
        session.intervalCount >= INTERVALS_MIN_COUNT

    private fun isZone2(session: CyclingSession): Boolean {
        val fatScore = session.fatEfficiencyScore ?: return false
        return fatScore >= ZONE_2_MIN_FAT_EFFICIENCY_SCORE
    }

    private fun isRecovery(session: CyclingSession, ftp: Int): Boolean {
        val avgPower = session.averagePower ?: return false
        if (session.netDurationSec >= RECOVERY_MAX_DURATION_SEC) return false

        val minPower = ftp * RECOVERY_MIN_POWER_FTP_FRACTION
        val maxPower = ftp * RECOVERY_MAX_POWER_FTP_FRACTION
        return avgPower >= minPower && avgPower <= maxPower
    }
}
