package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.RepeatedRouteDao
import com.velometrics.app.data.local.entity.RepeatedRouteEntity
import com.velometrics.app.domain.model.CyclingSession
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers #192: a [RepeatedRouteEntity]'s `sessionIds` has no FK, so between a delete and the next
 * recluster pass it can point at a session that no longer exists.
 */
class RepeatedRouteRepositoryImplTest {

    private val gson = Gson()

    private fun buildSession(id: Long) = CyclingSession(
        id = id,
        fileName = "ride-$id.fit",
        fileSha1 = "sha-$id",
        sessionStart = Instant.parse("2026-01-01T00:00:00Z"),
        sessionEnd = Instant.parse("2026-01-01T01:00:00Z"),
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
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = false
    )

    private fun buildRepository(
        sessionIds: List<Long>,
        existingSessionIds: List<Long>
    ): RepeatedRouteRepositoryImpl {
        val entity = RepeatedRouteEntity(
            id = 1L,
            name = "Repeated Route 1",
            sessionIds = gson.toJson(sessionIds.sorted()),
            createdAt = 1_000L
        )
        val dao = mockk<RepeatedRouteDao>()
        coEvery { dao.getAllRoutesList() } returns listOf(entity)

        val sessionRepository = FakeCyclingSessionRepository()
        existingSessionIds.forEach { sessionRepository.sessions.add(buildSession(it)) }

        return RepeatedRouteRepositoryImpl(dao, sessionRepository)
    }

    @Test
    fun `a route with one stale member id resolves with the remaining valid sessions`() = runTest {
        // 4 members, 1 deleted since the last recluster -> 3 remain, still >= the minimum group size.
        val repository = buildRepository(
            sessionIds = listOf(1L, 2L, 3L, 4L),
            existingSessionIds = listOf(1L, 2L, 3L)
        )

        val routes = repository.getAllRoutesList()

        assertEquals(1, routes.size)
        assertEquals(setOf(1L, 2L, 3L), routes.single().sessions.map { it.id }.toSet())
    }

    @Test
    fun `a route whose surviving member count drops below the minimum group size is hidden`() = runTest {
        // 3 members, 1 deleted since the last recluster -> only 2 remain, below the minimum of 3.
        val repository = buildRepository(
            sessionIds = listOf(1L, 2L, 3L),
            existingSessionIds = listOf(1L, 2L)
        )

        val routes = repository.getAllRoutesList()

        assertTrue(routes.isEmpty())
    }

    @Test
    fun `a route with no surviving members at all is hidden, not just filtered down`() = runTest {
        val repository = buildRepository(
            sessionIds = listOf(1L, 2L, 3L),
            existingSessionIds = emptyList()
        )

        val routes = repository.getAllRoutesList()

        assertTrue(routes.isEmpty())
    }
}
