package com.cyclegraph.app.ui.screens.navigation

import com.cyclegraph.app.data.location.FakeLocationSource
import com.cyclegraph.app.domain.model.GraphMetadata
import com.cyclegraph.app.domain.model.LocationFix
import com.cyclegraph.app.domain.model.MapEdge
import com.cyclegraph.app.domain.model.MapNode
import com.cyclegraph.app.domain.model.Poi
import com.cyclegraph.app.domain.repository.MapGraphRepository
import com.cyclegraph.app.domain.service.NavigationRouteHolder
import com.cyclegraph.app.util.CyclingConstants
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
import org.junit.Assert.assertNull
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

    private fun roughFix(lat: Double = 1.0, lon: Double = 2.0) =
        LocationFix(lat, lon, CyclingConstants.GPS_ROUGH_FIX_ACCURACY_M - 1f, Instant.now())

    // -------------------------------------------------------------------------
    // fetchRoughGpsPosition: cached lastKnownFix path
    // -------------------------------------------------------------------------

    @Test
    fun `fetchRoughGpsPosition uses cached lastKnownFix when accurate enough`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setLastKnown(roughFix(lat = 10.0, lon = 20.0))
        val vm = buildViewModel(fakeLocation)

        vm.fetchPoisNearbyByRadius()
        advanceUntilIdle()

        // Position comes from lastKnownFix, not from fixes() channel
        assertEquals(10.0, vm.userPosition.value!!.latitude, 0.0001)
        assertEquals(20.0, vm.userPosition.value!!.longitude, 0.0001)
    }

    // -------------------------------------------------------------------------
    // fetchRoughGpsPosition: falls back to fixes() stream
    // -------------------------------------------------------------------------

    @Test
    fun `fetchRoughGpsPosition falls back to first fresh fix when lastKnownFix is null`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setLastKnown(null)
        fakeLocation.emitFix(roughFix(lat = 51.5, lon = -0.1))
        val vm = buildViewModel(fakeLocation)

        vm.fetchPoisNearbyByRadius()
        advanceUntilIdle()

        assertEquals(51.5, vm.userPosition.value!!.latitude, 0.0001)
        assertEquals(-0.1, vm.userPosition.value!!.longitude, 0.0001)
    }

    // -------------------------------------------------------------------------
    // setUserPositionFromPermission: ALONG_TRACK fallback
    // -------------------------------------------------------------------------

    @Test
    fun `setUserPositionFromPermission false in ALONG_TRACK mode falls back to track start`() = runTest(testDispatcher) {
        val vm = buildViewModel()
        val trackStart = LatLng(51.0, 0.0)
        vm.loadGpxFromPoints(listOf(trackStart, LatLng(52.0, 0.1)), "Test Route")
        advanceUntilIdle()
        vm.setMode(PoiMode.ALONG_TRACK)

        vm.setUserPositionFromPermission(false)

        assertNotNull(vm.userPosition.value)
        assertEquals(51.0, vm.userPosition.value!!.latitude, 0.0001)
        assertEquals(0.0, vm.userPosition.value!!.longitude, 0.0001)
    }

    @Test
    fun `setUserPositionFromPermission false in NEARBY mode does not change userPosition`() = runTest(testDispatcher) {
        val vm = buildViewModel()
        vm.setMode(PoiMode.NEARBY)

        vm.setUserPositionFromPermission(false)

        assertNull(vm.userPosition.value)
    }

    // -------------------------------------------------------------------------
    // refreshDistances: re-runs POI lookup
    // -------------------------------------------------------------------------

    @Test
    fun `refreshDistances in NEARBY mode re-fetches POIs and updates userPosition`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setLastKnown(roughFix(lat = 48.8, lon = 2.3))
        val vm = buildViewModel(fakeLocation)
        vm.setMode(PoiMode.NEARBY)

        vm.refreshDistances()
        advanceUntilIdle()

        assertEquals(48.8, vm.userPosition.value!!.latitude, 0.0001)
        assertEquals(2.3, vm.userPosition.value!!.longitude, 0.0001)
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
