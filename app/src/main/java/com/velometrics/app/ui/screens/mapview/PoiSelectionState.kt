package com.velometrics.app.ui.screens.mapview

import com.velometrics.app.domain.model.PoiWithDistances

/**
 * What POI-layer selection the user has made on the Map View screen: which category chip is
 * active (drives whether/which POIs are shown at all) and which single POI, if any, is popped
 * up after a tap. Replaces two `MutableStateFlow`s that were previously updated independently.
 */
data class PoiSelectionState(
    val activeChip: String? = null,
    val selected: PoiWithDistances? = null,
) {
    companion object {
        val None = PoiSelectionState()
    }

    /** Re-selecting the active chip deactivates it; any popped-up POI is left untouched. */
    fun selectChip(chip: String): PoiSelectionState =
        copy(activeChip = if (chip == activeChip) null else chip)

    fun selectPoi(poiWD: PoiWithDistances): PoiSelectionState = copy(selected = poiWD)

    fun dismissPoi(): PoiSelectionState = copy(selected = null)
}
