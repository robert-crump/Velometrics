package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeIntervalRepository
import com.velometrics.app.fakes.FakeRepeatedRoutesCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RouteRecapTest {

    private fun ride(id: Long, km: Double, power: Int?, hr: Int?) = CyclingSession(
        id = id,
        fileName = "ride-$id.fit",
        fileSha1 = "sha-$id",
        sessionStart = Instant.parse("2026-03-10T08:00:00Z").minusSeconds(id * 86_400),
        sessionEnd = Instant.parse("2026-03-10T09:00:00Z").minusSeconds(id * 86_400),
        totalDurationSec = 3600,
        pauseDurationSec = 0,
        netDurationSec = 3600,
        distanceKm = km,
        averagePower = power,
        normalizedPower = null,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = power != null,
        avgHeartRate = hr
    )

    private fun route(vararg rides: CyclingSession, custom: Boolean = false) =
        RepeatedRoute(7L, if (custom) "Canal loop" else "Repeated Route 1", custom, rides.toList(), null)

    @Test
    fun `recap uses the exact format and excludes the current ride from the median`() {
        val r = route(ride(1, 26.2, 210, 142), ride(2, 26.0, 190, 145), ride(3, 28.0, 200, 147))
        val recap = SessionNarrativeAssembler.buildRouteRecap(1, r)!!
        assertEquals(7L, recap.routeId)
        assertEquals("vs. Repeated Route 1", recap.headline)
        assertEquals(
            listOf("26.2 km/h (vs. 27.0 km/h)", "210 W (vs. 195 W)", "142 bpm (vs. 146 bpm)"),
            recap.lines
        )
    }

    @Test
    fun `custom route name is used in the headline`() {
        val r = route(ride(1, 26.0, 200, 140), ride(2, 26.0, 200, 140), ride(3, 26.0, 200, 140), custom = true)
        assertEquals("vs. Canal loop", SessionNarrativeAssembler.buildRouteRecap(1, r)!!.headline)
    }

    @Test
    fun `stat lines drop independently when other rides lack the data`() {
        val r = route(ride(1, 26.0, 210, 142), ride(2, 26.0, null, 145), ride(3, 26.0, null, null))
        assertEquals(
            listOf("26.0 km/h (vs. 26.0 km/h)", "142 bpm (vs. 145 bpm)"),
            SessionNarrativeAssembler.buildRouteRecap(1, r)!!.lines
        )
    }

    @Test
    fun `no recap for a non-member session or an undersized route`() {
        val r = route(ride(1, 26.0, 200, 140), ride(2, 26.0, 200, 140), ride(3, 26.0, 200, 140))
        assertNull(SessionNarrativeAssembler.buildRouteRecap(99, r))
        assertNull(SessionNarrativeAssembler.buildRouteRecap(1, route(ride(1, 26.0, 200, 140), ride(2, 26.0, 200, 140))))
    }

    @Test
    fun `observeRouteRecap finds the session's route through the cache`() = runTest {
        val repo = FakeCyclingSessionRepository()
        val cache = FakeRepeatedRoutesCache(
            listOf(route(ride(1, 26.0, 200, 140), ride(2, 26.0, 200, 140), ride(3, 26.0, 200, 140)))
        )
        val assembler = SessionNarrativeAssembler(repo, FakeIntervalRepository(), SessionComparator(repo), cache)
        assertEquals(7L, assembler.observeRouteRecap(2).first()!!.routeId)
        assertNull(assembler.observeRouteRecap(50).first())
    }
}
