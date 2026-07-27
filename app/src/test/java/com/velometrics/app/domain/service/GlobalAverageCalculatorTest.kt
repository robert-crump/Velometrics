package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class GlobalAverageCalculatorTest {

    private fun session(
        id: Long,
        netDurationSec: Int = 3600,
        powerZoneDistribution: Map<String, Int>? = null,
        hrZoneDistribution: Map<String, Int>? = null,
        speedHistogram: Map<String, Int> = emptyMap(),
        hasPower: Boolean = powerZoneDistribution != null
    ) = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = Instant.EPOCH,
        sessionEnd = Instant.EPOCH.plusSeconds(netDurationSec.toLong()),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = 30.0,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = powerZoneDistribution,
        speedHistogram = speedHistogram,
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = hasPower,
        hrZoneDistribution = hrZoneDistribution
    )

    @Test
    fun `equal weight per ride regardless of ride length`() {
        // Short ride: 600s total, 300s (50%) in Zone 1.
        // Long ride: 36000s total, 3600s (10%) in Zone 1.
        // Equal-weight average of percentages is (50+10)/2 = 30%, not a duration-weighted value.
        val short = session(1, powerZoneDistribution = mapOf("Zone 1" to 300, "Zone 2" to 300))
        val long = session(2, powerZoneDistribution = mapOf("Zone 1" to 3600, "Zone 2" to 32400))

        val result = GlobalAverageCalculator.computePowerZoneAverages(listOf(short, long))

        assertEquals(30f, result.getValue("Zone 1"), 0.01f)
        assertEquals(70f, result.getValue("Zone 2"), 0.01f)
    }

    @Test
    fun `sessions without power or HR data are skipped for that metric's average only`() {
        val withPower = session(
            1,
            powerZoneDistribution = mapOf("Zone 1" to 100),
            hasPower = true,
            speedHistogram = mapOf("10-20 km/h" to 50, "20-25 km/h" to 50)
        )
        val withoutPower = session(
            2,
            powerZoneDistribution = null,
            hasPower = false,
            hrZoneDistribution = mapOf("Zone 1" to 50, "Zone 2" to 50),
            speedHistogram = mapOf("10-20 km/h" to 100)
        )

        val powerResult = GlobalAverageCalculator.computePowerZoneAverages(listOf(withPower, withoutPower))
        val hrResult = GlobalAverageCalculator.computeHrZoneAverages(listOf(withPower, withoutPower))
        val speedResult = GlobalAverageCalculator.computeSpeedHistogramAverages(listOf(withPower, withoutPower))

        // Only one session has power data — the average is that session's own value, not diluted by the other.
        assertEquals(100f, powerResult.getValue("Zone 1"), 0.01f)
        // Only one session has HR data.
        assertEquals(50f, hrResult.getValue("Zone 1"), 0.01f)
        // Speed histogram is present on every session (never null), so both contribute equally: (50+100)/2.
        assertEquals(75f, speedResult.getValue("10-20 km/h"), 0.01f)
    }

    @Test
    fun `no sessions with the relevant data yields an empty map`() {
        val session = session(1, powerZoneDistribution = null, hasPower = false)

        val result = GlobalAverageCalculator.computePowerZoneAverages(listOf(session))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `empty session list yields an empty map`() {
        val result = GlobalAverageCalculator.computeSpeedHistogramAverages(emptyList())

        assertTrue(result.isEmpty())
    }

    @Test
    fun `a zone present in only some sessions is still averaged correctly`() {
        // Session 1 never reaches Zone 3; session 2 spends half its time there.
        val s1 = session(1, powerZoneDistribution = mapOf("Zone 1" to 100, "Zone 2" to 100))
        val s2 = session(2, powerZoneDistribution = mapOf("Zone 2" to 50, "Zone 3" to 50))

        val result = GlobalAverageCalculator.computePowerZoneAverages(listOf(s1, s2))

        // s1 contributes 0% to Zone 3, s2 contributes 50% -> average 25%, not just s2's 50%.
        assertEquals(25f, result.getValue("Zone 3"), 0.01f)
    }
}
