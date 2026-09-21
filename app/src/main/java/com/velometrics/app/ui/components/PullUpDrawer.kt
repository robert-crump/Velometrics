package com.velometrics.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs

private val HANDLE_ROW_HEIGHT = 20.dp
private val HANDLE_TOP_PADDING = 8.dp
private val HANDLE_HEIGHT = 4.dp
private val HEADER_ROW_HEIGHT = 48.dp

/**
 * A snappy pull-up drawer with configurable snap positions.
 *
 * - The handle row can always be dragged.
 * - [headerStart]/[headerEnd] (e.g. back arrow / overflow menu) float over the content while the
 *   drawer is below full height and cross-fade into the handle row as the drawer reaches 100%.
 *   Their `docked` argument tells the caller which of the two placements is being composed.
 * - Below 100%, the entire content area also acts as a drag handle
 *   (upward = expand, downward = collapse). Scrolling is only enabled at 100%.
 * - At 100% with scroll at top, a downward drag collapses the drawer.
 * - After snapping to 100% via body-drag, scrolling is blocked until a new touch begins.
 * - Early snap: 10% past the second-to-last position triggers a snap to the last position.
 */
@Composable
fun PullUpDrawer(
    modifier: Modifier = Modifier,
    initialFraction: Float = 0.5f,
    snapFractions: List<Float> = listOf(0.15f, 0.50f, 1.00f),
    onFractionSnapped: (Float) -> Unit = {},
    headerStart: (@Composable (docked: Boolean) -> Unit)? = null,
    headerEnd: (@Composable (docked: Boolean) -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val firstSnap = snapFractions.first()
    val lastSnap = snapFractions.last()
    val secondToLastSnap = snapFractions[snapFractions.size - 2]
    val earlySnapThreshold = secondToLastSnap + (lastSnap - secondToLastSnap) * 0.10f

    fun computeSnapTarget(fraction: Float): Float {
        if (fraction >= earlySnapThreshold) return lastSnap
        return snapFractions.minByOrNull { abs(fraction - it) } ?: lastSnap
    }

    fun snapDown(fraction: Float): Float =
        snapFractions.lastOrNull { it < fraction } ?: firstSnap

    val snapSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val currentFractionState = remember { mutableStateOf(initialFraction) }
    var currentFraction by currentFractionState
    val animatedFraction = remember { Animatable(initialFraction) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()

    val blockScrollUntilNewTouch = remember { mutableStateOf(false) }
    val isCollapsingFromFull = remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val maxHeightPx = with(density) { maxHeight.toPx() }
        val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // 0 at or below the second-to-last snap, 1 at full height.
        val fullness = ((animatedFraction.value - secondToLastSnap) / (lastSnap - secondToLastSnap))
            .coerceIn(0f, 1f)
        val hasHeader = headerStart != null || headerEnd != null
        val headerFullness = if (hasHeader) fullness else 0f
        val handleRowHeight: Dp =
            HANDLE_ROW_HEIGHT + (HEADER_ROW_HEIGHT + statusBarTop - HANDLE_ROW_HEIGHT) * headerFullness

        val drawerNestedScrollConnection = remember(maxHeightPx, snapFractions) {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    val fraction = currentFractionState.value

                    if (fraction < 1.0f && available.y < 0f) {
                        val newFraction = (fraction - available.y / maxHeightPx)
                            .coerceIn(firstSnap, lastSnap)
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        if (newFraction >= 1.0f) blockScrollUntilNewTouch.value = true
                        return available
                    }

                    if (fraction >= 1.0f && available.y > 0f && scrollState.value == 0) {
                        isCollapsingFromFull.value = true
                        val newFraction = (fraction - available.y / maxHeightPx)
                            .coerceIn(firstSnap, lastSnap)
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        return available
                    }

                    if (fraction < 1.0f && available.y > 0f && isCollapsingFromFull.value) {
                        val newFraction = (fraction - available.y / maxHeightPx)
                            .coerceIn(firstSnap, lastSnap)
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        return available
                    }

                    if (fraction < 1.0f && fraction > firstSnap &&
                        available.y > 0f && !isCollapsingFromFull.value
                    ) {
                        val newFraction = (fraction - available.y / maxHeightPx)
                            .coerceIn(firstSnap, lastSnap)
                        currentFractionState.value = newFraction
                        scope.launch { animatedFraction.snapTo(newFraction) }
                        return available
                    }

                    if (fraction >= 1.0f && available.y < 0f && blockScrollUntilNewTouch.value) {
                        return available
                    }

                    return Offset.Zero
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val fraction = currentFractionState.value

                    if (isCollapsingFromFull.value && available.y > 0f) {
                        isCollapsingFromFull.value = false
                        val target = snapDown(fraction)
                        currentFractionState.value = target
                        onFractionSnapped(target)
                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                        return available
                    }

                    if (fraction < 1.0f) {
                        isCollapsingFromFull.value = false
                        val target = if (available.y > 0f && fraction > firstSnap) {
                            snapDown(fraction)
                        } else {
                            computeSnapTarget(fraction)
                        }
                        currentFractionState.value = target
                        onFractionSnapped(target)
                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                        if (target >= 1.0f) blockScrollUntilNewTouch.value = true
                        return available
                    }

                    if (fraction >= 1.0f && available.y > 0f && scrollState.value == 0) {
                        val target = secondToLastSnap
                        currentFractionState.value = target
                        onFractionSnapped(target)
                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                        return available
                    }

                    return Velocity.Zero
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(with(density) { (maxHeightPx * animatedFraction.value).toDp() })
                .background(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(handleRowHeight)
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    val newFraction = (currentFraction - dragAmount.y / maxHeightPx)
                                        .coerceIn(firstSnap, lastSnap)
                                    currentFraction = newFraction
                                    scope.launch { animatedFraction.snapTo(newFraction) }
                                },
                                onDragEnd = {
                                    val target = computeSnapTarget(currentFraction)
                                    currentFraction = target
                                    onFractionSnapped(target)
                                    scope.launch {
                                        animatedFraction.animateTo(target, animationSpec = snapSpec)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.TopCenter
                ) {
                    Spacer(
                        modifier = Modifier
                            .padding(
                                top = HANDLE_TOP_PADDING +
                                    (statusBarTop + (HEADER_ROW_HEIGHT - HANDLE_HEIGHT) / 2 - HANDLE_TOP_PADDING) * headerFullness
                            )
                            .width(40.dp)
                            .height(HANDLE_HEIGHT)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape
                            )
                    )
                    if (headerFullness > 0f) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = statusBarTop, start = 4.dp, end = 4.dp)
                                .graphicsLayer { alpha = headerFullness },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Box { headerStart?.invoke(true) }
                            Box { headerEnd?.invoke(true) }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                blockScrollUntilNewTouch.value = false
                                isCollapsingFromFull.value = false
                            }
                        }
                        .nestedScroll(drawerNestedScrollConnection)
                        .verticalScroll(scrollState),
                    content = content
                )
            }
        }

        if (hasHeader && fullness < 1f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .graphicsLayer { alpha = 1f - fullness }
            ) {
                Box(modifier = Modifier.align(Alignment.TopStart)) { headerStart?.invoke(false) }
                Box(modifier = Modifier.align(Alignment.TopEnd)) { headerEnd?.invoke(false) }
            }
        }
    }
}
