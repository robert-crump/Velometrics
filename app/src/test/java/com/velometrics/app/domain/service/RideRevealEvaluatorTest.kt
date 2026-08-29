package com.velometrics.app.domain.service

import com.velometrics.app.data.fitimport.ImportResult
import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.CyclingSession
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class RideRevealEvaluatorTest {

    private lateinit var repository: FakeCyclingSessionRepository
    private lateinit var evaluator: RideRevealEvaluator

    @Before
    fun setup() {
        repository = FakeCyclingSessionRepository()
        evaluator = RideRevealEvaluator(repository)
    }

    private fun makeSession(id: Long, start: Instant): CyclingSession = CyclingSession(
        id = id,
        fileName = "ride_$id.fit",
        fileSha1 = "sha1_$id",
        sessionStart = start,
        sessionEnd = start.plusSeconds(3600),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = 30.0,
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
        elevationGainM = 250.0
    )

    private suspend fun insert(start: Instant): Long =
        repository.insertSession(makeSession(id = 0, start = start))

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
}
