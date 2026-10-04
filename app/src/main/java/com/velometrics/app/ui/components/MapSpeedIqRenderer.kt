package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.SpeedIq
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.addLayerBelowUserMarker
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** One numbered Speed IQ marker; [number] matches the event's row in the card (1 = most time lost). */
data class SpeedIqMarker(val number: Int, val lat: Double, val lon: Double)

/**
 * The markers the Ride Detail map should show (#230): one per listed event, at its lowest-speed
 * point, numbered like the list. Empty when the toggle is off or there's nothing listed.
 */
fun speedIqMarkers(speedIq: SpeedIq?, showOnMap: Boolean): List<SpeedIqMarker> {
    if (!showOnMap || speedIq == null || !speedIq.hasElevation) return emptyList()
    return speedIq.topEvents.mapIndexed { i, event -> SpeedIqMarker(i + 1, event.lat, event.lon) }
}

/** Draws [SpeedIqMarker]s as numbered circles on the track. */
object MapSpeedIqRenderer {

    private const val SOURCE = "speed-iq-source"
    private const val CIRCLE_LAYER = "speed-iq-circle-layer"
    private const val NUMBER_LAYER = "speed-iq-number-layer"

    fun render(style: Style, markers: List<SpeedIqMarker>) {
        remove(style)
        if (markers.isEmpty()) return

        val features = markers.map { marker ->
            Feature.fromGeometry(Point.fromLngLat(marker.lon, marker.lat)).apply {
                addStringProperty("number", marker.number.toString())
            }
        }
        style.addSource(GeoJsonSource(SOURCE, FeatureCollection.fromFeatures(features)))

        addLayerBelowUserMarker(
            style,
            CircleLayer(CIRCLE_LAYER, SOURCE).withProperties(
                PropertyFactory.circleColor(CyclingConstants.SPEED_IQ_MARKER_COLOR),
                PropertyFactory.circleRadius(CyclingConstants.SPEED_IQ_MARKER_RADIUS),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        )
        addLayerBelowUserMarker(
            style,
            SymbolLayer(NUMBER_LAYER, SOURCE).withProperties(
                PropertyFactory.textField(Expression.get("number")),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textSize(13f),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true)
            )
        )
    }

    fun remove(style: Style) {
        try { style.removeLayer(NUMBER_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(CIRCLE_LAYER) } catch (_: Exception) {}
        try { style.removeSource(SOURCE) } catch (_: Exception) {}
    }
}
