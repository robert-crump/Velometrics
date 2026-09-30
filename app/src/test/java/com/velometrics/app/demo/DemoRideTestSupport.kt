package com.velometrics.app.demo

import com.velometrics.app.domain.service.BestEffortCalculator
import java.io.InputStream
import java.time.LocalDate

/** Shared fixture for the demo-ride JVM tests: generating all rides takes a few seconds, so once. */
object DemoRideTestSupport {
    val TODAY: LocalDate = LocalDate.of(2026, 9, 30)

    fun openAsset(path: String): InputStream =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { "missing test asset $path" }

    val rides: List<DemoRide> by lazy { DemoRideGenerator.generate(TODAY, ::openAsset) }

    /** Best average power over [durationSec] of wall-clock time, as the app's power curve computes it. */
    fun bestAvgPower(ride: DemoRide, durationSec: Int): Int? {
        val t0 = ride.samples.first().epochSec
        val elapsed = DoubleArray(ride.samples.size) { (ride.samples[it].epochSec - t0).toDouble() }
        val energy = DoubleArray(ride.samples.size)
        for (i in 1 until ride.samples.size) {
            energy[i] = energy[i - 1] + ride.samples[i - 1].power * (elapsed[i] - elapsed[i - 1])
        }
        return BestEffortCalculator.bestAvgPowerForDuration(elapsed, energy, durationSec.toDouble())
    }
}
