package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.GeoBounds
import com.velometrics.app.domain.model.GeoPoint
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/** Conversions between the domain's map-library-free geo types and MapLibre's own. */

fun GeoPoint.toLatLng(): LatLng = LatLng(lat, lon)

fun LatLng.toGeoPoint(): GeoPoint = GeoPoint(latitude, longitude)

fun LatLngBounds.toGeoBounds(): GeoBounds =
    GeoBounds(south = latitudeSouth, west = longitudeWest, north = latitudeNorth, east = longitudeEast)
