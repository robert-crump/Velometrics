package com.velometrics.app.ui.components

import com.velometrics.app.data.heatmap.HeatmapCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.velometrics.app.util.addLayerBelowUserMarker
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

object MapHeatmapRenderer {

    private const val HEATMAP_SOURCE = "heatmap-source"
    private const val HEATMAP_LAYER = "heatmap-layer"

    suspend fun renderHeatmap(style: Style, cells: List<HeatmapCell>) {
        if (cells.isEmpty()) return

        val maxCount = cells.maxOf { it.count }.toFloat().coerceAtLeast(1f)

        val collection = withContext(Dispatchers.Default) {
            val features = cells.map { cell ->
                Feature.fromGeometry(
                    Point.fromLngLat(cell.lon, cell.lat)
                ).also { it.addNumberProperty("count", cell.count) }
            }
            FeatureCollection.fromFeatures(features)
        }

        try { style.addSource(GeoJsonSource(HEATMAP_SOURCE, collection)) } catch (_: Exception) { return }

        val layer = HeatmapLayer(HEATMAP_LAYER, HEATMAP_SOURCE).withProperties(
            // Weight: normalise visit count to 0–1 so heavily-ridden roads are denser
            PropertyFactory.heatmapWeight(
                interpolate(
                    linear(), get("count"),
                    stop(0, 0f),
                    stop(maxCount, 1f)
                )
            ),
            // Kernel radius: small at low zoom to avoid blurry blobs; grows at high zoom
            // to bridge the ~55 m cell gaps when fully zoomed in
            PropertyFactory.heatmapRadius(
                interpolate(
                    linear(), zoom(),
                    stop(5,    2f),
                    stop(10,  4.5f),
                    stop(12,   7f),
                    stop(14,  10f),
                    stop(17,  19f),
                    stop(20,  45f)
                )
            ),
            // Intensity: kept low so only the most-ridden roads saturate to white;
            // lightly-ridden roads stay dark blue
            PropertyFactory.heatmapIntensity(
                interpolate(
                    linear(), zoom(),
                    stop(5,  0.15f),
                    stop(10, 0.40f),
                    stop(12, 0.65f),
                    stop(14, 0.90f)
                )
            ),
            // Colour ramp: transparent → dark blue → medium blue → light blue → near-white
            PropertyFactory.heatmapColor(
                interpolate(
                    linear(), heatmapDensity(),
                    stop(0.00, rgba(0,   0,   0,   0)),
                    stop(0.10, rgba(0,   20,  100, 0.65)),
                    stop(0.30, rgba(0,   70,  200, 0.78)),
                    stop(0.55, rgba(30,  130, 255, 0.88)),
                    stop(0.80, rgba(130, 190, 255, 0.95)),
                    stop(1.00, rgba(220, 235, 255, 1.00))
                )
            ),
            PropertyFactory.heatmapOpacity(0.85f)
        )

        addLayerBelowUserMarker(style, layer)
    }

    fun removeHeatmap(style: Style) {
        try { style.removeLayer(HEATMAP_LAYER) } catch (_: Exception) {}
        try { style.removeSource(HEATMAP_SOURCE) } catch (_: Exception) {}
    }
}
