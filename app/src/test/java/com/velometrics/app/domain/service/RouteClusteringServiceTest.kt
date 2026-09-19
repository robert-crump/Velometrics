package com.velometrics.app.domain.service

import android.util.Log
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.domain.repository.RepeatedRouteRepository
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.testSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Covers #213: newly clustered routes are on their default name; matched routes keep their flag. */
class RouteClusteringServiceTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() = unmockkStatic(Log::class)

    private val track = "[" + (0..100).joinToString(",") { "[${50.78 + it * 0.0002},6.07]" } + "]"

    private fun sessionRepo() = FakeCyclingSessionRepository().apply {
        (1L..3L).forEach {
            sessions.add(testSession(it, Instant.parse("2026-01-0${it}T00:00:00Z")).copy(gpsTrack = track))
        }
    }

    private fun routeRepo(existing: List<RepeatedRoute>, saved: MutableList<RepeatedRoute>): RepeatedRouteRepository {
        val repo = mockk<RepeatedRouteRepository>(relaxed = true)
        coEvery { repo.getAllRoutesList() } returns existing
        val slot = slot<RepeatedRoute>()
        coEvery { repo.saveRoute(capture(slot)) } answers { saved.add(slot.captured); 1L }
        return repo
    }

    @Test
    fun `a newly created cluster is saved with isCustomName false`() = runTest {
        val saved = mutableListOf<RepeatedRoute>()
        RouteClusteringService(sessionRepo(), routeRepo(emptyList(), saved)).runClustering()

        assertEquals(1, saved.size)
        assertEquals("Repeated Route 1", saved.single().name)
        assertFalse(saved.single().isCustomName)
    }

    @Test
    fun `a re-clustered route keeps its existing custom-name flag`() = runTest {
        val sessions = sessionRepo()
        val existing = RepeatedRoute(
            id = 7L, name = "Hill loop", isCustomName = true,
            sessions = sessions.sessions.toList(), representativeTrack = null
        )
        val saved = mutableListOf<RepeatedRoute>()
        RouteClusteringService(sessions, routeRepo(listOf(existing), saved)).runClustering()

        assertEquals("Hill loop", saved.single().name)
        assertTrue(saved.single().isCustomName)
    }
}
