package com.cyclegraph.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * A snappy pull-up drawer with configurable snap positions.
 *
 * - The 44dp handle can always be dragged.
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
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
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
                    contentAlignment = Alignment.Center
                ) {
                    Spacer(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                CircleShape
                            )
                    )
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
    }
}
