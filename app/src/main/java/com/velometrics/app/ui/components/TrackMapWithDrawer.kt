package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.velometrics.app.util.CyclingConstants.TRACK_FIT_PADDING
import com.velometrics.app.util.GpsTrackParser
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Full-screen interactive map that fits [points] to the viewport above a [PullUpDrawer],
 * re-centering whenever [drawerFraction] changes (e.g. as the drawer is dragged). Shows a
 * "No GPS data available" placeholder when [points] is empty.
 *
 * [overlayContent] renders extra Compose UI on top of the map (e.g. a legend); callers that need
 * to add map-style-level layers (rendered on the map itself, not as Compose UI) can do so from
 * [onMapReady], which fires after the track has been added and the camera has made its initial fit.
 */
@Composable
fun TrackMapWithDrawer(
    points: List<LatLng>,
    drawerFraction: Float,
    trackId: String,
    trackColor: String = "#2196F3",
    onMapReady: (MapLibreMap, Style) -> Unit = { _, _ -> },
    overlayContent: @Composable BoxWithConstraintsScope.() -> Unit = {}
) {
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val boundsRef = remember { mutableStateOf<LatLngBounds?>(null) }
    val density = LocalDensity.current

    if (points.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Map,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No GPS data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val mapHeightPx = with(density) { maxHeight.toPx() }

            // Re-center track whenever the drawer snaps to a new position
            LaunchedEffect(drawerFraction) {
                if (drawerFraction >= 1.0f) return@LaunchedEffect
                val map = mapRef.value ?: return@LaunchedEffect
                val bounds = boundsRef.value ?: return@LaunchedEffect
                val bottomPx = (mapHeightPx * drawerFraction).toInt() + TRACK_FIT_PADDING
                map.animateCamera(
                    CameraUpdateFactory.newLatLngBounds(
                        bounds,
                        TRACK_FIT_PADDING, TRACK_FIT_PADDING, TRACK_FIT_PADDING, bottomPx
                    )
                )
            }

            ComposableMapView(
                modifier = Modifier.fillMaxSize(),
                gesturesEnabled = true,
                onMapReady = { map, style ->
                    mapRef.value = map
                    MapTrackRenderer.addTrack(style, trackId, points, trackColor)
                    val bounds = GpsTrackParser.computeBounds(points)
                    boundsRef.value = bounds
                    if (bounds != null) {
                        val bottomPx = (mapHeightPx * drawerFraction).toInt() + TRACK_FIT_PADDING
                        map.moveCamera(
                            CameraUpdateFactory.newLatLngBounds(
                                bounds,
                                TRACK_FIT_PADDING, TRACK_FIT_PADDING, TRACK_FIT_PADDING, bottomPx
                            )
                        )
                    }
                    onMapReady(map, style)
                }
            )

            overlayContent()
        }
    }
}
