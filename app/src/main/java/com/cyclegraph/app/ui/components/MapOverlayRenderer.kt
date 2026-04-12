package com.cyclegraph.app.ui.components

import android.util.Log
import com.cyclegraph.app.domain.model.MapEdge
import com.cyclegraph.app.util.CyclingConstants
import com.cyclegraph.app.util.CyclingConstants.SPEED_OVERLAY_LINE_WIDTH
import com.cyclegraph.app.util.CyclingConstants.STOP_CLUSTER_MAX_ZOOM
import com.cyclegraph.app.util.CyclingConstants.STOP_CLUSTER_RADIUS
import com.cyclegraph.app.util.CyclingConstants.STOP_SPOT_RADIUS
import com.cyclegraph.app.util.CyclingConstants.STOP_SPOT_STROKE_COLOR
import com.cyclegraph.app.util.CyclingConstants.STOP_SPOT_STROKE_WIDTH
import com.cyclegraph.app.util.MapOverlayUtils
import com.cyclegraph.app.util.PolylineDecoder
import com.cyclegraph.app.util.addLayerBelowUserMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
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
    private const val STOP_SOURCE = "stop-spots-source"
    private const val STOP_CLUSTER_LAYER = "stop-cluster-layer"
    private const val STOP_COUNT_LAYER = "stop-cluster-count-layer"
    private const val STOP_LAYER = "stop-spots-layer"

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

    suspend fun renderStopSpots(style: Style, edges: List<MapEdge>) {
        val features = withContext(Dispatchers.Default) {
            val traversed = edges.filter { it.isTraversed }
            val withProb = edges.count { it.stopProbability != null }
            val withProbPos = edges.count { (it.stopProbability ?: 0.0) > 0.0 }
            Log.d("StopSpots", "edges=${edges.size} traversed=${traversed.size} withProb=$withProb withProbPos=$withProbPos")
            // Sample: print stop-related fields from first 3 traversed edges
            traversed.take(3).forEachIndexed { i, e ->
                Log.d("StopSpots", "sample[$i] stopProb=${e.stopProbability} stopCount=${e.stopCount} estStop=${e.estimatedStopTimeS} traversals=${e.traversalCount}")
            }
            edges.mapNotNull { edge ->
                val prob = edge.stopProbability ?: return@mapNotNull null
                if (prob <= 0.0) return@mapNotNull null
                val points = PolylineDecoder.decode(edge.geometryEncoded)
                if (points.isEmpty()) return@mapNotNull null
                // Place marker at from_node (direction-aware: stop occurs before entering the edge)
                val first = points[0]
                val feature = Feature.fromGeometry(Point.fromLngLat(first.longitude, first.latitude))
                val color = when {
                    prob < CyclingConstants.STOP_PROB_OCCASIONAL -> CyclingConstants.STOP_COLOR_SHORT
                    prob < CyclingConstants.STOP_PROB_FREQUENT   -> CyclingConstants.STOP_COLOR_MEDIUM
                    else                                         -> CyclingConstants.STOP_COLOR_LONG
                }
                feature.addStringProperty("color", color)
                feature.addNumberProperty("stopProbability", prob)
                edge.estimatedStopTimeS?.let { feature.addNumberProperty("estimatedStopTimeS", it) }
                feature
            }
        }
        Log.d("StopSpots", "features built: ${features.size}")

        val source = GeoJsonSource(
            STOP_SOURCE,
            FeatureCollection.fromFeatures(features),
            GeoJsonOptions()
                .withCluster(true)
                .withClusterRadius(STOP_CLUSTER_RADIUS)
                .withClusterMaxZoom(STOP_CLUSTER_MAX_ZOOM)
        )
        style.addSource(source)

        // Cluster circles
        val clusterLayer = CircleLayer(STOP_CLUSTER_LAYER, STOP_SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(
                PropertyFactory.circleColor("#E53935"),
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.get("point_count"),
                        Expression.literal(16),
                        Expression.literal(10), Expression.literal(20),
                        Expression.literal(30), Expression.literal(26)
                    )
                ),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor(STOP_SPOT_STROKE_COLOR)
            )
        addLayerBelowUserMarker(style, clusterLayer)

        // Cluster count text
        val clusterCountLayer = SymbolLayer(STOP_COUNT_LAYER, STOP_SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("point_count"))),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textSize(12f),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true)
            )
        addLayerBelowUserMarker(style, clusterCountLayer)

        // Individual (unclustered) spots
        val layer = CircleLayer(STOP_LAYER, STOP_SOURCE)
            .withFilter(Expression.not(Expression.has("point_count")))
            .withProperties(
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleRadius(STOP_SPOT_RADIUS),
                PropertyFactory.circleStrokeWidth(STOP_SPOT_STROKE_WIDTH),
                PropertyFactory.circleStrokeColor(STOP_SPOT_STROKE_COLOR)
            )
        addLayerBelowUserMarker(style, layer)
    }

    fun removeStopSpots(style: Style) {
        listOf(STOP_LAYER, STOP_COUNT_LAYER, STOP_CLUSTER_LAYER).forEach {
            try { style.removeLayer(it) } catch (_: Exception) {}
        }
        try { style.removeSource(STOP_SOURCE) } catch (_: Exception) {}
    }

    private fun decodeEdgePoints(edge: MapEdge): List<Point> {
        return PolylineDecoder.decode(edge.geometryEncoded).map { latLng ->
            Point.fromLngLat(latLng.longitude, latLng.latitude)
        }
    }
}
