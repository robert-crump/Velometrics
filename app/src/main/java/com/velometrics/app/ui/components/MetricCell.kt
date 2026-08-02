package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val ColorBetter = Color(0xFF4CAF50)
private val ColorWorse = Color(0xFF592B0A)

/**
 * A label/value pair, optionally with up to two trend triangles comparing [current] against
 * [medianLast5] (vs. last 5 rides) and [medianAllPrevious] (vs. all previous rides). Used by the
 * Ride and Repeated Interval detail screens.
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    current: Double? = null,
    medianLast5: Double? = null,
    medianAllPrevious: Double? = null,
    higherIsBetter: Boolean = true
) {
    val last5Triangle = remember(current, medianLast5, higherIsBetter) {
        getTriangle(current, medianLast5, higherIsBetter)
    }
    val allPreviousTriangle = remember(current, medianAllPrevious, higherIsBetter) {
        getTriangle(current, medianAllPrevious, higherIsBetter)
    }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium
            )
            last5Triangle?.let { (icon, color) ->
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            }
            allPreviousTriangle?.let { (icon, color) ->
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun getTriangle(
    current: Double?,
    reference: Double?,
    higherIsBetter: Boolean
): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color>? {
    if (current == null || reference == null || reference == 0.0 || current == reference) return null
    val isUp = current > reference
    val isBetter = isUp == higherIsBetter
    val color = if (isBetter) ColorBetter else ColorWorse
    val icon = if (isUp) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown
    return Pair(icon, color)
}
