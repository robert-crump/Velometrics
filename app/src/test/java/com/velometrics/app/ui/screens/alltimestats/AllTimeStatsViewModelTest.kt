package com.velometrics.app.ui.screens.alltimestats

import com.velometrics.app.data.repository.FakeBestEffortRepository
import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.BestEffortRecord
import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.util.FormatUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class AllTimeStatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun session(
        id: Long,
        sessionStart: Instant,
        distanceKm: Double = 50.0,
        netDurationSec: Int = 3600,
        elevationGainM: Double? = 500.0
    ) = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = sessionStart,
        sessionEnd = sessionStart.plusSeconds(netDurationSec.toLong()),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = distanceKm,
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
        hasPower = false,
        elevationGainM = elevationGainM
    )

    private fun bestEffort(
        sessionId: Long,
        sessionStart: Long,
        split25kSec: Double? = null,
        split50kSec: Double? = null,
        split100kSec: Double? = null,
        power1s: Int? = null
    ) = BestEffortRecord(
        sessionId = sessionId,
        sessionStart = sessionStart,
        split25kSec = split25kSec,
        split50kSec = split50kSec,
        split100kSec = split100kSec,
        power1s = power1s,
        power3s = null,
        power5s = null,
        power20s = null,
        power30s = null,
        power1m = null,
        power5m = null,
        power20m = null,
        power30m = null
    )

    private fun midYearInstant(year: Int): Instant =
        LocalDate.of(year, 7, 15).atStartOfDay(ZoneId.systemDefault()).toInstant()

    private fun buildViewModel(
        sessions: List<CyclingSession> = emptyList(),
        bestEfforts: List<BestEffortRecord> = emptyList()
    ): AllTimeStatsViewModel {
        val sessionRepo = FakeCyclingSessionRepository().apply { this.sessions.addAll(sessions) }
        val bestEffortRepo = FakeBestEffortRepository().apply { records.addAll(bestEfforts) }
        return AllTimeStatsViewModel(sessionRepo, bestEffortRepo)
    }

    // ---------------------------------------------------------------------
    // Per-year breakdown
    // ---------------------------------------------------------------------

    @Test
    fun `yearStats buckets sessions by calendar year and sorts years descending`() = runTest(testDispatcher) {
        val sessions = listOf(
            session(1, midYearInstant(2023), distanceKm = 10.0, netDurationSec = 1000, elevationGainM = 100.0),
            session(2, midYearInstant(2025), distanceKm = 20.0, netDurationSec = 2000, elevationGainM = 200.0),
            session(3, midYearInstant(2025), distanceKm = 30.0, netDurationSec = 3000, elevationGainM = 300.0),
            session(4, midYearInstant(2024), distanceKm = 40.0, netDurationSec = 4000, elevationGainM = 400.0)
        )
        val vm = buildViewModel(sessions = sessions)

        val state = vm.uiState.first { !it.isLoading }

        assertEquals(listOf(2025, 2024, 2023), state.yearStats.map { it.year })
        val year2025 = state.yearStats.first { it.year == 2025 }
        assertEquals(2, year2025.rideCount)
        assertEquals(50.0, year2025.totalDistanceKm, 0.001)
        assertEquals(500.0, year2025.totalElevationGainM, 0.001)
        assertEquals(5000, year2025.totalNetDurationSec)
    }

    // ---------------------------------------------------------------------
    // Best trio
    // ---------------------------------------------------------------------

    @Test
    fun `longestRideEntry picks the session with the greatest distance`() = runTest(testDispatcher) {
        val sessions = listOf(
            session(1, midYearInstant(2024), distanceKm = 30.0),
            session(2, midYearInstant(2024), distanceKm = 90.0),
            session(3, midYearInstant(2024), distanceKm = 60.0)
        )
        val vm = buildViewModel(sessions = sessions)

        val state = vm.uiState.first { !it.isLoading }

        val longest = state.bestTrio.first { it.label == "Longest ride" }
        assertEquals(2L, longest.sessionId)
        assertEquals(FormatUtils.formatDistance(90.0), longest.value)
    }

    @Test
    fun `longestDurationEntry picks the session with the greatest net duration`() = runTest(testDispatcher) {
        val sessions = listOf(
            session(1, midYearInstant(2024), netDurationSec = 1000),
            session(2, midYearInstant(2024), netDurationSec = 5000),
            session(3, midYearInstant(2024), netDurationSec = 2000)
        )
        val vm = buildViewModel(sessions = sessions)

        val state = vm.uiState.first { !it.isLoading }

        val longestDuration = state.bestTrio.first { it.label == "Longest duration" }
        assertEquals(2L, longestDuration.sessionId)
        assertEquals(FormatUtils.formatDuration(5000), longestDuration.value)
    }

    @Test
    fun `biggestClimbEntry ignores sessions with null elevationGainM rather than treating null as 0`() = runTest(testDispatcher) {
        val sessions = listOf(
            // Highest distance of the three, but no elevation data at all — must not win, and must
            // not be treated as a climb of 0m either.
            session(1, midYearInstant(2024), distanceKm = 200.0, elevationGainM = null),
            session(2, midYearInstant(2024), distanceKm = 10.0, elevationGainM = 50.0),
            session(3, midYearInstant(2024), distanceKm = 20.0, elevationGainM = 30.0)
        )
        val vm = buildViewModel(sessions = sessions)

        val state = vm.uiState.first { !it.isLoading }

        val biggestClimb = state.bestTrio.first { it.label == "Biggest climb" }
        assertEquals(2L, biggestClimb.sessionId)
        assertEquals(FormatUtils.formatElevationGain(50.0), biggestClimb.value)
    }

    // ---------------------------------------------------------------------
    // Distance splits / power curve
    // ---------------------------------------------------------------------

    @Test
    fun `splitEntry picks the minimum time across all best-effort records`() = runTest(testDispatcher) {
        val sessions = listOf(
            session(1, midYearInstant(2024)),
            session(2, midYearInstant(2024)),
            session(3, midYearInstant(2024))
        )
        val bestEfforts = listOf(
            bestEffort(1, sessionStart = 1_000L, split25kSec = 3000.0),
            bestEffort(2, sessionStart = 2_000L, split25kSec = 2500.0),
            bestEffort(3, sessionStart = 3_000L, split25kSec = 2800.0)
        )
        val vm = buildViewModel(sessions = sessions, bestEfforts = bestEfforts)

        val state = vm.uiState.first { !it.isLoading }

        val split25k = state.distanceSplits.first { it.label == "25 km" }
        assertEquals(2L, split25k.sessionId)
        assertEquals(FormatUtils.formatDuration(2500), split25k.value)
        assertEquals(FormatUtils.formatDate(Instant.ofEpochMilli(2_000L)), split25k.date)
    }

    @Test
    fun `powerCurvePoint picks the maximum watts across all best-effort records`() = runTest(testDispatcher) {
        val sessions = listOf(
            session(1, midYearInstant(2024)),
            session(2, midYearInstant(2024)),
            session(3, midYearInstant(2024))
        )
        val bestEfforts = listOf(
            bestEffort(1, sessionStart = 1_000L, power1s = 400),
            bestEffort(2, sessionStart = 2_000L, power1s = 900),
            bestEffort(3, sessionStart = 3_000L, power1s = 700)
        )
        val vm = buildViewModel(sessions = sessions, bestEfforts = bestEfforts)

        val state = vm.uiState.first { !it.isLoading }

        val power1s = state.powerCurve.first { it.durationSec == 1 }
        assertEquals(2L, power1s.sessionId)
        assertEquals(900, power1s.watts)
        assertEquals(FormatUtils.formatDate(Instant.ofEpochMilli(2_000L)), power1s.date)
        assertTrue(state.hasAnyPowerCurveData)
    }

    // ---------------------------------------------------------------------
    // Empty states
    // ---------------------------------------------------------------------

    @Test
    fun `empty session list produces an all-null empty state without throwing`() = runTest(testDispatcher) {
        val vm = buildViewModel(sessions = emptyList(), bestEfforts = emptyList())

        val state = vm.uiState.first { !it.isLoading }

        assertFalse(state.hasAnySessions)
        state.bestTrio.forEach {
            assertNull(it.value)
            assertNull(it.sessionId)
            assertNull(it.date)
        }
        assertTrue(state.yearStats.isEmpty())
    }

    @Test
    fun `empty best-efforts list produces null splits and power curve without throwing`() = runTest(testDispatcher) {
        val sessions = listOf(session(1, midYearInstant(2024)))
        val vm = buildViewModel(sessions = sessions, bestEfforts = emptyList())

        val state = vm.uiState.first { !it.isLoading }

        assertTrue(state.hasAnySessions)
        state.distanceSplits.forEach {
            assertNull(it.value)
            assertNull(it.sessionId)
        }
        state.powerCurve.forEach {
            assertNull(it.watts)
            assertNull(it.sessionId)
        }
        assertFalse(state.hasAnyPowerCurveData)
    }
}
