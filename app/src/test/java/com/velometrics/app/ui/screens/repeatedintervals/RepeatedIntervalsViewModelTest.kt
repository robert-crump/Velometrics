package com.velometrics.app.ui.screens.repeatedintervals

import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.service.IntervalClusteringService
import com.velometrics.app.fakes.FakeRepeatedIntervalsCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/** Proves the seam introduced by #195: [RepeatedIntervalsCache] is now a fakeable interface. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepeatedIntervalsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun interval(id: Long, name: String) = RepeatedInterval(
        id = id,
        name = name,
        intervals = emptyList(),
        edges = emptyList(),
        startLat = 0.0,
        startLon = 0.0,
        endLat = 0.0,
        endLon = 0.0,
        distanceM = 1000.0
    )

    @Test
    fun `repeatedIntervals mirrors the cache's current value`() = runTest(testDispatcher) {
        val cache = FakeRepeatedIntervalsCache(initialIntervals = listOf(interval(1, "Climb")))
        val vm = RepeatedIntervalsViewModel(cache, mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(listOf(interval(1, "Climb")), vm.repeatedIntervals.value)
    }

    @Test
    fun `isLoading mirrors the cache`() = runTest(testDispatcher) {
        val cache = FakeRepeatedIntervalsCache(initialIsLoading = true)
        val vm = RepeatedIntervalsViewModel(cache, mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(true, vm.isLoading.value)
    }

    @Test
    fun `refresh runs clustering and clears isRefreshing when done`() = runTest(testDispatcher) {
        val clusteringService = mockk<IntervalClusteringService>()
        coEvery { clusteringService.runClustering() } returns Unit
        val vm = RepeatedIntervalsViewModel(FakeRepeatedIntervalsCache(), clusteringService)

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { clusteringService.runClustering() }
        assertFalse(vm.isRefreshing.value)
    }
}
