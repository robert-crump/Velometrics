package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.MapEdge
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.CyclingConstants.SPEED_OVERLAY_LINE_WIDTH
import com.velometrics.app.util.MapOverlayUtils
import com.velometrics.app.util.PolylineDecoder
import com.velometrics.app.util.addLayerBelowUserMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

object MapOverlayRenderer {

    private const val EDGE_SOURCE = "edge-overlay-source"
    private const val EDGE_LAYER = "edge-overlay-layer"
    private const val SPEED_SOURCE = "speed-overlay-source"
    private const val SPEED_LAYER = "speed-overlay-layer"

    suspend fun renderEdges(style: Style, edges: List<MapEdge>) {
        val features = withContext(Dispatchers.Default) {
            val traversed = mutableListOf<List<Point>>()
            edges.filter { it.isTraversed }.forEach { edge ->
                val points = decodeEdgePoints(edge)
                if (points.size >= 2) traversed.add(points)
            }
            buildList {
                if (traversed.isNotEmpty())
                    add(Feature.fromGeometry(MultiLineString.fromLngLats(traversed))
                        .also { it.addStringProperty("color", "#4CAF50") })
            }
        }
        val source = GeoJsonSource(EDGE_SOURCE, FeatureCollection.fromFeatures(features))
        style.addSource(source)
        val layer = LineLayer(EDGE_LAYER, EDGE_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        addLayerBelowUserMarker(style, layer)
    }

    fun removeEdges(style: Style) {
        try { style.removeLayer(EDGE_LAYER) } catch (_: Exception) {}
        try { style.removeSource(EDGE_SOURCE) } catch (_: Exception) {}
    }

    suspend fun renderSpeedOverlay(style: Style, edges: List<MapEdge>, selectedCategories: Set<String> = emptySet()) {
        // Resolve selected category keys to hex colors; empty set = show all
        val selectedColors = selectedCategories.mapNotNull { CyclingConstants.SPEED_COLOR_MAP[it] }.toSet()
        val features = withContext(Dispatchers.Default) {
            val byColor = mutableMapOf<String, MutableList<List<Point>>>()
            edges.forEach { edge ->
                val speed = edge.speedMean ?: return@forEach
                val color = MapOverlayUtils.speedToColor(speed)
                if (selectedColors.isNotEmpty() && color !in selectedColors) return@forEach
                val points = decodeEdgePoints(edge)
                if (points.size >= 2) {
                    byColor.getOrPut(color) { mutableListOf() }.add(points)
                }
            }
            byColor.map { (color, lines) ->
                Feature.fromGeometry(MultiLineString.fromLngLats(lines))
                    .also { it.addStringProperty("color", color) }
            }
        }
        val source = GeoJsonSource(SPEED_SOURCE, FeatureCollection.fromFeatures(features))
        style.addSource(source)
        val layer = LineLayer(SPEED_LAYER, SPEED_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(SPEED_OVERLAY_LINE_WIDTH),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        addLayerBelowUserMarker(style, layer)
    }

    fun removeSpeedOverlay(style: Style) {
        try { style.removeLayer(SPEED_LAYER) } catch (_: Exception) {}
        try { style.removeSource(SPEED_SOURCE) } catch (_: Exception) {}
    }

    private fun decodeEdgePoints(edge: MapEdge): List<Point> {
        return PolylineDecoder.decode(edge.geometryEncoded).map { latLng ->
            Point.fromLngLat(latLng.longitude, latLng.latitude)
        }
    }
}
