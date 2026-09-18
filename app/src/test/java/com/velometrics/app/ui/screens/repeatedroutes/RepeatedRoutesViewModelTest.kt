package com.velometrics.app.ui.screens.repeatedroutes

import com.velometrics.app.domain.model.RepeatedRoute
import com.velometrics.app.domain.service.RouteClusteringService
import com.velometrics.app.fakes.FakeRepeatedRoutesCache
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

/** Proves the seam introduced by #195: [RepeatedRoutesCache] is now a fakeable interface. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepeatedRoutesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun route(id: Long, name: String) = RepeatedRoute(
        id = id,
        name = name,
        sessions = emptyList(),
        representativeTrack = null
    )

    @Test
    fun `routes mirrors the cache's current value`() = runTest(testDispatcher) {
        val cache = FakeRepeatedRoutesCache(initialRoutes = listOf(route(1, "Loop")))
        val vm = RepeatedRoutesViewModel(cache, mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(listOf(route(1, "Loop")), vm.routes.value)
    }

    @Test
    fun `isLoading mirrors the cache`() = runTest(testDispatcher) {
        val cache = FakeRepeatedRoutesCache(initialIsLoading = true)
        val vm = RepeatedRoutesViewModel(cache, mockk(relaxed = true))
        advanceUntilIdle()

        assertEquals(true, vm.isLoading.value)
    }

    @Test
    fun `refresh runs clustering and clears isRefreshing when done`() = runTest(testDispatcher) {
        val clusteringService = mockk<RouteClusteringService>()
        coEvery { clusteringService.runClustering() } returns Unit
        val vm = RepeatedRoutesViewModel(FakeRepeatedRoutesCache(), clusteringService)

        vm.refresh()
        advanceUntilIdle()

        coVerify(exactly = 1) { clusteringService.runClustering() }
        assertFalse(vm.isRefreshing.value)
    }
}
