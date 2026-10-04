package com.velometrics.app.domain.model

/**
 * Speed IQ for one ride (#223, #226): where braking threw away speed. Computed at import from the
 * per-second stream, which isn't retained, so rides imported before #226 have none (no backfill).
 *
 * [hasElevation] false means the ride had no FIT altitude: without the gravity term turning onto a
 * climb would look like braking, so nothing is detected and the card says "No elevation data".
 */
data class SpeedIq(
    val hasElevation: Boolean,
    /** Sum of every event's [BrakingEvent.penaltySec], not just the top ones. */
    val brakingPenaltySec: Double,
    val eventCount: Int,
    /** P in penalty = E_brake / P: the median of the ride's pedalling samples. */
    val referencePowerW: Int,
    val systemMassKg: Double,
    /** At most [com.velometrics.app.util.CyclingConstants.SPEED_IQ_TOP_EVENTS], highest penalty first. */
    val topEvents: List<BrakingEvent>
)

data class BrakingEvent(
    /** Where the event's lowest speed happened, from the ride start. */
    val km: Double,
    val brakingEnergyJ: Double,
    val penaltySec: Double,
    val peakKmh: Double,
    val lowKmh: Double,
    val lat: Double,
    val lon: Double
)
