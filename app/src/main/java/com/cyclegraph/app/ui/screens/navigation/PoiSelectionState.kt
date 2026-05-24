package com.cyclegraph.app.ui.screens.navigation

import com.cyclegraph.app.domain.model.Poi
import com.cyclegraph.app.domain.model.PoiWithDistances

data class PoiSelectionState(
    val selected: Selected? = null,
    val pendingZoomTo: Poi? = null,
) {
    data class Selected(val poi: Poi, val popup: PoiWithDistances)

    fun pickFromList(poiWD: PoiWithDistances): PoiSelectionState =
        copy(selected = Selected(poiWD.poi, poiWD), pendingZoomTo = poiWD.poi)

    fun pickFromMap(poiWD: PoiWithDistances): PoiSelectionState =
        copy(selected = Selected(poiWD.poi, poiWD))

    fun dismiss(): PoiSelectionState = None

    fun consumeCameraMove(): PoiSelectionState = copy(pendingZoomTo = null)

    companion object {
        val None = PoiSelectionState()
    }
}
