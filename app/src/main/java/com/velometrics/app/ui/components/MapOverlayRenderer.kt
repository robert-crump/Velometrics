package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.FlowSegment
import com.velometrics.app.util.CyclingConstants.FLOW_SEGMENT_COLOR
import com.velometrics.app.util.CyclingConstants.FLOW_SEGMENT_LINE_WIDTH
import com.velometrics.app.util.PolylineDecoder
import com.velometrics.app.util.addLayerBelowUserMarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

object MapOverlayRenderer {

    private const val FLOW_SEGMENT_SOURCE = "flow-segment-overlay-source"
    private const val FLOW_SEGMENT_LAYER = "flow-segment-overlay-layer"

    suspend fun renderFlowSegments(style: Style, segments: List<FlowSegment>) {
        val features = withContext(Dispatchers.Default) {
            val lines = mutableListOf<List<Point>>()
            segments.forEach { segment ->
                val points = PolylineDecoder.decode(segment.geometryEncoded).map { latLng ->
                    Point.fromLngLat(latLng.longitude, latLng.latitude)
                }
                if (points.size >= 2) lines.add(points)
            }
            buildList {
                if (lines.isNotEmpty())
                    add(Feature.fromGeometry(MultiLineString.fromLngLats(lines))
                        .also { it.addStringProperty("color", FLOW_SEGMENT_COLOR) })
            }
        }
        val source = GeoJsonSource(FLOW_SEGMENT_SOURCE, FeatureCollection.fromFeatures(features))
        style.addSource(source)
        val layer = LineLayer(FLOW_SEGMENT_LAYER, FLOW_SEGMENT_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(FLOW_SEGMENT_LINE_WIDTH),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        addLayerBelowUserMarker(style, layer)
    }

    fun removeFlowSegments(style: Style) {
        try { style.removeLayer(FLOW_SEGMENT_LAYER) } catch (_: Exception) {}
        try { style.removeSource(FLOW_SEGMENT_SOURCE) } catch (_: Exception) {}
    }
}
