package com.velometrics.app.domain.service

import com.velometrics.app.util.CyclingConstants

/**
 * The resistance model Speed IQ shares between braking energy (#226) and expected speed (#225), so
 * both use the same CdA, Crr and air density: `P = ½ρ·CdA·v³ + Crr·m·g·v + m·g·v·slope`.
 */
object RidePhysics {

    private const val MAX_SPEED_MPS = 40.0

    /** Aerodynamic drag power at [speedMps], in W. */
    fun dragW(speedMps: Double): Double =
        0.5 * CyclingConstants.SPEED_IQ_AIR_DENSITY * CyclingConstants.SPEED_IQ_CDA_M2 * speedMps * speedMps * speedMps

    /** Rolling resistance power at [speedMps], in W. */
    fun rollingW(massKg: Double, speedMps: Double): Double =
        CyclingConstants.SPEED_IQ_CRR * massKg * CyclingConstants.SPEED_IQ_GRAVITY * speedMps

    /**
     * Steady speed in m/s that [powerW] holds on [slope] (rise over run): the one positive root of
     * `½ρ·CdA·v³ + m·g·(Crr + slope)·v = P`, found by bisection. 0 for no power.
     */
    fun steadySpeedMps(powerW: Double, slope: Double, massKg: Double): Double {
        if (powerW <= 0) return 0.0
        val a = 0.5 * CyclingConstants.SPEED_IQ_AIR_DENSITY * CyclingConstants.SPEED_IQ_CDA_M2
        val b = massKg * CyclingConstants.SPEED_IQ_GRAVITY * (CyclingConstants.SPEED_IQ_CRR + slope)
        // f(v) = a·v³ + b·v − P is negative at 0 and rises past its only positive root
        var lo = 0.0
        var hi = MAX_SPEED_MPS
        repeat(50) {
            val mid = (lo + hi) / 2
            if (a * mid * mid * mid + b * mid < powerW) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }
}
