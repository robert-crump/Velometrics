package com.velometrics.app.ui.components

import android.graphics.PointF
import com.velometrics.app.domain.model.GeoPoint
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Point

/**
 * Adapter over MapLibre's `queryRenderedFeatures` tap hit-testing, so the interactive layers'
 * names live in one place alongside the renderers that create them.
 */
object MapFeatureQuery {

    /** Location of the first POI cluster under [screenPoint], if any. */
    fun poiClusterAt(map: MapLibreMap, screenPoint: PointF): GeoPoint? {
        val feature = map.queryRenderedFeatures(screenPoint, MapPoiRenderer.POI_CLUSTER_LAYER)
            .firstOrNull() ?: return null
        val geometry = feature.geometry()
        return if (geometry is Point) GeoPoint(geometry.latitude(), geometry.longitude()) else null
    }

    /** `poiId` of the first individual POI under [screenPoint], if any. */
    fun poiIdAt(map: MapLibreMap, screenPoint: PointF): String? =
        map.queryRenderedFeatures(screenPoint, MapPoiRenderer.POI_LAYER)
            .firstOrNull()?.getStringProperty("poiId")

    /** `repeatedIntervalId` of the grouped-interval archetype under [screenPoint], if any. */
    fun repeatedIntervalIdAt(map: MapLibreMap, screenPoint: PointF): String? =
        map.queryRenderedFeatures(screenPoint, MapIntervalRenderer.GROUPED_LAYER)
            .firstOrNull()?.getStringProperty("repeatedIntervalId")
}
