package com.velometrics.app.ui.screens.mapview

import com.velometrics.app.data.location.FakeLocationSource
import com.velometrics.app.data.repository.FakeCyclingSessionRepository
import com.velometrics.app.domain.model.GraphMetadata
import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.LocationFix
import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.domain.model.MapNode
import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.domain.repository.IntervalRepository
import com.velometrics.app.domain.repository.MapGraphRepository
import com.velometrics.app.domain.repository.RepeatedIntervalRepository
import com.velometrics.app.domain.service.LocationException
import com.velometrics.app.util.CyclingConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun buildViewModel(locationSource: FakeLocationSource) = MapViewViewModel(
        mapGraphRepository = FakeMapGraphRepository(),
        cyclingSessionRepository = FakeCyclingSessionRepository(),
        intervalRepository = FakeIntervalRepository(),
        repeatedIntervalRepository = FakeRepeatedIntervalRepository(),
        locationSource = locationSource,
    )

    private fun coarseFix(lat: Double = 0.0, lon: Double = 0.0, accuracyM: Float = 50f) =
        LocationFix(lat, lon, accuracyM, Instant.now())

    @Test
    fun `tracking keeps receiving fixes after a poor accuracy reading instead of stopping`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        fakeLocation.emitFix(coarseFix(accuracyM = 500f))
        runCurrent()
        assertEquals(500f, vm.locationAccuracy.value)

        fakeLocation.emitFix(coarseFix(accuracyM = 50f))
        runCurrent()
        assertEquals(50f, vm.locationAccuracy.value)
    }

    @Test
    fun `accuracy emissions are reflected in locationAccuracy`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        fakeLocation.emitFix(coarseFix(accuracyM = 100f))
        runCurrent()  // process the fix; do NOT advance virtual time to the timeout
        assertEquals(100f, vm.locationAccuracy.value)

        fakeLocation.emitFix(coarseFix(accuracyM = 45f))
        runCurrent()
        assertEquals(45f, vm.locationAccuracy.value)
    }

    @Test
    fun `5s display throttle prevents rapid currentLocation updates`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        // First fix — always updates currentLocation (lastDotUpdateMs is 0)
        fakeLocation.emitFix(coarseFix(lat = 10.0, lon = 20.0))
        advanceUntilIdle()
        val firstLocation = vm.currentLocation.value
        assertEquals(10.0, firstLocation!!.latitude, 0.0001)

        // Second fix emitted immediately — within 5 s throttle window; should NOT update
        fakeLocation.emitFix(coarseFix(lat = 30.0, lon = 40.0))
        advanceUntilIdle()
        assertEquals(firstLocation, vm.currentLocation.value)
    }

    @Test
    fun `LocationException NoProvider does not crash`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setSubscriptionException(LocationException.NoProvider)
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        advanceUntilIdle()

        assertNull(vm.currentLocation.value)
    }

    @Test
    fun `LocationException PermissionDenied does not crash`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setSubscriptionException(LocationException.PermissionDenied)
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        advanceUntilIdle()

        assertNull(vm.currentLocation.value)
    }

    @Test
    fun `currentLocation is null before first fix`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        val vm = buildViewModel(fakeLocation)

        assertNull(vm.currentLocation.value)
    }

    @Test
    fun `selectPoiChip activates, switches, and deactivates on re-tap`() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeLocationSource())

        assertNull(vm.activePoiChip.value)

        vm.selectPoiChip(MapViewViewModel.ALL_POIS_CHIP)
        assertEquals(MapViewViewModel.ALL_POIS_CHIP, vm.activePoiChip.value)

        // Re-tapping the active chip deactivates
        vm.selectPoiChip(MapViewViewModel.ALL_POIS_CHIP)
        assertNull(vm.activePoiChip.value)

        // Activating a category then switching to another
        vm.selectPoiChip("Cafe")
        assertEquals("Cafe", vm.activePoiChip.value)
        vm.selectPoiChip("Park")
        assertEquals("Park", vm.activePoiChip.value)
    }
}

// ---------------------------------------------------------------------------
// Test doubles
// ---------------------------------------------------------------------------

private class FakeMapGraphRepository : MapGraphRepository {
    override fun getAllEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllNodes(): Flow<List<MapNode>> = flowOf(emptyList())
    override suspend fun getEdgesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<MapEdge>()
    override suspend fun getNodesNear(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double) = emptyList<MapNode>()
    override fun getTraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getUntraversedEdges(): Flow<List<MapEdge>> = flowOf(emptyList())
    override fun getAllPois(): Flow<List<Poi>> = flowOf(emptyList())
    override fun getMetadata(): GraphMetadata? = null
    override suspend fun loadGraph(nodes: List<MapNode>, edges: List<MapEdge>, metadata: GraphMetadata) {}
    override suspend fun loadPois(pois: List<Poi>) {}
    override fun isLoaded(): Boolean = false
}

private class FakeIntervalRepository : IntervalRepository {
    override suspend fun insertInterval(interval: IntervalSession): Long = 0L
    override suspend fun insertIntervals(intervals: List<IntervalSession>): List<Long> = emptyList()
    override suspend fun updateInterval(interval: IntervalSession) {}
    override fun getIntervalsForSession(sessionId: Long): Flow<List<IntervalSession>> = flowOf(emptyList())
    override fun getAllIntervals(): Flow<List<IntervalSession>> = flowOf(emptyList())
}

private class FakeRepeatedIntervalRepository : RepeatedIntervalRepository {
    override fun getAllRepeatedIntervals(): Flow<List<RepeatedInterval>> = flowOf(emptyList())
    override fun getRepeatedIntervalById(id: Long): Flow<RepeatedInterval?> = flowOf(null)
    override suspend fun getAllRepeatedIntervalsList(): List<RepeatedInterval> = emptyList()
    override suspend fun saveRepeatedInterval(interval: RepeatedInterval): Long = 0L
    override suspend fun renameRepeatedInterval(id: Long, newName: String) {}
    override suspend fun deleteRepeatedIntervalsByIds(ids: List<Long>) {}
    override suspend fun deleteAll() {}
}
