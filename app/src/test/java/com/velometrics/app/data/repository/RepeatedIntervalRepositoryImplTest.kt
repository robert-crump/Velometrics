package com.velometrics.app.data.repository

import com.velometrics.app.data.local.dao.RepeatedIntervalDao
import com.velometrics.app.data.local.entity.RepeatedIntervalEntity
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers #192: a [RepeatedIntervalEntity]'s `intervalIds` has no FK, so between a delete and the
 * next recluster pass it can point at an interval whose session no longer exists.
 */
class RepeatedIntervalRepositoryImplTest {

    private val gson = Gson()

    private fun buildInterval(id: Long) = IntervalSession(
        id = id,
        cyclingSessionId = id,
        startTimestamp = Instant.parse("2026-01-01T00:00:00Z"),
        durationSec = 200,
        durationNormalizedSec = 200,
        distanceM = 1000.0,
        avgPower = 250,
        avgSpeedKmh = 25.0,
        avgSpeedNormalizedKmh = 25.0,
        direction = "out",
        startLat = 50.78, startLon = 6.08, endLat = 50.79, endLon = 6.08,
        gpsTrack = "[]"
    )

    private fun buildRepository(
        intervalIds: List<Long>,
        existingIntervalIds: List<Long>
    ): RepeatedIntervalRepositoryImpl {
        val entity = RepeatedIntervalEntity(
            id = 1L,
            name = "Repeated Interval 1",
            intervalIds = gson.toJson(intervalIds.sorted()),
            edges = gson.toJson(emptyList<List<Long>>()),
            startLat = 50.78, startLon = 6.08, endLat = 50.79, endLon = 6.08,
            distanceM = 1000.0,
            createdAt = 1_000L
        )
        val dao = mockk<RepeatedIntervalDao>()
        coEvery { dao.getAllList() } returns listOf(entity)

        val intervalRepository = mockk<IntervalRepository>()
        every { intervalRepository.getAllIntervals() } returns
            flowOf(existingIntervalIds.map { buildInterval(it) })

        val mapGraphRepository = mockk<MapGraphRepository>()
        coEvery { mapGraphRepository.getEdgesByNodePairs(any()) } returns emptyList()

        return RepeatedIntervalRepositoryImpl(dao, intervalRepository, mapGraphRepository)
    }

    @Test
    fun `a repeated interval with one stale member id resolves with the remaining valid intervals`() = runTest {
        val repository = buildRepository(
            intervalIds = listOf(1L, 2L, 3L),
            existingIntervalIds = listOf(1L, 2L)
        )

        val entries = repository.getAllRepeatedIntervalsList()

        // No minimum group size for interval clustering (unlike routes) - a partial survivor list
        // still resolves rather than being hidden or throwing.
        assertEquals(1, entries.size)
        assertEquals(setOf(1L, 2L), entries.single().intervals.map { it.id }.toSet())
    }

    @Test
    fun `a repeated interval with no surviving members at all is hidden`() = runTest {
        val repository = buildRepository(
            intervalIds = listOf(1L, 2L, 3L),
            existingIntervalIds = emptyList()
        )

        val entries = repository.getAllRepeatedIntervalsList()

        assertTrue(entries.isEmpty())
    }
}
