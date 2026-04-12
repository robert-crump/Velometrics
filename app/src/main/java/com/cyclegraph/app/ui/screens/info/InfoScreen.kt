package com.cyclegraph.app.ui.screens.info

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import com.cyclegraph.app.ui.components.ComposableMapView
import com.cyclegraph.app.util.CyclingConstants
import org.maplibre.android.geometry.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Polygon
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.max
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Info") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            KcalSectionCard()
            Spacer(modifier = Modifier.height(16.dp))
            FatEfficiencySectionCard()
            Spacer(modifier = Modifier.height(16.dp))
            PoiBboxSectionCard()
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun KcalSectionCard() {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("How kcal are calculated", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Fat and carbohydrate burn rates are estimated from your power output using " +
                "polynomial models calibrated to average metabolic data. The chart below shows " +
                "kcal/h burned from fat (orange) and carbs (blue) across the power range.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            KcalChart()
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Fat burn peaks at ~${fatMaxWatt().roundToInt()} W (called 'FatMax') — " +
                "above this, carbohydrates become the dominant fuel. Carb and fat burn rates depend on the athlete and their fitness shape.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FatEfficiencySectionCard() {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Fat Efficiency Score", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "The fat efficiency score (0–100) measures how much of your ride was spent " +
                "burning fat relative to the theoretical maximum fat burn rate (at ~${fatMaxWatt().roundToInt()} W).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Score high:", style = MaterialTheme.typography.labelMedium)
            Text(
                "Ride consistently at moderate power (roughly 150–220 W). " +
                "This is where fat burn is at or near its peak. Long endurance rides at " +
                "zone 2 intensity maximize fat oxidation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Score low:", style = MaterialTheme.typography.labelMedium)
            Text(
                "Very high power efforts (above ~300 W) shift fuel use almost entirely to " +
                "carbohydrates. Very low power (below ~80 W) also reduces absolute fat burn. " +
                "A mix of high-intensity intervals without recovery will lower the score.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PoiBboxSectionCard() {
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Where is POI data available?", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Points of interest (fuel stations, cafes, bakeries, fast food) are available " +
                "within the highlighted area. Outside this region, the POI list will be empty.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            val midLat = (CyclingConstants.BBOX_SW_LAT + CyclingConstants.BBOX_NE_LAT) / 2.0
            val midLon = (CyclingConstants.BBOX_SW_LON + CyclingConstants.BBOX_NE_LON) / 2.0
            ComposableMapView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp),
                initialCenter = LatLng(midLat, midLon),
                initialZoom = 8.5,
                gesturesEnabled = false,
                onMapReady = { _, style ->
                    val ring = listOf(
                        listOf(
                            org.maplibre.geojson.Point.fromLngLat(CyclingConstants.BBOX_SW_LON, CyclingConstants.BBOX_SW_LAT),
                            org.maplibre.geojson.Point.fromLngLat(CyclingConstants.BBOX_NE_LON, CyclingConstants.BBOX_SW_LAT),
                            org.maplibre.geojson.Point.fromLngLat(CyclingConstants.BBOX_NE_LON, CyclingConstants.BBOX_NE_LAT),
                            org.maplibre.geojson.Point.fromLngLat(CyclingConstants.BBOX_SW_LON, CyclingConstants.BBOX_NE_LAT),
                            org.maplibre.geojson.Point.fromLngLat(CyclingConstants.BBOX_SW_LON, CyclingConstants.BBOX_SW_LAT)
                        )
                    )
                    val polygon = Polygon.fromLngLats(ring)
                    val feature = Feature.fromGeometry(polygon)
                    val source = GeoJsonSource("bbox-source", FeatureCollection.fromFeature(feature))
                    style.addSource(source)
                    val fillLayer = FillLayer("bbox-layer", "bbox-source").withProperties(
                        PropertyFactory.fillColor("#2196F3"),
                        PropertyFactory.fillOpacity(0.25f)
                    )
                    style.addLayer(fillLayer)
                }
            )
        }
    }
}

private fun fatMaxWatt(): Double = -CyclingConstants.FAT_B / (2.0 * CyclingConstants.FAT_A)

private fun fatKcalPerHour(w: Double): Double =
    max(0.0, CyclingConstants.FAT_A * w * w + CyclingConstants.FAT_B * w + CyclingConstants.FAT_C)

private fun carbKcalPerHour(w: Double): Double =
    max(
        0.0,
        CyclingConstants.CARB_A * w * w * w * w +
        CyclingConstants.CARB_B * w * w * w +
        CyclingConstants.CARB_C * w * w +
        CyclingConstants.CARB_D * w +
        CyclingConstants.CARB_E
    )

