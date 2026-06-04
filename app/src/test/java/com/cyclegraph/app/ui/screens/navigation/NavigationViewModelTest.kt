package com.cyclegraph.app.ui.screens.navigation

import com.cyclegraph.app.data.location.FakeLocationSource
import com.cyclegraph.app.domain.model.GraphMetadata
import com.cyclegraph.app.domain.model.LocationFix
import com.cyclegraph.app.domain.model.MapEdge
import com.cyclegraph.app.domain.model.MapNode
import com.cyclegraph.app.domain.model.Poi
import com.cyclegraph.app.domain.repository.MapGraphRepository
import com.cyclegraph.app.domain.service.NavigationRouteHolder
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
import java.time.Instant

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
        navigationRouteHolder = NavigationRouteHolder(),
        locationSource = locationSource,
    )

    @Test
    fun `setUserPositionFromPermission false falls back to track start`() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val trackStart = LatLng(51.0, 0.0)
        vm.loadGpxFromPoints(listOf(trackStart, LatLng(52.0, 0.1)), "Test Route")
        advanceUntilIdle()

        vm.setUserPositionFromPermission(false)

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
