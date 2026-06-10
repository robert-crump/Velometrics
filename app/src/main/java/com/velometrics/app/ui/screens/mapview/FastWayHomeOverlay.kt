package com.velometrics.app.ui.screens.mapview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.velometrics.app.domain.service.FastWayHomeResult
import com.velometrics.app.domain.service.RideEstimate
import com.velometrics.app.util.CyclingConstants
import com.velometrics.app.util.FormatUtils
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Point

const val FAST_WAY_HOME_TRACK_ID = "fast-way-home-track"
private const val HOME_MARKER_SOURCE = "fast-way-home-marker-source"
private const val HOME_MARKER_LAYER  = "fast-way-home-marker-layer"

@Composable
fun FastWayHomeCard(
    result: FastWayHomeResult?,
    message: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val title = if (result != null) {
                    "Fast way home: ${Math.round(result.totalDistanceM / 1000.0)} km"
                } else {
                    "Fast way home"
                }
                Text(title, style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss")
                }
            }

            when {
                isLoading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Finding fast way home…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                result != null -> {
                    FastWayHomeEstimateRow("Fast", result.fast)
                    FastWayHomeEstimateRow("Avg", result.avg)
                    FastWayHomeEstimateRow("Slow", result.slow)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Data coverage: ${result.coveragePercent}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                message != null -> {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun FastWayHomeEstimateRow(label: String, estimate: RideEstimate?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        val text = if (estimate != null) {
            "${FormatUtils.formatDuration(estimate.durationSec.toInt())} · ${FormatUtils.formatPower(estimate.avgPowerW.toInt())}"
        } else {
            "—"
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

fun renderHomeMarker(style: Style, position: LatLng) {
    removeHomeMarker(style)
    val point  = Point.fromLngLat(position.longitude, position.latitude)
    val source = GeoJsonSource(HOME_MARKER_SOURCE, Feature.fromGeometry(point))
    style.addSource(source)

    val marker = CircleLayer(HOME_MARKER_LAYER, HOME_MARKER_SOURCE).withProperties(
        PropertyFactory.circleColor(CyclingConstants.FAST_WAY_HOME_TRACK_COLOR),
        PropertyFactory.circleRadius(CyclingConstants.NAV_USER_MARKER_RADIUS),
        PropertyFactory.circleStrokeWidth(CyclingConstants.POI_MARKER_STROKE_WIDTH),
        PropertyFactory.circleStrokeColor("#FFFFFF")
    )
    style.addLayer(marker)
}

fun removeHomeMarker(style: Style) {
    try { style.removeLayer(HOME_MARKER_LAYER) }   catch (_: Exception) {}
    try { style.removeSource(HOME_MARKER_SOURCE) } catch (_: Exception) {}
}