@Composable
private fun KcalChart() {
    val minWatt = 50.0
    val maxWatt = 375.0
    val maxKcal = 1300.0
    val fatColor = Color(0xFFFF9800)   // orange
    val carbColor = Color(0xFF2196F3)  // blue
    val axisColor = Color.Gray
    val fatMaxW = fatMaxWatt()

    Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
        ) {
            val w = size.width
            val h = size.height
            val padL = 52f
            val padR = 16f
            val padT = 36f   // extra top padding for "kcal/h" label
            val padB = 36f
            val plotW = w - padL - padR
            val plotH = h - padT - padB

            fun xOf(watt: Double) = padL + ((watt - minWatt) / (maxWatt - minWatt) * plotW).toFloat()
            fun yOf(kcal: Double) = padT + plotH - (kcal / maxKcal * plotH).toFloat()

            // "kcal/h" label at the top of the y-axis
            drawContext.canvas.nativeCanvas.drawText(
                "kcal/h",
                padL - 8f,
                padT - 14f,
                android.graphics.Paint().apply {
                    textSize = 24f
                    color = android.graphics.Color.GRAY
                    textAlign = android.graphics.Paint.Align.RIGHT
                }
            )

            // Axes
            drawLine(axisColor, Offset(padL, padT), Offset(padL, padT + plotH), strokeWidth = 2f)
            drawLine(axisColor, Offset(padL, padT + plotH), Offset(padL + plotW, padT + plotH), strokeWidth = 2f)

            // Y-axis ticks and labels (with period as thousands separator)
            val yTicks = listOf(0, 200, 400, 600, 800, 1000, 1200)
            yTicks.forEach { kcal ->
                val y = yOf(kcal.toDouble())
                drawLine(axisColor, Offset(padL - 6f, y), Offset(padL, y), strokeWidth = 1.5f)
                val label = if (kcal >= 1000) "${kcal / 1000}.${"%03d".format(kcal % 1000)}" else "$kcal"
                drawContext.canvas.nativeCanvas.drawText(
                    label,
                    padL - 8f,
                    y + 4f,
                    android.graphics.Paint().apply {
                        textSize = 24f
                        color = android.graphics.Color.GRAY
                        textAlign = android.graphics.Paint.Align.RIGHT
                    }
                )
            }

            // X-axis ticks and labels
            val xTicks = listOf(50, 100, 150, 200, 250, 300, 350)
            xTicks.forEach { watt ->
                val x = xOf(watt.toDouble())
                drawLine(axisColor, Offset(x, padT + plotH), Offset(x, padT + plotH + 6f), strokeWidth = 1.5f)
                drawContext.canvas.nativeCanvas.drawText(
                    "${watt}W",
                    x,
                    padT + plotH + 28f,
                    android.graphics.Paint().apply {
                        textSize = 24f
                        color = android.graphics.Color.GRAY
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                )
            }

            // Fat line
            val fatPath = Path()
            var firstFat = true
            val steps = 300
            for (i in 0..steps) {
                val watt = minWatt + i * (maxWatt - minWatt) / steps
                val kcal = fatKcalPerHour(watt)
                val x = xOf(watt)
                val y = yOf(kcal)
                if (firstFat) { fatPath.moveTo(x, y); firstFat = false } else fatPath.lineTo(x, y)
            }
            drawPath(fatPath, fatColor, style = Stroke(width = 3f))

            // Carb line
            val carbPath = Path()
            var firstCarb = true
            for (i in 0..steps) {
                val watt = minWatt + i * (maxWatt - minWatt) / steps
                val kcal = carbKcalPerHour(watt)
                val x = xOf(watt)
                val y = yOf(kcal)
                if (firstCarb) { carbPath.moveTo(x, y); firstCarb = false } else carbPath.lineTo(x, y)
            }
            drawPath(carbPath, carbColor, style = Stroke(width = 3f))

            // Fat-max vertical dashed line (only if within visible range)
            if (fatMaxW in minWatt..maxWatt) {
                val fatMaxX = xOf(fatMaxW)
                drawLine(
                    color = fatColor.copy(alpha = 0.6f),
                    start = Offset(fatMaxX, padT),
                    end = Offset(fatMaxX, padT + plotH),
                    strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                )
                drawContext.canvas.nativeCanvas.drawText(
                    "~${fatMaxW.roundToInt()}W",
                    fatMaxX + 4f,
                    padT + 24f,
                    android.graphics.Paint().apply {
                        textSize = 22f
                        color = android.graphics.Color.rgb(255, 152, 0)
                        textAlign = android.graphics.Paint.Align.LEFT
                    }
                )
            }

            // Legend
            val legendY = padT + 10f
            val legendX = padL + plotW - 160f
            drawLine(fatColor, Offset(legendX, legendY), Offset(legendX + 30f, legendY), strokeWidth = 3f)
            drawContext.canvas.nativeCanvas.drawText(
                "Fat",
                legendX + 36f,
                legendY + 6f,
                android.graphics.Paint().apply {
                    textSize = 26f
                    color = android.graphics.Color.rgb(255, 152, 0)
                }
            )
            drawLine(carbColor, Offset(legendX, legendY + 30f), Offset(legendX + 30f, legendY + 30f), strokeWidth = 3f)
            drawContext.canvas.nativeCanvas.drawText(
                "Carbs",
                legendX + 36f,
                legendY + 36f,
                android.graphics.Paint().apply {
                    textSize = 26f
                    color = android.graphics.Color.rgb(33, 150, 243)
                }
            )
        }
}
