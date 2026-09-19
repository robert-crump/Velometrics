package com.velometrics.app.domain.service

import com.velometrics.app.domain.model.CyclingSession
import com.velometrics.app.domain.model.FtpEntry
import com.velometrics.app.domain.model.FtpHistory
import com.velometrics.app.util.CyclingConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

class TrainingLoadAggregatorTest {

    // The aggregator always walks its daily series through LocalDate.now(), which a test can't
    // inject — so test sessions are placed relative to "today" rather than on fixed calendar
    // dates, keeping the resulting chartPoints series (and its length relative to the 182-day
    // chart window) deterministic regardless of when this test actually runs.
    private val today: LocalDate = LocalDate.now(ZoneId.systemDefault())

    private fun session(
        id: Long,
        day: LocalDate,
        netDurationSec: Int = 3600,
        hasPower: Boolean = false,
        normalizedPower: Int? = null,
        hasHR: Boolean = false,
        hrZoneDistribution: Map<String, Int>? = null
    ) = CyclingSession(
        id = id,
        fileName = "ride$id.fit",
        fileSha1 = "sha$id",
        sessionStart = day.atStartOfDay(ZoneId.systemDefault()).toInstant(),
        sessionEnd = day.atStartOfDay(ZoneId.systemDefault()).toInstant().plusSeconds(netDurationSec.toLong()),
        totalDurationSec = netDurationSec,
        pauseDurationSec = 0,
        netDurationSec = netDurationSec,
        distanceKm = 30.0,
        averagePower = null,
        normalizedPower = normalizedPower,
        fatBurnedGrams = null,
        carbsBurnedGrams = null,
        powerZoneDistribution = null,
        speedHistogram = emptyMap(),
        intervalCount = 0,
        intervalTotalTimeSec = 0,
        gpsQualityPercent = 100.0,
        powerQualityPercent = null,
        hasPower = hasPower,
        hasHR = hasHR,
        hrZoneDistribution = hrZoneDistribution
    )

    // ---------------------------------------------------------------------
    // Per-ride load score (single ride, on "today" so exactly one chart point exists)
    // ---------------------------------------------------------------------

    @Test
    fun `power ride at IF 1 over 1 hour scores TSS of 100`() {
        val ftp = 250
        val sessions = listOf(session(1, today, hasPower = true, normalizedPower = ftp))

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(ftp))

