package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.IntervalSession
import com.velometrics.app.domain.model.RepeatedInterval
import com.velometrics.app.util.CyclingConstants.INTERVAL_GROUPED_LINE_WIDTH
import com.velometrics.app.util.CyclingConstants.INTERVAL_HIGHLIGHT_COLOR
import com.velometrics.app.util.CyclingConstants.INTERVAL_HIGHLIGHT_LINE_WIDTH
import com.velometrics.app.util.CyclingConstants.INTERVAL_OVERLAY_LINE_WIDTH
import com.velometrics.app.util.FormatUtils
import com.velometrics.app.util.GpsTrackParser
import com.velometrics.app.util.MapOverlayUtils
import com.velometrics.app.util.PolylineDecoder
import com.velometrics.app.util.addLayerBelowUserMarker
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

object MapIntervalRenderer {

    private const val UNGROUPED_SOURCE = "interval-ungrouped-source"
    private const val UNGROUPED_LAYER = "interval-ungrouped-layer"
    private const val GROUPED_SOURCE = "interval-grouped-source"
    private const val GROUPED_LAYER = "interval-grouped-layer"
    private const val HIGHLIGHT_SOURCE = "interval-highlight-source"
    private const val HIGHLIGHT_LAYER = "interval-highlight-layer"

    /** Renders raw, per-session interval tracks (e.g. on a single ride's detail map) — no archetype grouping. */
    fun renderUngroupedIntervals(style: Style, intervals: List<IntervalSession>) {
        val features = intervals.mapNotNull { interval ->
            val points = GpsTrackParser.parse(interval.gpsTrack)
            if (points.size < 2) return@mapNotNull null

            val geoPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val lineString = LineString.fromLngLats(geoPoints)
            val feature = Feature.fromGeometry(lineString)

            feature.addStringProperty("color", MapOverlayUtils.normalizedDurationToColor(interval.durationNormalizedSec))
            feature.addStringProperty("intervalId", interval.id.toString())
            feature.addStringProperty("date", FormatUtils.formatDate(interval.startTimestamp))
            feature.addStringProperty("duration", FormatUtils.formatDuration(interval.durationSec))
            feature.addStringProperty("durationNorm", FormatUtils.formatDuration(interval.durationNormalizedSec))
            feature.addStringProperty("distance", FormatUtils.formatDistance(interval.distanceM / 1000.0))
            feature.addStringProperty("power", FormatUtils.formatPower(interval.avgPower))
            feature.addStringProperty("speed", FormatUtils.formatSpeed(interval.avgSpeedKmh))
            feature.addStringProperty("direction", interval.direction)

            feature
        }

        val collection = FeatureCollection.fromFeatures(features)
        val source = GeoJsonSource(UNGROUPED_SOURCE, collection)
        style.addSource(source)

        val layer = LineLayer(UNGROUPED_LAYER, UNGROUPED_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(INTERVAL_OVERLAY_LINE_WIDTH),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        addLayerBelowUserMarker(style, layer)
    }

    /** Decodes a [RepeatedInterval]'s matched road-graph edges into a single lng/lat point sequence. */
    private fun archetypeGeometry(repeatedInterval: RepeatedInterval): List<Point> =
        repeatedInterval.edges
            .flatMap { edge -> PolylineDecoder.decode(edge.geometryEncoded) }
            .map { Point.fromLngLat(it.longitude, it.latitude) }

    fun renderRepeatedIntervals(style: Style, repeatedIntervals: List<RepeatedInterval>) {
        val features = repeatedIntervals.mapNotNull { repeatedInterval ->
            val geoPoints = archetypeGeometry(repeatedInterval)
            if (geoPoints.size < 2) return@mapNotNull null

            val lineString = LineString.fromLngLats(geoPoints)
            val feature = Feature.fromGeometry(lineString)

            val avgDuration = MapOverlayUtils.avgDurationNormalizedSec(repeatedInterval)
            val avgPower = MapOverlayUtils.avgPower(repeatedInterval)

            feature.addStringProperty("color", MapOverlayUtils.normalizedDurationToColor(avgDuration))
            feature.addStringProperty("repeatedIntervalId", repeatedInterval.id.toString())
            feature.addStringProperty("name", repeatedInterval.name)
            feature.addNumberProperty("count", repeatedInterval.intervals.size)
            feature.addStringProperty("avgDuration", MapOverlayUtils.formatDurationMinSec(avgDuration))
            feature.addStringProperty("avgPower", FormatUtils.formatPower(avgPower))

            feature
        }

        val collection = FeatureCollection.fromFeatures(features)
        val source = GeoJsonSource(GROUPED_SOURCE, collection)
        style.addSource(source)

        val layer = LineLayer(GROUPED_LAYER, GROUPED_SOURCE).withProperties(
            PropertyFactory.lineColor(Expression.get("color")),
            PropertyFactory.lineWidth(INTERVAL_GROUPED_LINE_WIDTH),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        addLayerBelowUserMarker(style, layer)
    }

    fun renderHighlight(style: Style, interval: IntervalSession) {
        val points = GpsTrackParser.parse(interval.gpsTrack)
        if (points.size < 2) return

        val geoPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val lineString = LineString.fromLngLats(geoPoints)
        val feature = Feature.fromGeometry(lineString)

        val source = GeoJsonSource(HIGHLIGHT_SOURCE, feature)
        style.addSource(source)

        val layer = LineLayer(HIGHLIGHT_LAYER, HIGHLIGHT_SOURCE).withProperties(
            PropertyFactory.lineColor(INTERVAL_HIGHLIGHT_COLOR),
            PropertyFactory.lineWidth(INTERVAL_HIGHLIGHT_LINE_WIDTH),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        addLayerBelowUserMarker(style, layer)
    }

    fun removeHighlight(style: Style) {
        style.removeLayer(HIGHLIGHT_LAYER)
        style.removeSource(HIGHLIGHT_SOURCE)
    }

    fun removeIntervalOverlay(style: Style) {
        style.removeLayer(HIGHLIGHT_LAYER)
        style.removeSource(HIGHLIGHT_SOURCE)
        style.removeLayer(GROUPED_LAYER)
        style.removeSource(GROUPED_SOURCE)
        style.removeLayer(UNGROUPED_LAYER)
        style.removeSource(UNGROUPED_SOURCE)
    }
}
