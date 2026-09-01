package com.velometrics.app.domain.model

/**
 * Output of [com.velometrics.app.domain.service.BestEffortCalculator] for a single ride — no
 * session id attached yet, since it's computed before the session row is inserted.
 */
data class BestEffortValues(
    val split25kSec: Double? = null,
    val split50kSec: Double? = null,
    val split100kSec: Double? = null,
    val power1s: Int? = null,
    val power3s: Int? = null,
    val power5s: Int? = null,
    val power20s: Int? = null,
    val power30s: Int? = null,
    val power1m: Int? = null,
    val power5m: Int? = null,
    val power20m: Int? = null,
    val power30m: Int? = null
) {
    val hasAnyData: Boolean
        get() = listOfNotNull(
            split25kSec, split50kSec, split100kSec,
            power1s, power3s, power5s, power20s, power30s, power1m, power5m, power20m, power30m
        ).isNotEmpty()

    /**
     * This ride's own best-effort curve as [PowerCurvePoint]s, same 9 durations as the all-time
     * power curve on All-time Stats — for the per-ride "Power curve" card on Session Detail
     * (#173). [PowerCurvePoint.sessionId]/[PowerCurvePoint.date] are left null: there's nothing
     * to navigate to or date-label from a single ride's own chart.
     */
    fun toPowerCurvePoints(): List<PowerCurvePoint> = listOf(
        PowerCurvePoint(1, "1s", power1s, sessionId = null, date = null),
        PowerCurvePoint(3, "3s", power3s, sessionId = null, date = null),
        PowerCurvePoint(5, "5s", power5s, sessionId = null, date = null),
        PowerCurvePoint(20, "20s", power20s, sessionId = null, date = null),
        PowerCurvePoint(30, "30s", power30s, sessionId = null, date = null),
        PowerCurvePoint(60, "1min", power1m, sessionId = null, date = null),
        PowerCurvePoint(300, "5min", power5m, sessionId = null, date = null),
        PowerCurvePoint(1200, "20min", power20m, sessionId = null, date = null),
        PowerCurvePoint(1800, "30min", power30m, sessionId = null, date = null)
    )
}

/** All-time best-effort row joined with its originating session's start time, for the All-time Stats screen. */
data class BestEffortRecord(
    val sessionId: Long,
    val sessionStart: Long,
    val split25kSec: Double?,
    val split50kSec: Double?,
    val split100kSec: Double?,
    val power1s: Int?,
    val power3s: Int?,
    val power5s: Int?,
    val power20s: Int?,
    val power30s: Int?,
    val power1m: Int?,
    val power5m: Int?,
    val power20m: Int?,
    val power30m: Int?
)
