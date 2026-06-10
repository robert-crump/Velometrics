package com.velometrics.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.PedalBike
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.graphics.vector.ImageVector
import com.velometrics.app.R

/** Maps POI categories to their icon, shared between map circles and filter chips. */
object PoiIcons {

    /** Icon shown on POI category filter chips (Material Icons Extended). */
    fun forCategory(category: String): ImageVector = when (category) {
        "bakery" -> Icons.Filled.BakeryDining
        "bicycle" -> Icons.Filled.PedalBike
        "cafe" -> Icons.Filled.LocalCafe
        "drinking_water" -> Icons.Filled.LocalDrink
        "fast_food", "friture" -> Icons.Filled.Fastfood
        "fuel" -> Icons.Filled.LocalGasStation
        "restaurant" -> Icons.Filled.Restaurant
        "vending_machine" -> Icons.Filled.SmartToy
        else -> Icons.Filled.Place
    }

    /** Drawable resource rendered (white, on a dark POI circle) on the map. */
    fun drawableResForCategory(category: String): Int = when (category) {
        "bakery" -> R.drawable.ic_poi_bakery
        "bicycle" -> R.drawable.ic_poi_bicycle
        "cafe" -> R.drawable.ic_poi_cafe
        "drinking_water" -> R.drawable.ic_poi_drinking_water
        "fast_food", "friture" -> R.drawable.ic_poi_fast_food
        "fuel" -> R.drawable.ic_poi_fuel
        "restaurant" -> R.drawable.ic_poi_restaurant
        "vending_machine" -> R.drawable.ic_poi_vending_machine
        else -> R.drawable.ic_poi_cafe
    }

    /** All categories with a registered map icon, used to pre-register bitmaps with the map style. */
    val ALL_CATEGORIES = listOf(
        "bakery", "bicycle", "cafe", "drinking_water", "fast_food", "friture", "fuel", "restaurant", "vending_machine"
    )

    /** Image id registered via [org.maplibre.android.maps.Style.addImage] for a category. */
    fun mapIconId(category: String): String = "poi-icon-${normalizedIconKey(category)}"

    /** Collapses categories that share an icon (e.g. friture/fast_food) to a single registration key. */
    fun normalizedIconKey(category: String): String = when (category) {
        "friture" -> "fast_food"
        else -> category
    }
}
