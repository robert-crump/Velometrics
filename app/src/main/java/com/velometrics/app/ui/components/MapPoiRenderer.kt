package com.velometrics.app.ui.components

import com.velometrics.app.domain.model.Poi
import com.velometrics.app.util.CyclingConstants
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

object MapPoiRenderer {

    const val POI_SOURCE = "poi-source"
    const val POI_LAYER = "poi-layer"
    const val POI_CLUSTER_LAYER = "poi-cluster-layer"
    private const val POI_CLUSTER_COUNT_LAYER = "poi-cluster-count-layer"

    private const val HIGHLIGHT_SOURCE = "poi-highlight-source"
    private const val HIGHLIGHT_LAYER = "poi-highlight-layer"

    fun addPois(style: Style, pois: List<Poi>) {
        removePois(style)

        if (pois.isEmpty()) return

        val features = pois.map { poi ->
            val point = Point.fromLngLat(poi.lon, poi.lat)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("color", colorForCategory(poi.category))
            feature.addStringProperty("poiId", poi.poiId)
            feature
        }

        val collection = FeatureCollection.fromFeatures(features)
        val options = GeoJsonOptions()
            .withCluster(true)
            .withClusterRadius(50)
            .withClusterMaxZoom(14)
        val source = GeoJsonSource(POI_SOURCE, collection, options)
        style.addSource(source)

        // Cluster circles
        val clusterLayer = CircleLayer(POI_CLUSTER_LAYER, POI_SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(
                PropertyFactory.circleColor("#388E3C"),
                PropertyFactory.circleRadius(
                    Expression.step(
                        Expression.get("point_count"),
                        Expression.literal(18),
                        Expression.literal(10), Expression.literal(22),
                        Expression.literal(30), Expression.literal(28)
                    )
                ),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        style.addLayer(clusterLayer)

        // Cluster count text
        val clusterCountLayer = SymbolLayer(POI_CLUSTER_COUNT_LAYER, POI_SOURCE)
            .withFilter(Expression.has("point_count"))
            .withProperties(
                PropertyFactory.textField(
                    Expression.toString(Expression.get("point_count"))
                ),
                PropertyFactory.textColor("#FFFFFF"),
                PropertyFactory.textSize(13f),
                PropertyFactory.textIgnorePlacement(true),
                PropertyFactory.textAllowOverlap(true)
            )
        style.addLayer(clusterCountLayer)

        // Individual (unclustered) POIs
        val layer = CircleLayer(POI_LAYER, POI_SOURCE)
            .withFilter(Expression.not(Expression.has("point_count")))
            .withProperties(
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleRadius(CyclingConstants.POI_MARKER_RADIUS),
                PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        style.addLayer(layer)
    }

    fun removePois(style: Style) {
        try { style.removeLayer(HIGHLIGHT_LAYER) } catch (_: Exception) {}
        try { style.removeSource(HIGHLIGHT_SOURCE) } catch (_: Exception) {}
        try { style.removeLayer(POI_CLUSTER_COUNT_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(POI_CLUSTER_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(POI_LAYER) } catch (_: Exception) {}
        try { style.removeSource(POI_SOURCE) } catch (_: Exception) {}
    }

    /** Renders a single highlighted POI on top of the base POI layer at 1.5× radius. */
    fun highlightPoi(style: Style, poi: Poi?) {
        try { style.removeLayer(HIGHLIGHT_LAYER) } catch (_: Exception) {}
        try { style.removeSource(HIGHLIGHT_SOURCE) } catch (_: Exception) {}
        poi ?: return

        val point = Point.fromLngLat(poi.lon, poi.lat)
        val feature = Feature.fromGeometry(point)
        feature.addStringProperty("color", colorForCategory(poi.category))
        val source = GeoJsonSource(HIGHLIGHT_SOURCE, feature)
        style.addSource(source)

        val layer = CircleLayer(HIGHLIGHT_LAYER, HIGHLIGHT_SOURCE).withProperties(
            PropertyFactory.circleColor(Expression.get("color")),
            PropertyFactory.circleRadius(CyclingConstants.POI_MARKER_RADIUS * 1.5f),
            PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH * 1.5f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
        style.addLayer(layer)
    }

    private fun colorForCategory(category: String): String = when (category) {
        "cafe" -> CyclingConstants.POI_COLOR_CAFE
        "bakery" -> CyclingConstants.POI_COLOR_BAKERY
        "restaurant" -> "#E64A19"
        "fast_food" -> CyclingConstants.POI_COLOR_FAST_FOOD
        "fuel" -> CyclingConstants.POI_COLOR_FUEL
        "friture" -> CyclingConstants.POI_COLOR_FRITURE
        else -> "#607D8B"
    }
}
