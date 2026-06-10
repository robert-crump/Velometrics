package com.velometrics.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * A label/value pair, optionally with a trend arrow comparing [current] against a
 * reference [median]. Used by the Ride, Repeated Route, and Repeated Interval detail screens.
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    current: Double? = null,
    median: Double? = null,
    higherIsBetter: Boolean = true
) {
    val (arrowIcon, arrowColor) = remember(current, median, higherIsBetter) {
        getArrow(current, median, higherIsBetter)
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
            if (arrowIcon != null && current != null && median != null) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = arrowIcon,
                    contentDescription = null,
                    tint = arrowColor,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

private fun getArrow(
    current: Double?,
    median: Double?,
    higherIsBetter: Boolean
): Pair<ImageVector?, Color> {
    if (current == null || median == null || median == 0.0) return Pair(null, Color.Gray)
    val relDiff = (current - median) / median
    return when {
        abs(relDiff) < 0.05 ->
            Pair(Icons.Default.ArrowForward, Color.Gray)
        relDiff > 0 && higherIsBetter -> Pair(Icons.Default.ArrowUpward, Color(0xFF4CAF50))
        relDiff < 0 && higherIsBetter -> Pair(Icons.Default.ArrowDownward, Color(0xFFF44336))
        relDiff > 0 && !higherIsBetter -> Pair(Icons.Default.ArrowUpward, Color(0xFFF44336))
        else -> Pair(Icons.Default.ArrowDownward, Color(0xFF4CAF50))
    }
}
