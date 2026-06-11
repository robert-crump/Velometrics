package com.velometrics.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Minimal Google Maps-style distance scale: a horizontal bar whose width represents
 * [distanceLabel] of ground distance at the map's current zoom level.
 */
@Composable
fun MapScaleBar(
    widthDp: androidx.compose.ui.unit.Dp,
    distanceLabel: String,
    modifier: Modifier = Modifier
) {
    val textShadow = Shadow(color = Color.Black, blurRadius = 4f)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = distanceLabel,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                shadow = textShadow,
                textAlign = TextAlign.Center
            )
        )
        Box(
            modifier = Modifier
                .width(widthDp)
                .height(2.dp)
                .background(Color.White)
        )
    }
}