        assertEquals(100.0, state.chartPoints.single().load, 0.001)
    }

    @Test
    fun `each ride is scored against the FTP in force on its ride date`() {
        // Retesting from 200 to 250 W two days ago must not rescore the older ride.
        val retestDay = today.minusDays(2)
        val history = FtpHistory(listOf(FtpEntry(null, 200), FtpEntry(retestDay, 250)))
        val sessions = listOf(
            session(1, today.minusDays(3), hasPower = true, normalizedPower = 200),
            session(2, today, hasPower = true, normalizedPower = 250)
        )

        val state = TrainingLoadAggregator.buildUiState(sessions, history)

        assertEquals(listOf(100.0, 0.0, 0.0, 100.0), state.chartPoints.map { it.load })
    }

    @Test
    fun `HR-only ride uses zone-weighted minutes scaled by the calibration constant`() {
        // All 3600 samples (1 per second, matching netDurationSec) in Zone 3: 60 weighted
        // minutes * multiplier 3.0 = 180, scaled by 0.36 -> 64.8.
        val sessions = listOf(
            session(1, today, hasHR = true, hrZoneDistribution = mapOf("Zone 3" to 3600))
        )

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(250))

        assertEquals(64.8, state.chartPoints.single().load, 0.001)
    }

    @Test
    fun `HR-only ride with mixed zones weights each zone separately`() {
        // 2400s (40min) Zone 1 + 1200s (20min) Zone 5: 40*1 + 20*5 = 140 weighted minutes,
        // scaled by 0.36 -> 50.4.
        val sessions = listOf(
            session(1, today, hasHR = true, hrZoneDistribution = mapOf("Zone 1" to 2400, "Zone 5" to 1200))
        )

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(250))

        assertEquals(50.4, state.chartPoints.single().load, 0.001)
    }

    @Test
    fun `ride with neither power nor HR scores zero but still counts as a day`() {
        val sessions = listOf(session(1, today))

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(250))

        assertTrue(state.hasAnySessions)
        assertEquals(0.0, state.chartPoints.single().load, 0.001)
    }

    @Test
    fun `multiple rides on the same day are summed into one bucket`() {
        val ftp = 250
        val sessions = listOf(
            session(1, today, hasPower = true, normalizedPower = ftp),
            session(2, today, hasPower = true, normalizedPower = ftp)
        )

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(ftp))

        assertEquals(1, state.chartPoints.size)
        assertEquals(200.0, state.chartPoints.single().load, 0.001)
    }

    // ---------------------------------------------------------------------
    // Daily bucketing / gap-filling
    // ---------------------------------------------------------------------

    @Test
    fun `gap days between rides are filled with zero load and no dates are skipped`() {
        val ftp = 250
        val sessions = listOf(
            session(1, today.minusDays(3), hasPower = true, normalizedPower = ftp),
            session(2, today, hasPower = true, normalizedPower = ftp)
        )

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(ftp))

        val dates = state.chartPoints.map { it.date }
        assertEquals(
            listOf(today.minusDays(3), today.minusDays(2), today.minusDays(1), today),
            dates
        )
        assertEquals(0.0, state.chartPoints[1].load, 0.001)
        assertEquals(0.0, state.chartPoints[2].load, 0.001)
    }

    // ---------------------------------------------------------------------
    // EMA math
    // ---------------------------------------------------------------------

    @Test
    fun `CTL and ATL follow the standard 42-7 day EMA recurrence, TSB uses the prior day's values`() {
        // A single 100-load day (today-2) followed by two rest days (today-1, today).
        // Hand-computed via ctl_n = ctl_(n-1) + (load_n - ctl_(n-1))/42, atl_n analogous with
        // /7, tsb_n = ctl_(n-1) - atl_(n-1) (i.e. before day n's own update).
        val ftp = 100
        val sessions = listOf(
            session(1, today.minusDays(2), hasPower = true, normalizedPower = ftp) // TSS = 1^2 * 1h * 100 = 100
        )

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(ftp))

        assertEquals(3, state.chartPoints.size)

        val day1 = state.chartPoints[0]
        assertEquals(0.0, day1.tsb, 0.000001)
        assertEquals(100.0 / 42.0, day1.ctl, 0.000001)
        assertEquals(100.0 / 7.0, day1.atl, 0.000001)

        val day2 = state.chartPoints[1]
        assertEquals(day1.ctl - day1.atl, day2.tsb, 0.000001)
        assertEquals(day1.ctl * 41.0 / 42.0, day2.ctl, 0.000001)
        assertEquals(day1.atl * 6.0 / 7.0, day2.atl, 0.000001)

        val day3 = state.chartPoints[2]
        assertEquals(day2.ctl - day2.atl, day3.tsb, 0.000001)
        assertEquals(day2.ctl * 41.0 / 42.0, day3.ctl, 0.000001)
        assertEquals(day2.atl * 6.0 / 7.0, day3.atl, 0.000001)

        assertEquals(day3.ctl, state.currentCtl, 0.000001)
        assertEquals(day3.atl, state.currentAtl, 0.000001)
        assertEquals(day3.tsb, state.currentTsb, 0.000001)
    }

    // ---------------------------------------------------------------------
    // Chart-window slicing
    // ---------------------------------------------------------------------

    @Test
    fun `chart window truncates to the trailing window but current values use the full series`() {
        val ftp = 250
        val firstDay = today.minusYears(2)
        // One ride at the very start of a >182-day history, no rides since.
        val sessions = listOf(session(1, firstDay, hasPower = true, normalizedPower = ftp))

        val state = TrainingLoadAggregator.buildUiState(sessions, FtpHistory.constant(ftp))

        val fullSeriesLength = ChronoUnit.DAYS.between(firstDay, today) + 1
        assertTrue(fullSeriesLength > CyclingConstants.TRAINING_LOAD_CHART_WINDOW_DAYS)
        assertEquals(CyclingConstants.TRAINING_LOAD_CHART_WINDOW_DAYS, state.chartPoints.size)
        // Two years of decay should leave CTL/ATL negligible, not the (much larger) value the
        // ride would still show if currentCtl were mistakenly derived from just the windowed
        // (i.e. truncated) series instead of the full one.
        assertTrue(state.currentCtl < 0.01)
    }

    // ---------------------------------------------------------------------
    // Empty state
    // ---------------------------------------------------------------------

    @Test
    fun `no sessions yields hasAnySessions false and an empty chart with no exception`() {
        val state = TrainingLoadAggregator.buildUiState(emptyList(), FtpHistory.constant(250))

        assertFalse(state.hasAnySessions)
        assertFalse(state.isLoading)
        assertEquals(0.0, state.currentCtl, 0.001)
        assertEquals(0.0, state.currentAtl, 0.001)
        assertEquals(0.0, state.currentTsb, 0.001)
        assertTrue(state.chartPoints.isEmpty())
    }
}
