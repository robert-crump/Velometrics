package com.cyclegraph.app.domain.service

import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NavigationRouteHolder @Inject constructor() {
    var pendingRoute: List<LatLng>? = null
    var pendingRouteName: String? = null
}
