package com.velometrics.app.domain.service

import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class SessionComparatorTest {

    private lateinit var repository: FakeCyclingSessionRepository
    private lateinit var comparator: SessionComparator

    @Before
    fun setup() {
        repository = FakeCyclingSessionRepository()
        comparator = SessionComparator(repository)
    }

    private fun makeSession(
        id: Long,
        daysAgo: Long,
        netDurationSec: Int,
        distanceKm: Double,
        hasPower: Boolean = false,
        averagePower: Int? = null,
        normalizedPower: Int? = null,
        intervalTotalTimeSec: Int = 0,
        avgHeartRate: Int? = null
    ): CyclingSession {
        val start = Instant.now().minusSeconds(daysAgo * 86400)
        return CyclingSession(
            id = id,
            fileName = "ride_$id.fit",
            fileSha1 = "sha1_$id",
            sessionStart = start,
            sessionEnd = start.plusSeconds(netDurationSec.toLong() + 300),
            totalDurationSec = netDurationSec + 300,
            pauseDurationSec = 300,
            netDurationSec = netDurationSec,
            distanceKm = distanceKm,
            averagePower = averagePower,
            normalizedPower = normalizedPower,
            fatBurnedGrams = if (hasPower) 30.0 else null,
            carbsBurnedGrams = if (hasPower) 80.0 else null,
            powerZoneDistribution = if (hasPower) mapOf("Zone 1" to 100) else null,
            speedHistogram = mapOf("0-10 km/h" to 100),
            intervalCount = 0,
            intervalTotalTimeSec = intervalTotalTimeSec,
            gpsQualityPercent = 95.0,
            powerQualityPercent = if (hasPower) 90.0 else null,
            hasPower = hasPower,
            gpsTrack = null,
            avgHeartRate = avgHeartRate
        )
    }

    @Test
    fun `zero previous sessions returns null`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0)
        repository.sessions.add(current)

        val result = comparator.computeComparison(current)
        assertNull(result)
    }

    @Test
    fun `one previous session returns null`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0)
        val prev1 = makeSession(2, 1, 3500, 28.0)
        repository.sessions.addAll(listOf(current, prev1))

        val result = comparator.computeComparison(current)
        assertNull(result)
    }

    @Test
    fun `three previous sessions with power computes medians correctly`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = true, averagePower = 240, normalizedPower = 260)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, normalizedPower = 280)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNotNull(result)
        assertEquals(3, result!!.previousSessionCount)
        // Median of [3400, 3600, 3800] = 3600
        assertEquals(3600, result.medianNetDurationSec)
        // Median of [28.0, 30.0, 32.0] = 30.0
        assertEquals(30.0, result.medianDistanceKm!!, 0.01)
        // Median of [240, 250, 260] = 250
        assertEquals(250, result.medianAvgPower)
        // Median of [260, 270, 280] = 270
        assertEquals(270, result.medianNormalizedPower)
    }

    @Test
    fun `mixed power and no-power sessions compute power median from power sessions only`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = false)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = true, averagePower = 240, normalizedPower = 260)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, normalizedPower = 280)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNotNull(result)
        assertEquals(3, result!!.previousSessionCount)
        // Distance/duration medians from all 3 previous
        assertEquals(3600, result.medianNetDurationSec)
        // Power medians from 2 power sessions: [240, 260] → (240+260)/2 = 250
        assertEquals(250, result.medianAvgPower)
        assertEquals(270, result.medianNormalizedPower)
    }

    @Test
    fun `all previous without power yields null power medians`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = false)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = false)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = false)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNotNull(result)
        assertNull(result!!.medianAvgPower)
        assertNull(result.medianNormalizedPower)
        // Non-power medians should still exist
        assertNotNull(result.medianNetDurationSec)
        assertNotNull(result.medianDistanceKm)
    }

    @Test
    fun `median cardiac efficiency computed from sessions with both power and heart rate`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, avgHeartRate = 140)
        // cardiac efficiency = 240/120 = 2.0
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = true, averagePower = 240, avgHeartRate = 120)
        // no heart rate -> excluded
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = true, averagePower = 250, avgHeartRate = null)
        // cardiac efficiency = 260/130 = 2.0
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, avgHeartRate = 130)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNotNull(result)
        // Median of [2.0, 2.0] = 2.0
        assertEquals(2.0, result!!.medianCardiacEfficiency!!, 0.001)
    }

    @Test
    fun `no previous sessions with both power and heart rate yields null median cardiac efficiency`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, avgHeartRate = 140)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = true, averagePower = 240, avgHeartRate = null)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = false)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, avgHeartRate = null)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNotNull(result)
        assertNull(result!!.medianCardiacEfficiency)
    }
}
