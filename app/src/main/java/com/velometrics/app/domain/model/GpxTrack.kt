package com.velometrics.app.domain.model

import org.maplibre.android.geometry.LatLng

data class GpxTrack(val name: String?, val points: List<LatLng>)
