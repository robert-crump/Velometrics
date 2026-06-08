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
import com.velometrics.app.util.addLayerBelowUserMarker
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
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
    private const val LABELS_SOURCE = "interval-labels-source"
    private const val LABELS_LAYER = "interval-labels-layer"
    private const val HIGHLIGHT_SOURCE = "interval-highlight-source"
    private const val HIGHLIGHT_LAYER = "interval-highlight-layer"

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

    fun renderGroupedIntervals(style: Style, groups: List<RepeatedInterval>) {
        val features = groups.mapNotNull { group ->
            val trackJson = group.intervals.maxByOrNull { GpsTrackParser.parse(it.gpsTrack).size }?.gpsTrack
            val points = GpsTrackParser.parse(trackJson)
            if (points.size < 2) return@mapNotNull null

            val geoPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
            val lineString = LineString.fromLngLats(geoPoints)
            val feature = Feature.fromGeometry(lineString)

            val avgDuration = MapOverlayUtils.avgDurationNormalizedSec(group)
            val avgPower = MapOverlayUtils.avgPower(group)

            feature.addStringProperty("color", MapOverlayUtils.normalizedDurationToColor(avgDuration))
            feature.addStringProperty("repeatedIntervalId", group.id.toString())
            feature.addStringProperty("name", group.name)
            feature.addNumberProperty("count", group.intervals.size)
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

    fun renderGroupLabels(style: Style, groups: List<RepeatedInterval>) {
        val features = groups.mapNotNull { group ->
            val trackJson = group.intervals.maxByOrNull { GpsTrackParser.parse(it.gpsTrack).size }?.gpsTrack
            val points = GpsTrackParser.parse(trackJson)
            if (points.isEmpty()) return@mapNotNull null

            val midIndex = points.size / 2
            val mid = points[midIndex]
            val point = Point.fromLngLat(mid.longitude, mid.latitude)
            val feature = Feature.fromGeometry(point)

            val avgDuration = MapOverlayUtils.formatDurationMinSec(MapOverlayUtils.avgDurationNormalizedSec(group))
            feature.addStringProperty("label", "${group.name} (${group.intervals.size}x, $avgDuration)")

            feature
        }

        val collection = FeatureCollection.fromFeatures(features)
        val source = GeoJsonSource(LABELS_SOURCE, collection)
        style.addSource(source)

        val layer = SymbolLayer(LABELS_LAYER, LABELS_SOURCE).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textHaloColor("#000000"),
            PropertyFactory.textHaloWidth(1.5f),
            PropertyFactory.textAllowOverlap(true)
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
        style.removeLayer(LABELS_LAYER)
        style.removeSource(LABELS_SOURCE)
        style.removeLayer(GROUPED_LAYER)
        style.removeSource(GROUPED_SOURCE)
        style.removeLayer(UNGROUPED_LAYER)
        style.removeSource(UNGROUPED_SOURCE)
    }
}
