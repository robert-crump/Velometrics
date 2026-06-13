package com.velometrics.app.domain.model

import org.maplibre.android.geometry.LatLng

data class GpxTrack(
    val name: String?,
    val points: List<LatLng>,
    /** Elevation in meters per point (same size as [points]), or null if the file has no `<ele>` data. */
    val elevations: List<Double?>? = null
)
