package com.velometrics.app.ui.screens.navigation

import com.velometrics.app.domain.model.Poi
import com.velometrics.app.domain.model.PoiWithDistances
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PoiSelectionStateTest {

    private fun poi(id: String) = Poi(
        poiId = id, name = "Poi $id", category = "cafe", cuisine = null,
        lat = 0.0, lon = 0.0, openingHours = null
    )

    private fun poiWD(id: String) = PoiWithDistances(
        poi = poi(id), airDistanceM = 100.0, trackDistanceM = null
    )

    @Test
    fun `None has no selection and no pending zoom`() {
        assertNull(PoiSelectionState.None.selected)
        assertNull(PoiSelectionState.None.pendingZoomTo)
    }

    @Test
    fun `pickFromList sets selected and pending zoom to the picked poi`() {
        val wd = poiWD("a")
        val state = PoiSelectionState.None.pickFromList(wd)

        assertEquals(wd.poi, state.selected?.poi)
        assertEquals(wd, state.selected?.popup)
        assertEquals(wd.poi, state.pendingZoomTo)
    }

    @Test
    fun `pickFromMap sets selected but does not set pending zoom`() {
        val wd = poiWD("a")
        val state = PoiSelectionState.None.pickFromMap(wd)

        assertEquals(wd.poi, state.selected?.poi)
        assertEquals(wd, state.selected?.popup)
        assertNull(state.pendingZoomTo)
    }

    @Test
    fun `pickFromMap after pickFromList preserves pending zoom from the list pick`() {
        // Pre-existing behavior: the screen consumes pendingZoomTo via a LaunchedEffect;
        // if the user taps a map poi before that runs, the camera still flies to A.
        val a = poiWD("a")
        val b = poiWD("b")
        val state = PoiSelectionState.None.pickFromList(a).pickFromMap(b)

        assertEquals(b.poi, state.selected?.poi)
        assertEquals(a.poi, state.pendingZoomTo)
    }

    @Test
    fun `consumeCameraMove clears only the pending zoom`() {
        val wd = poiWD("a")
        val state = PoiSelectionState.None.pickFromList(wd).consumeCameraMove()

        assertEquals(wd.poi, state.selected?.poi)
        assertNull(state.pendingZoomTo)
    }

    @Test
    fun `dismiss clears selection and pending zoom`() {
        val state = PoiSelectionState.None.pickFromList(poiWD("a")).dismiss()

        assertEquals(PoiSelectionState.None, state)
    }

    @Test
    fun `dismiss on None is idempotent`() {
        assertSame(PoiSelectionState.None, PoiSelectionState.None.dismiss().dismiss())
    }

    @Test
    fun `pickFromList replaces a previous selection`() {
        val a = poiWD("a")
        val b = poiWD("b")
        val state = PoiSelectionState.None.pickFromList(a).pickFromList(b)

        assertEquals(b.poi, state.selected?.poi)
        assertEquals(b.poi, state.pendingZoomTo)
    }
}
