package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.IntervalSession
import kotlin.math.roundToInt

/**
 * A ride's "average power during intervals" (#215): each [IntervalSession.avgPower] weighted by its
 * [IntervalSession.durationSec], so longer intervals count proportionally more. Null when there are
 * no intervals (or their durations sum to zero). The pool-side equivalent is the `intervalAvgPower`
 * subquery in `CyclingSessionDao`, which must stay in step with this.
 */
fun List<IntervalSession>.durationWeightedAvgPower(): Int? {
    val totalSec = sumOf { it.durationSec.toLong() }
    if (totalSec <= 0) return null
    return (sumOf { it.avgPower.toLong() * it.durationSec } .toDouble() / totalSec).roundToInt()
}
