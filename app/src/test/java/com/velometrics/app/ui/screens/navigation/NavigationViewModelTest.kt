package com.velometrics.app.ui.screens.navigation

import com.velometrics.app.data.location.FakeLocationSource
import com.velometrics.app.data.preferences.UserSettingsRepository
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.GpxTrack
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.service.FastWayHomeService
import com.velometrics.app.domain.service.LocationException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.maplibre.android.geometry.LatLng

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(
        locationSource: FakeLocationSource = FakeLocationSource(),
        pois: List<Poi> = emptyList(),
    ) = NavigationViewModel(
        mapGraphRepository = FakeMapGraphRepository(pois),
        locationSource = locationSource,
        fastWayHomeService = mockk<FastWayHomeService>().also {
            coEvery { it.findFastWayHome(any()) } returns null
        },
        userSettingsRepository = mockk<UserSettingsRepository>().also {
            every { it.homeLat } returns flowOf(0.0)
            every { it.homeLon } returns flowOf(0.0)
        },
    )

    @Test
    fun `refreshUserPosition falls back to track start when GPS unavailable`() = runTest(testDispatcher) {
        val locationSource = FakeLocationSource().apply {
            setLastKnown(null)
            setSubscriptionException(LocationException.PermissionDenied)
        }
        val vm = buildViewModel(locationSource = locationSource)
        val trackStart = LatLng(51.0, 0.0)
        vm.loadGpxFromTrack(GpxTrack(name = "Test", points = listOf(trackStart, LatLng(52.0, 0.1))))
        advanceUntilIdle()

        vm.refreshUserPosition()
        advanceUntilIdle()

        assertNotNull(vm.userPosition.value)
        assertEquals(51.0, vm.userPosition.value!!.latitude, 0.0001)
        assertEquals(0.0, vm.userPosition.value!!.longitude, 0.0001)
    }
}

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

private class FakeMapGraphRepository(private val poiList: List<Poi> = emptyList()) : MapGraphRepository {
    override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
    override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllPois(): Flow<List<Poi>> = flowOf(poiList)
    override fun getMetadata(): GraphMetadata? = null
    override suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata) {}
    override suspend fun loadPois(pois: List<Poi>) {}
    override fun isLoaded(): Boolean = false
}
