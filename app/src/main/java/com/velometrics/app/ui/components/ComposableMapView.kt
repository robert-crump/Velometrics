package com.velometrics.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import com.velometrics.app.util.CyclingConstants.CARTO_DARK_STYLE_URL
import com.velometrics.app.util.CyclingConstants.DEFAULT_MAP_ZOOM
import com.velometrics.app.util.CyclingConstants.HOME_LAT
import com.velometrics.app.util.CyclingConstants.HOME_LON

@Composable
fun ComposableMapView(
    modifier: Modifier = Modifier,
    initialCenter: LatLng = LatLng(HOME_LAT, HOME_LON),
    initialZoom: Double = DEFAULT_MAP_ZOOM,
    styleSource: String = CARTO_DARK_STYLE_URL,
    gesturesEnabled: Boolean = true,
    onMapReady: (MapLibreMap, Style) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    remember { MapLibre.getInstance(context) }

    val mapView = remember {
        MapView(context).apply {
            onCreate(null)
            getMapAsync { map ->
                map.cameraPosition = CameraPosition.Builder()
                    .target(initialCenter)
                    .zoom(initialZoom)
                    .build()

                map.uiSettings.apply {
                    isRotateGesturesEnabled = gesturesEnabled
                    isScrollGesturesEnabled = gesturesEnabled
                    isZoomGesturesEnabled = gesturesEnabled
                    isTiltGesturesEnabled = gesturesEnabled
                }

                val styleBuilder = if (styleSource.startsWith("http")) {
                    Style.Builder().fromUri(styleSource)
                } else {
                    Style.Builder().fromJson(styleSource)
                }

                map.setStyle(styleBuilder) { style ->
                    onMapReady(map, style)
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        var alreadyDestroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> {
                    if (!alreadyDestroyed) {
                        alreadyDestroyed = true
                        mapView.onDestroy()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!alreadyDestroyed) {
                alreadyDestroyed = true
                mapView.onDestroy()
            }
        }
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}
