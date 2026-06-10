package com.velometrics.app.ui.components

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
    private const val POI_ICON_LAYER = "poi-icon-layer"

    private const val HIGHLIGHT_SOURCE = "poi-highlight-source"
    private const val HIGHLIGHT_LAYER = "poi-highlight-layer"
    private const val HIGHLIGHT_ICON_LAYER = "poi-highlight-icon-layer"

    /** Registers the white category icon bitmaps with the map style, if not already present. */
    fun registerIcons(context: Context, style: Style) {
        for (category in PoiIcons.ALL_CATEGORIES) {
            val imageId = PoiIcons.mapIconId(category)
            if (style.getImage(imageId) != null) continue
            val drawable = ContextCompat.getDrawable(context, PoiIcons.drawableResForCategory(category)) ?: continue
            style.addImage(imageId, drawable.toBitmap())
        }
    }

    fun addPois(context: Context, style: Style, pois: List<Poi>) {
        removePois(style)

        if (pois.isEmpty()) return

        registerIcons(context, style)

        val features = pois.map { poi ->
            val point = Point.fromLngLat(poi.lon, poi.lat)
            val feature = Feature.fromGeometry(point)
            feature.addStringProperty("icon", PoiIcons.mapIconId(poi.category))
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
                PropertyFactory.circleColor(CyclingConstants.POI_CIRCLE_COLOR),
                PropertyFactory.circleRadius(CyclingConstants.POI_MARKER_RADIUS),
                PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        style.addLayer(layer)

        // Category icon on top of each POI circle
        val iconLayer = SymbolLayer(POI_ICON_LAYER, POI_SOURCE)
            .withFilter(Expression.not(Expression.has("point_count")))
            .withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(CyclingConstants.POI_MARKER_RADIUS / 24f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        style.addLayer(iconLayer)
    }

    fun removePois(style: Style) {
        try { style.removeLayer(HIGHLIGHT_ICON_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(HIGHLIGHT_LAYER) } catch (_: Exception) {}
        try { style.removeSource(HIGHLIGHT_SOURCE) } catch (_: Exception) {}
        try { style.removeLayer(POI_CLUSTER_COUNT_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(POI_CLUSTER_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(POI_ICON_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(POI_LAYER) } catch (_: Exception) {}
        try { style.removeSource(POI_SOURCE) } catch (_: Exception) {}
    }

    /** Renders a single highlighted POI on top of the base POI layer at 1.5x radius. */
    fun highlightPoi(context: Context, style: Style, poi: Poi?) {
        try { style.removeLayer(HIGHLIGHT_ICON_LAYER) } catch (_: Exception) {}
        try { style.removeLayer(HIGHLIGHT_LAYER) } catch (_: Exception) {}
        try { style.removeSource(HIGHLIGHT_SOURCE) } catch (_: Exception) {}
        poi ?: return

        registerIcons(context, style)

        val point = Point.fromLngLat(poi.lon, poi.lat)
        val feature = Feature.fromGeometry(point)
        feature.addStringProperty("icon", PoiIcons.mapIconId(poi.category))
        val source = GeoJsonSource(HIGHLIGHT_SOURCE, feature)
        style.addSource(source)

        val layer = CircleLayer(HIGHLIGHT_LAYER, HIGHLIGHT_SOURCE).withProperties(
            PropertyFactory.circleColor(CyclingConstants.POI_CIRCLE_COLOR),
            PropertyFactory.circleRadius(CyclingConstants.POI_MARKER_RADIUS * 1.5f),
            PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH * 1.5f),
            PropertyFactory.circleStrokeColor("#FFFFFF")
        )
        style.addLayer(layer)

        val iconLayer = SymbolLayer(HIGHLIGHT_ICON_LAYER, HIGHLIGHT_SOURCE)
            .withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconSize(CyclingConstants.POI_MARKER_RADIUS * 1.5f / 24f),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        style.addLayer(iconLayer)
    }
}
