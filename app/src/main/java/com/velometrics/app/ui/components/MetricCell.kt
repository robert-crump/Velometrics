package com.velometrics.app.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
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

// Trend colors encode "better"/"worse" rather than an MD3 color-scheme role, so they stay
// fixed hex values rather than theme tokens — but they still need a light and a dark variant:
// the original single ColorWorse (a near-black brown, chosen only to read as "not green") had
// fine contrast on a light surface but was close to invisible against a dark theme's surface.
private val ColorBetterLight = Color(0xFF2E7D32)
private val ColorBetterDark = Color(0xFF81C784)
private val ColorWorseLight = Color(0xFF8B4513)
private val ColorWorseDark = Color(0xFFD7A86E)

/**
 * A label/value pair, optionally with a trend triangle comparing [current] against [reference]
 * (whichever comparison pool the caller has already selected — e.g. last 5 rides or all previous
 * rides). Used by the Ride and Repeated Interval detail screens.
 */
@Composable
fun MetricCell(
    label: String,
    value: String,
    current: Double? = null,
    reference: Double? = null,
    higherIsBetter: Boolean = true
) {
    val darkTheme = isSystemInDarkTheme()
    val triangle = remember(current, reference, higherIsBetter, darkTheme) {
        getTriangle(current, reference, higherIsBetter, darkTheme)
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
            triangle?.let { (icon, color) ->
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

private fun getTriangle(
    current: Double?,
    reference: Double?,
    higherIsBetter: Boolean,
    darkTheme: Boolean
): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color>? {
    if (current == null || reference == null || reference == 0.0 || current == reference) return null
    val isUp = current > reference
    val isBetter = isUp == higherIsBetter
    val color = when {
        isBetter && darkTheme -> ColorBetterDark
        isBetter -> ColorBetterLight
        darkTheme -> ColorWorseDark
        else -> ColorWorseLight
    }
    val icon = if (isUp) Icons.Filled.ArrowDropUp else Icons.Filled.ArrowDropDown
    return Pair(icon, color)
}
