package com.velometrics.app.domain.model

/**
 * Speed IQ for one ride (#223, #226, #227): where braking and standing threw away time. Computed at
 * import from the per-second stream, which isn't retained, so rides imported before #226 have none
 * and rides imported before #227 have no standing time (no backfill).
 *
 * [hasElevation] false means the ride had no FIT altitude: without the gravity term turning onto a
 * climb would look like braking, so nothing is detected and the card says "No elevation data".
 */
data class SpeedIq(
    val hasElevation: Boolean,
    /** Sum of every event's [BrakingEvent.penaltySec], not just the top ones. */
    val brakingPenaltySec: Double,
    /** Sum of every event's [BrakingEvent.standingSec]; coffee stops are already left out. */
    val standingSec: Double,
    /** The part of [standingSec] with the timer running, i.e. inside the ride's net time. */
    val standingInTimerSec: Double,
    /** Braking events plus standing-only events. */
    val eventCount: Int,
    /**
     * P in penalty = E_brake / P: the median of the ride's pedalling samples, or for a ride without
     * power an estimate from earlier rides or FTP ([referencePowerEstimated], #229).
     */
    val referencePowerW: Int,
    /** Rider + bike + kit used for this ride: the setting at import, else the 85 kg default. */
    val systemMassKg: Double,
    /** False when [systemMassKg] is the assumed default (the setting was unset at import, or the ride predates #228). */
    val massFromSettings: Boolean = false,
    /** True for a ride without power, whose [referencePowerW] is estimated and pedal work counted as 0. */
    val referencePowerEstimated: Boolean = false,
    /** At most [com.velometrics.app.util.CyclingConstants.SPEED_IQ_TOP_EVENTS], the highest [BrakingEvent.lostSec] in ride order (by [BrakingEvent.km]). */
    val topEvents: List<BrakingEvent>
) {
    /**
     * Average speed without the penalties: distance / (net time − braking penalty − standing with the
     * timer running). Standing under auto-pause is already outside net time. Slightly optimistic,
     * since braking is counted in pedal-seconds. Null when there's nothing left to divide by.
     */
    fun potentialAvgKmh(distanceKm: Double, netDurationSec: Int): Double? {
        val sec = netDurationSec - brakingPenaltySec - standingInTimerSec
        return if (sec > 0) distanceKm / (sec / 3600.0) else null
    }
}

/**
 * One place speed was lost: braking (energy penalty), standing (clock seconds), or both. A
 * standing-only event has zero braking energy and both speeds 0.
 */
data class BrakingEvent(
    /** Where the event's lowest speed happened (where the stop began, if standing-only), from the ride start. */
    val km: Double,
    val brakingEnergyJ: Double,
    val penaltySec: Double,
    val peakKmh: Double,
    val lowKmh: Double,
    val lat: Double,
    val lon: Double,
    /** Below [com.velometrics.app.util.CyclingConstants.SPEED_IQ_STANDING_KMH] or timer paused; 0 for rows stored before #227. */
    val standingSec: Double = 0.0
) {
    val lostSec: Double get() = penaltySec + standingSec
}
