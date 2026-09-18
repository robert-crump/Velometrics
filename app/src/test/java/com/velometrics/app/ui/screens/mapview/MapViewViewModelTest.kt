package com.velometrics.app.ui.screens.mapview

import com.velometrics.app.domain.model.LocationFix
import com.velometrics.app.domain.service.LocationException
import com.velometrics.app.fakes.FakeCyclingSessionRepository
import com.velometrics.app.fakes.FakeIntervalRepository
import com.velometrics.app.fakes.FakeLocationSource
import com.velometrics.app.fakes.FakeMapGraphRepository
import com.velometrics.app.fakes.FakeRepeatedIntervalRepository
import com.velometrics.app.util.CyclingConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        assertEquals(10.0, firstLocation!!.lat, 0.0001)

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
    fun `showLocatingIndicator is false before subscription starts`() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeLocationSource())

        assertEquals(false, vm.showLocatingIndicator.value)
    }

    @Test
    fun `showLocatingIndicator is true while subscription is active and no fix has arrived`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        runCurrent()

        assertEquals(true, vm.showLocatingIndicator.value)
        assertNull(vm.currentLocation.value)
    }

    @Test
    fun `showLocatingIndicator turns false as soon as a fix arrives`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        runCurrent()
        assertEquals(true, vm.showLocatingIndicator.value)

        fakeLocation.emitFix(coarseFix())
        advanceUntilIdle()

        assertEquals(false, vm.showLocatingIndicator.value)
    }

    @Test
    fun `showLocatingIndicator stays false when permission is denied`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setSubscriptionException(LocationException.PermissionDenied)
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        advanceUntilIdle()

        assertEquals(false, vm.showLocatingIndicator.value)
    }

    @Test
    fun `showLocatingIndicator stays false when no provider is available`() = runTest(testDispatcher) {
        val fakeLocation = FakeLocationSource()
        fakeLocation.setSubscriptionException(LocationException.NoProvider)
        val vm = buildViewModel(fakeLocation)

        vm.startLocationUpdates()
        advanceUntilIdle()

        assertEquals(false, vm.showLocatingIndicator.value)
    }

    @Test
    fun `selectPoiChip activates, switches, and deactivates on re-tap`() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeLocationSource())

        assertNull(vm.poiSelection.value.activeChip)

        vm.selectPoiChip(MapViewViewModel.ALL_POIS_CHIP)
        assertEquals(MapViewViewModel.ALL_POIS_CHIP, vm.poiSelection.value.activeChip)

        // Re-tapping the active chip deactivates
        vm.selectPoiChip(MapViewViewModel.ALL_POIS_CHIP)
        assertNull(vm.poiSelection.value.activeChip)

        // Activating a category then switching to another
        vm.selectPoiChip("Cafe")
        assertEquals("Cafe", vm.poiSelection.value.activeChip)
        vm.selectPoiChip("Park")
        assertEquals("Park", vm.poiSelection.value.activeChip)
    }

    @Test
    fun `selecting a chip leaves an already popped-up POI untouched`() = runTest(testDispatcher) {
        val vm = buildViewModel(FakeLocationSource())
        val poi = com.velometrics.app.domain.model.Poi(
            poiId = "1", name = "Cafe", category = "Cafe", cuisine = null,
            lat = 1.0, lon = 1.0, openingHours = null
        )

        vm.selectPoiFromMap(poi)
        assertEquals(poi, vm.poiSelection.value.selected?.poi)

        vm.selectPoiChip("Park")
        assertEquals(poi, vm.poiSelection.value.selected?.poi)

        vm.dismissPoi()
        assertNull(vm.poiSelection.value.selected)
    }
}
