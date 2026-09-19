package com.velometrics.app.ui.screens.alltimestats

import com.velometrics.app.fakes.FakeAllTimeStatsCache
import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the seam introduced by #195: [AllTimeStatsCache] is now a fakeable interface. */
class AllTimeStatsViewModelTest {

    @Test
    fun `uiState mirrors the cache's current value`() {
        val state = AllTimeStatsUiState(
            isLoading = false,
            hasAnySessions = true,
            yearStats = listOf(
                YearStat(
                    year = 2026,
                    rideCount = 42,
                    totalDistanceKm = 1000.0,
                    totalElevationGainM = 5000.0,
                    totalNetDurationSec = 360000
                )
            )
        )
        val vm = AllTimeStatsViewModel(FakeAllTimeStatsCache(state))

        assertEquals(state, vm.uiState.value)
    }

    @Test
    fun `uiState reflects later cache emissions`() {
        val cache = FakeAllTimeStatsCache()
        val vm = AllTimeStatsViewModel(cache)

        val updated = AllTimeStatsUiState(isLoading = false, hasAnySessions = true)
        cache.emit(updated)

        assertEquals(updated, vm.uiState.value)
    }
}
