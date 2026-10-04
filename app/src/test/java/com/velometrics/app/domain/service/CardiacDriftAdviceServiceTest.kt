package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CardiacDriftCause
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.FtpHistory
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

class CardiacDriftAdviceServiceTest {

    private val repository = FakeCyclingSessionRepository()
    private val service = CardiacDriftAdviceService(repository)
    private val rideStart = Instant.parse("2026-07-01T09:00:00Z")

    private fun session(
        id: Long,
        start: Instant,
        netDurationSec: Int,
        driftPercent: Double? = null,
        causes: List<CardiacDriftCause>? = null
    ) = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = start,
        sessionEnd = start.plusSeconds(netDurationSec.toLong()),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = 40.0,
        averagePower = 140,
        normalizedPower = 150,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = 100.0,
        hasPower = true,
        cardiacDriftPercent = driftPercent,
        cardiacDriftCauses = causes
    )

    private fun daysBefore(days: Long) = rideStart.minus(Duration.ofDays(days))

    @Test
    fun `duration baseline only counts long rides in the six weeks before`() = runBlocking {
        repository.sessions += listOf(
            session(1, daysBefore(3), 3600),
            session(2, daysBefore(10), 4000),
            session(3, daysBefore(20), 4400),
            session(4, daysBefore(25), 1800), // too short
            session(5, daysBefore(50), 20000), // outside the window
            session(6, rideStart.plusSeconds(86400), 20000) // after the ride
        )
        val evaluated = service.evaluate(session(7, rideStart, 6000, driftPercent = 8.0), ftp = 250)
        assertEquals(4000, evaluated.cardiacDriftDurationBaselineSec)
        assertEquals(listOf(CardiacDriftCause.DURATION), evaluated.cardiacDriftCauses)
    }

    @Test
    fun `low drift gets no causes and no baseline`() = runBlocking {
        repository.sessions += (1L..3L).map { session(it, daysBefore(it), 3600) }
        val evaluated = service.evaluate(session(9, rideStart, 6000, driftPercent = 3.0), ftp = 250)
        assertNull(evaluated.cardiacDriftCauses)
        assertNull(evaluated.cardiacDriftDurationBaselineSec)
    }

    @Test
    fun `backfill evaluates only never-evaluated medium or high rides`() = runBlocking {
        repository.sessions += listOf(
            session(1, daysBefore(1), 6000, driftPercent = 8.0),
            session(2, daysBefore(2), 6000, driftPercent = 2.0),
            session(3, daysBefore(3), 6000, driftPercent = 12.0, causes = listOf(CardiacDriftCause.HEAT))
        )
        service.backfillMissing(FtpHistory.constant(250))

        assertEquals(emptyList<CardiacDriftCause>(), repository.sessions.first { it.id == 1L }.cardiacDriftCauses)
        assertNull(repository.sessions.first { it.id == 2L }.cardiacDriftCauses)
        // Already evaluated: left alone even though a fresh evaluation would differ (no temperature).
        assertEquals(listOf(CardiacDriftCause.HEAT), repository.sessions.first { it.id == 3L }.cardiacDriftCauses)
    }

    @Test
    fun `recomputeAll corrects stale causes and is idempotent`() = runBlocking {
        repository.sessions += session(1, daysBefore(1), 6000, driftPercent = 12.0, causes = listOf(CardiacDriftCause.HEAT))
        service.recomputeAll(FtpHistory.constant(250))
        service.recomputeAll(FtpHistory.constant(250))
        assertEquals(emptyList<CardiacDriftCause>(), repository.sessions.single().cardiacDriftCauses)
    }
}
