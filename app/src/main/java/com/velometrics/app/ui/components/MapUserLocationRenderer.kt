package com.velometrics.app.ui.components

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.velometrics.app.R
import com.velometrics.app.domain.model.GeoPoint
import com.velometrics.app.util.CyclingConstants.USER_HEADING_ARROW_ICON_SIZE
import com.velometrics.app.util.GeoUtils
import kotlin.math.cos
import kotlin.math.pow
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

/** Renders the user-location dot, accuracy circle, and heading arrow. */
object MapUserLocationRenderer {

    private const val SOURCE = "user-location-source"

    // Referenced by name (not this constant) in MapOverlayUtils.addLayerBelowUserMarker, which
    // must stay layer-agnostic — keep that string literal in sync with this one.
    private const val OUTER_LAYER = "user-location-outer"
    private const val INNER_LAYER = "user-location-inner"
    private const val HEADING_LAYER = "user-location-heading"
    private const val HEADING_ARROW_ICON = "user-heading-arrow-icon"

    /** (Re)draws the marker, accuracy circle, and heading arrow at [location]. */
    fun render(
        context: Context,
        style: Style,
        location: GeoPoint,
        accuracyM: Float,
        heading: Float?,
    ) {
        val feature = Feature.fromGeometry(Point.fromLngLat(location.lon, location.lat))
        val source = GeoJsonSource(SOURCE, feature)

        // Remove existing layers/source if present (heading layer must go before its source)
        if (style.getLayer(HEADING_LAYER) != null) style.removeLayer(HEADING_LAYER)
        if (style.getLayer(OUTER_LAYER) != null) style.removeLayer(OUTER_LAYER)
        if (style.getLayer(INNER_LAYER) != null) style.removeLayer(INNER_LAYER)
        if (style.getSource(SOURCE) != null) style.removeSource(SOURCE)

        style.addSource(source)

        // Location.getAccuracy() is a 68%-confidence radius by definition, so ~1-in-3 fixes
        // legitimately land outside it with no bug. Doubling it approximates a ~95%-confidence
        // radius so the dot reliably reads as "inside the circle".
        val displayRadiusM = accuracyM.toDouble() * 2.0

        // Web Mercator tiles double in resolution with each zoom level, so the screen-pixel
        // radius for a constant ground radius is `radiusAtZoom0 * 2^zoom`. Express that as an
        // exponential (base 2) zoom interpolation so the circle keeps representing the same
        // real-world accuracy radius — and visibly grows/shrinks — as the map is zoomed,
        // mirroring the Google Maps "my location" accuracy circle.
        val latRad = Math.toRadians(location.lat)
        val radiusAtZoom0 = (displayRadiusM * 256.0 /
                (2 * Math.PI * GeoUtils.EARTH_RADIUS_M * cos(latRad))).toFloat()
        val outerRadius = Expression.interpolate(
            Expression.exponential(2f),
            Expression.zoom(),
            Expression.stop(0f, radiusAtZoom0),
            Expression.stop(20f, radiusAtZoom0 * 2f.pow(20))
        )

        val outerCircle = CircleLayer(OUTER_LAYER, SOURCE).apply {
            setProperties(
                PropertyFactory.circleRadius(outerRadius),
                PropertyFactory.circleColor("#42A5F5"),
                PropertyFactory.circleOpacity(0.25f)
            )
        }

        // Inner dot — 8px radius, fully opaque, with white stroke
        val innerCircle = CircleLayer(INNER_LAYER, SOURCE).apply {
            setProperties(
                PropertyFactory.circleRadius(8f),
                PropertyFactory.circleColor("#42A5F5"),
                PropertyFactory.circleOpacity(1.0f),
                PropertyFactory.circleStrokeWidth(2f),
                PropertyFactory.circleStrokeColor("#FFFFFF")
            )
        }

        style.addLayer(outerCircle)
        style.addLayer(innerCircle)

        if (heading != null) {
            registerHeadingArrowIcon(context, style)
            val headingLayer = SymbolLayer(HEADING_LAYER, SOURCE).apply {
                setProperties(
                    PropertyFactory.iconImage(HEADING_ARROW_ICON),
                    PropertyFactory.iconSize(USER_HEADING_ARROW_ICON_SIZE),
                    PropertyFactory.iconRotate(heading),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true)
                )
            }
            style.addLayer(headingLayer)
        }
    }

    private fun registerHeadingArrowIcon(context: Context, style: Style) {
        if (style.getImage(HEADING_ARROW_ICON) != null) return
        val drawable = ContextCompat.getDrawable(context, R.drawable.ic_heading_arrow) ?: return
        style.addImage(HEADING_ARROW_ICON, drawable.toBitmap())
    }

    /** Cheaply updates the heading arrow's rotation, creating the layer if it doesn't exist yet. */
    fun updateHeading(context: Context, style: Style, heading: Float) {
        if (style.getSource(SOURCE) == null) return

        val existing = style.getLayer(HEADING_LAYER) as? SymbolLayer
        if (existing != null) {
            existing.setProperties(PropertyFactory.iconRotate(heading))
            return
        }

        registerHeadingArrowIcon(context, style)
        val headingLayer = SymbolLayer(HEADING_LAYER, SOURCE).apply {
            setProperties(
                PropertyFactory.iconImage(HEADING_ARROW_ICON),
                PropertyFactory.iconSize(USER_HEADING_ARROW_ICON_SIZE),
                PropertyFactory.iconRotate(heading),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true)
            )
        }
        style.addLayer(headingLayer)
    }
}
