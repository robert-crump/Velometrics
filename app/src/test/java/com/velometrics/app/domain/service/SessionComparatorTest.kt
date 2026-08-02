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
    fun `zero previous sessions yields null medians and zero counts`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0)
        repository.sessions.add(current)

        val result = comparator.computeComparison(current)
        assertEquals(0, result.last5SessionCount)
        assertEquals(0, result.allPreviousSessionCount)
        assertNull(result.medianNetDurationSecLast5)
        assertNull(result.medianNetDurationSecAllPrevious)
        assertNull(result.medianDistanceKmLast5)
        assertNull(result.medianDistanceKmAllPrevious)
    }

    @Test
    fun `one previous session yields null medians but reports the count`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0)
        val prev1 = makeSession(2, 1, 3500, 28.0)
        repository.sessions.addAll(listOf(current, prev1))

        val result = comparator.computeComparison(current)
        assertEquals(1, result.last5SessionCount)
        assertEquals(1, result.allPreviousSessionCount)
        assertNull(result.medianNetDurationSecLast5)
        assertNull(result.medianNetDurationSecAllPrevious)
    }

    @Test
    fun `three previous sessions with power computes medians for both pools`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = true, averagePower = 240, normalizedPower = 260)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, normalizedPower = 280)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertEquals(3, result.last5SessionCount)
        assertEquals(3, result.allPreviousSessionCount)
        // Only 3 previous sessions exist, so both pools are identical.
        // Median of [3400, 3600, 3800] = 3600
        assertEquals(3600, result.medianNetDurationSecLast5)
        assertEquals(3600, result.medianNetDurationSecAllPrevious)
        // Median of [28.0, 30.0, 32.0] = 30.0
        assertEquals(30.0, result.medianDistanceKmLast5!!, 0.01)
        // Median of [240, 250, 260] = 250
        assertEquals(250, result.medianAvgPowerLast5)
        // Median of [260, 270, 280] = 270
        assertEquals(270, result.medianNormalizedPowerLast5)
    }

    @Test
    fun `mixed power and no-power sessions compute power median from power sessions only`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = false)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = true, averagePower = 240, normalizedPower = 260)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, normalizedPower = 280)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertEquals(3, result.last5SessionCount)
        // Distance/duration medians from all 3 previous
        assertEquals(3600, result.medianNetDurationSecLast5)
        // Power medians from 2 power sessions: [240, 260] → (240+260)/2 = 250
        assertEquals(250, result.medianAvgPowerLast5)
        assertEquals(270, result.medianNormalizedPowerLast5)
    }

    @Test
    fun `all previous without power yields null power medians`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, normalizedPower = 270)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = false)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = false)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = false)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNull(result.medianAvgPowerLast5)
        assertNull(result.medianNormalizedPowerLast5)
        // Non-power medians should still exist
        assertNotNull(result.medianNetDurationSecLast5)
        assertNotNull(result.medianDistanceKmLast5)
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
        // Median of [2.0, 2.0] = 2.0
        assertEquals(2.0, result.medianCardiacEfficiencyLast5!!, 0.001)
    }

    @Test
    fun `no previous sessions with both power and heart rate yields null median cardiac efficiency`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0, hasPower = true, averagePower = 250, avgHeartRate = 140)
        val prev1 = makeSession(2, 1, 3400, 28.0, hasPower = true, averagePower = 240, avgHeartRate = null)
        val prev2 = makeSession(3, 2, 3600, 30.0, hasPower = false)
        val prev3 = makeSession(4, 3, 3800, 32.0, hasPower = true, averagePower = 260, avgHeartRate = null)
        repository.sessions.addAll(listOf(current, prev1, prev2, prev3))

        val result = comparator.computeComparison(current)
        assertNull(result.medianCardiacEfficiencyLast5)
    }

    @Test
    fun `more than 5 previous sessions makes last-5 and all-previous medians diverge`() = runBlocking {
        val current = makeSession(1, 0, 3600, 30.0)
        // 5 most recent (1-5 days ago): distances 40..44 -> median 42
        val recent = (1..5).map { daysAgo ->
            makeSession((daysAgo + 1).toLong(), daysAgo.toLong(), 3600, 39.0 + daysAgo)
        }
        // 2 older ones (6-7 days ago): distances 10, 12
        val older = listOf(
            makeSession(10, 6, 3600, 10.0),
            makeSession(11, 7, 3600, 12.0)
        )
        repository.sessions.add(current)
        repository.sessions.addAll(recent)
        repository.sessions.addAll(older)

        val result = comparator.computeComparison(current)
        assertEquals(5, result.last5SessionCount)
        assertEquals(7, result.allPreviousSessionCount)
        // Last 5: distances [40, 41, 42, 43, 44] -> median 42
        assertEquals(42.0, result.medianDistanceKmLast5!!, 0.01)
        // All 7: distances [10, 12, 40, 41, 42, 43, 44] -> median 41
        assertEquals(41.0, result.medianDistanceKmAllPrevious!!, 0.01)
    }
}
