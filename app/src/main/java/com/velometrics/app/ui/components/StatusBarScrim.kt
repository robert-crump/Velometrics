package com.velometrics.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** Alpha of the scrim at the top edge of the screen; it fades to 0 at its bottom edge. */
internal const val STATUS_BAR_SCRIM_TOP_ALPHA = 0.4f

/** The scrim reaches this many status bar heights down, so the fade ends below the icons. */
internal const val STATUS_BAR_SCRIM_HEIGHT_FACTOR = 1.5f

internal fun statusBarScrimHeight(statusBarHeight: Dp): Dp = statusBarHeight * STATUS_BAR_SCRIM_HEIGHT_FACTOR

/**
 * Soft dark gradient behind the status bar for screens whose map draws edge-to-edge (#240), so the
 * clock/battery icons stay readable over light map tiles. Place it above the map and below any
 * controls; it takes no touches.
 */
@Composable
fun StatusBarScrim(modifier: Modifier = Modifier) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(statusBarScrimHeight(statusBarHeight))
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = STATUS_BAR_SCRIM_TOP_ALPHA), Color.Transparent)
                )
            )
    )
}
