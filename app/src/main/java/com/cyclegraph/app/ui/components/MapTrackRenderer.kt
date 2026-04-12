package com.cyclegraph.app.ui.components

import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

object MapTrackRenderer {

    private fun sourceId(trackId: String) = "track-source-$trackId"
    private fun layerId(trackId: String) = "track-layer-$trackId"

    fun addTrack(
        style: Style,
        trackId: String,
        points: List<LatLng>,
        color: String,
        lineWidth: Float = com.cyclegraph.app.util.CyclingConstants.TRACK_LINE_WIDTH
    ) {
        if (points.size < 2) return

        val geoPoints = points.map { Point.fromLngLat(it.longitude, it.latitude) }
        val lineString = LineString.fromLngLats(geoPoints)
        val feature = Feature.fromGeometry(lineString)

        val source = GeoJsonSource(sourceId(trackId), feature)
        style.addSource(source)

        val layer = LineLayer(layerId(trackId), sourceId(trackId)).withProperties(
            PropertyFactory.lineColor(color),
            PropertyFactory.lineWidth(lineWidth),
            PropertyFactory.lineJoin("round"),
            PropertyFactory.lineCap("round")
        )
        style.addLayer(layer)
    }

    fun removeTrack(style: Style, trackId: String) {
        style.removeLayer(layerId(trackId))
        style.removeSource(sourceId(trackId))
    }

    fun removeAllTracks(style: Style, trackIds: Collection<String>) {
        trackIds.forEach { removeTrack(style, it) }
    }
}
