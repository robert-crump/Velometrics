package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.data.repository.FakeBestEffortRepository
import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.CyclingSession
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RideRevealEvaluatorTest {

    private lateinit var repository: FakeCyclingSessionRepository
    private lateinit var bestEffortRepository: FakeBestEffortRepository
    private lateinit var evaluator: RideRevealEvaluator

    @Before
    fun setup() {
        repository = FakeCyclingSessionRepository()
        bestEffortRepository = FakeBestEffortRepository()
        evaluator = RideRevealEvaluator(
            repository,
            RideMilestoneEvaluator(repository),
            PowerCurveAchievementEvaluator(bestEffortRepository)
        )
    }

    private fun makeSession(
        id: Long,
        start: Instant,
        distanceKm: Double = 30.0,
        elevationGainM: Double? = 250.0
    ): CyclingSession = CyclingSession(
        id = id,
        fileName = "ride_$id.fit",
        fileSha1 = "sha1_$id",
        sessionStart = start,
        sessionEnd = start.plusSeconds(3600),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = distanceKm,
        averagePower = null,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 95.0,
        powerQualityPercent = null,
        hasPower = false,
        gpsTrack = null,
        elevationGainM = elevationGainM
    )

    private suspend fun insert(start: Instant): Long =
        repository.insertSession(makeSession(id = 0, start = start))

    private suspend fun insert(start: Instant, distanceKm: Double, elevationGainM: Double?): Long =
        repository.insertSession(makeSession(id = 0, start = start, distanceKm = distanceKm, elevationGainM = elevationGainM))

    @Test
    fun `captureBaseline returns null for an empty database`() = runBlocking {
        assertNull(evaluator.captureBaseline())
    }

    @Test
    fun `captureBaseline returns the latest existing session start`() = runBlocking {
        val older = Instant.parse("2026-01-01T00:00:00Z")
        val newer = Instant.parse("2026-02-01T00:00:00Z")
        insert(older)
        insert(newer)

        assertEquals(newer, evaluator.captureBaseline())
    }

    @Test
    fun `fires the reveal when the imported ride is later than the baseline`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        insert(baseline)
        val newRideStart = Instant.parse("2026-02-01T00:00:00Z")
        val id = insert(newRideStart)

        val results = listOf(ImportResult.Success(id, "summary", newRideStart))
        val content = evaluator.evaluate(results, baseline)

        assertEquals(id, content?.sessionId)
        assertEquals(30.0, content?.distanceKm)
        assertEquals(250.0, content?.elevationGainM)
    }

    @Test
    fun `does not fire when the imported ride is not later than the baseline`() = runBlocking {
        val baseline = Instant.parse("2026-02-01T00:00:00Z")
        val id = insert(Instant.parse("2026-01-15T00:00:00Z"))

        val results = listOf(ImportResult.Success(id, "summary", Instant.parse("2026-01-15T00:00:00Z")))

        assertNull(evaluator.evaluate(results, baseline))
    }

    @Test
    fun `does not fire on a backfill into an empty database, even for the newest imported ride`() = runBlocking {
        // captureBaseline() would have returned null before this batch, since the DB was empty.
        val id = insert(Instant.parse("2020-06-01T00:00:00Z"))
        val results = listOf(ImportResult.Success(id, "summary", Instant.parse("2020-06-01T00:00:00Z")))

        assertNull(evaluator.evaluate(results, baseline = null))
    }

    @Test
    fun `picks the chronologically newest ride in the batch, not the last-processed file`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        val processedFirst = insert(Instant.parse("2026-03-01T00:00:00Z")) // chronologically newest
        val processedLast = insert(Instant.parse("2026-01-15T00:00:00Z")) // processed last, but older

        val results = listOf(
            ImportResult.Success(processedFirst, "summary", Instant.parse("2026-03-01T00:00:00Z")),
            ImportResult.Success(processedLast, "summary", Instant.parse("2026-01-15T00:00:00Z"))
        )

        val content = evaluator.evaluate(results, baseline)
        assertEquals(processedFirst, content?.sessionId)
    }

    @Test
    fun `ignores non-success results when picking the batch's newest ride`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        val id = insert(Instant.parse("2026-02-01T00:00:00Z"))

        val results = listOf(
            ImportResult.Error("bad file"),
            ImportResult.AlreadyImported("dup.fit"),
            ImportResult.Success(id, "summary", Instant.parse("2026-02-01T00:00:00Z"))
        )

        assertEquals(id, evaluator.evaluate(results, baseline)?.sessionId)
    }

    @Test
    fun `no successes in the batch never fires`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        val results = listOf(ImportResult.Error("bad file"))

        assertNull(evaluator.evaluate(results, baseline))
    }

    @Test
    fun `shows the milestone callout when the new ride is a genuine longest-ever`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        insert(baseline, distanceKm = 20.0, elevationGainM = 100.0)
        val newRideStart = Instant.parse("2026-02-01T00:00:00Z")
        val id = insert(newRideStart, distanceKm = 200.0, elevationGainM = 100.0)

        val results = listOf(ImportResult.Success(id, "summary", newRideStart))
        val content = evaluator.evaluate(results, baseline)

        assertTrue(content?.headline?.contains("longest") == true)
    }

    @Test
    fun `falls back to plain stats when the new ride sets no milestone`() = runBlocking {
        // Three rides already beat the new ride on every tracked metric, so it ranks 4th (or
        // worse) on all of them - no milestone qualifies, even with the "no sample-size floor"
        // assumption, since rank itself (not sample size) is what's checked.
        insert(Instant.parse("2026-01-01T00:00:00Z"), distanceKm = 200.0, elevationGainM = 3000.0)
        insert(Instant.parse("2026-01-02T00:00:00Z"), distanceKm = 210.0, elevationGainM = 3100.0)
        val baseline = Instant.parse("2026-01-03T00:00:00Z")
        insert(baseline, distanceKm = 220.0, elevationGainM = 3200.0)
        val newRideStart = Instant.parse("2026-02-01T00:00:00Z")
        val id = insert(newRideStart, distanceKm = 5.0, elevationGainM = 10.0)

        val results = listOf(ImportResult.Success(id, "summary", newRideStart))
        val content = evaluator.evaluate(results, baseline)

        assertEquals("Nice ride!", content?.headline)
    }

    @Test
    fun `shows the milestone headline over a power-curve achievement when both qualify`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        insert(baseline, distanceKm = 20.0, elevationGainM = 100.0)
        val newRideStart = Instant.parse("2026-02-01T00:00:00Z")
        // Both the longest-ever ride and a rank-1 20-minute power PR - milestone should win the tie.
        val id = insert(newRideStart, distanceKm = 200.0, elevationGainM = 100.0)
        bestEffortRepository.records.add(
            BestEffortRecord(
                sessionId = id,
                sessionStart = newRideStart.toEpochMilli(),
                split25kSec = null,
                split50kSec = null,
                split100kSec = null,
                power1s = null,
                power3s = null,
                power5s = null,
                power20s = null,
                power30s = null,
                power1m = null,
                power5m = null,
                power20m = 250,
                power30m = null
            )
        )

        val results = listOf(ImportResult.Success(id, "summary", newRideStart))
        val content = evaluator.evaluate(results, baseline)

        assertTrue(content?.headline?.contains("longest") == true)
    }

    @Test
    fun `shows a power-curve achievement when the ride sets a genuine 20-minute power PR`() = runBlocking {
        val baseline = Instant.parse("2026-01-01T00:00:00Z")
        // Older ride beats the new one on distance/elevation, so no milestone qualifies.
        insert(baseline, distanceKm = 200.0, elevationGainM = 3000.0)
        val newRideStart = Instant.parse("2026-02-01T00:00:00Z")
        val id = insert(newRideStart, distanceKm = 20.0, elevationGainM = 100.0)
        bestEffortRepository.records.add(
            BestEffortRecord(
                sessionId = id,
                sessionStart = newRideStart.toEpochMilli(),
                split25kSec = null,
                split50kSec = null,
                split100kSec = null,
                power1s = null,
                power3s = null,
                power5s = null,
                power20s = null,
                power30s = null,
                power1m = null,
                power5m = null,
                power20m = 250,
                power30m = null
            )
        )

        val results = listOf(ImportResult.Success(id, "summary", newRideStart))
        val content = evaluator.evaluate(results, baseline)

        assertEquals("Your best 20-minute power ever!", content?.headline)
    }
}
