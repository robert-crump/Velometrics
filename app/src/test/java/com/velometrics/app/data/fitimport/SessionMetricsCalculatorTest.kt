package com.velometrics.app.data.fitimport

import com.velometrics.app.domain.model.Datapoint
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SessionMetricsCalculatorTest {

    private val calculator = SessionMetricsCalculator()

    private fun datapoint(
        index: Int,
        heartRate: Int? = null,
        altitude: Double? = null,
        power: Int? = null
    ): Datapoint {
        return Datapoint(
            lat = 50.78 + index * 0.0001,
            lon = 6.07 + index * 0.0001,
            speedKmh = 20.0,
            power = power,
            timestamp = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index.toLong()),
            heartRate = heartRate,
            altitude = altitude
        )
    }

    @Test
    fun `avgHeartRate is mean of non-null non-zero readings when coverage clears threshold`() {
        // 10 datapoints, all with non-zero HR -> coverage 100% >= 10%
        val datapoints = (0 until 10).map { datapoint(it, heartRate = 120 + it) }

        val session = calculator.compute(
            fileName = "test.fit",
            fileSha1 = "sha1",
            datapoints = datapoints,
            hasPower = false,
            timerEvents = emptyList(),
            rawRecordCount = 10,
            originalPowerCount = 0
        )

        // mean of 120..129 = 124.5 -> rounds to 125 (half-up)
        assertEquals(125, session.avgHeartRate)
    }

    @Test
    fun `avgHeartRate excludes zero readings`() {
        val datapoints = (0 until 10).map {
            datapoint(it, heartRate = if (it < 5) 0 else 140)
        }

        val session = calculator.compute(
            fileName = "test.fit",
            fileSha1 = "sha1",
            datapoints = datapoints,
            hasPower = false,
            timerEvents = emptyList(),
            rawRecordCount = 10,
            originalPowerCount = 0
        )

        // Only the 5 non-zero readings (all 140) count, coverage 50% >= 10%
        assertEquals(140, session.avgHeartRate)
    }

    @Test
    fun `avgHeartRate is null when coverage is below threshold`() {
        // 100 datapoints, only 1 has a non-zero HR reading -> coverage 1% < 10%
        val datapoints = (0 until 100).map {
            datapoint(it, heartRate = if (it == 0) 150 else null)
        }

        val session = calculator.compute(
            fileName = "test.fit",
            fileSha1 = "sha1",
            datapoints = datapoints,
            hasPower = false,
            timerEvents = emptyList(),
            rawRecordCount = 100,
            originalPowerCount = 0
        )

        assertNull(session.avgHeartRate)
    }

    @Test
    fun `avgHeartRate is null when no heart rate data present`() {
        val datapoints = (0 until 10).map { datapoint(it) }

        val session = calculator.compute(
            fileName = "test.fit",
            fileSha1 = "sha1",
            datapoints = datapoints,
            hasPower = false,
            timerEvents = emptyList(),
            rawRecordCount = 10,
            originalPowerCount = 0
        )

        assertNull(session.avgHeartRate)
    }

    @Test
    fun `elevationGainM is null when altitude data is absent or insufficient`() {
        val noAltitude = (0 until 10).map { datapoint(it) }
        val oneAltitude = listOf(datapoint(0, altitude = 100.0)) +
            (1 until 10).map { datapoint(it) }

        val sessionNoAlt = calculator.compute(
            fileName = "test.fit", fileSha1 = "sha1", datapoints = noAltitude,
            hasPower = false, timerEvents = emptyList(), rawRecordCount = 10, originalPowerCount = 0
        )
        val sessionOneAlt = calculator.compute(
            fileName = "test.fit", fileSha1 = "sha1", datapoints = oneAltitude,
            hasPower = false, timerEvents = emptyList(), rawRecordCount = 10, originalPowerCount = 0
        )

        assertNull(sessionNoAlt.elevationGainM)
        assertNull(sessionOneAlt.elevationGainM)
    }

    @Test
    fun `elevationGainM rejects jitter within the hysteresis threshold on a flat profile`() {
        // Oscillates +/-2m around 100m, below the 3m threshold -> 0 gain
        val altitudes = listOf(100.0, 102.0, 100.0, 101.0, 100.0, 102.0, 101.0, 100.0)
        val datapoints = altitudes.mapIndexed { i, alt -> datapoint(i, altitude = alt) }

        val session = calculator.compute(
            fileName = "test.fit", fileSha1 = "sha1", datapoints = datapoints,
            hasPower = false, timerEvents = emptyList(), rawRecordCount = altitudes.size, originalPowerCount = 0
        )

        assertEquals(0.0, session.elevationGainM!!, 0.001)
    }

    @Test
    fun `elevationGainM accumulates a real climb with descents resetting the reference`() {
        // Climb 100 -> 130 (gain 30), descend to 110, climb again to 125 (gain 15) = 45 total
        val altitudes = listOf(100.0, 110.0, 120.0, 130.0, 110.0, 115.0, 125.0)
        val datapoints = altitudes.mapIndexed { i, alt -> datapoint(i, altitude = alt) }

        val session = calculator.compute(
            fileName = "test.fit", fileSha1 = "sha1", datapoints = datapoints,
            hasPower = false, timerEvents = emptyList(), rawRecordCount = altitudes.size, originalPowerCount = 0
        )

        assertEquals(45.0, session.elevationGainM!!, 0.001)
    }
}
