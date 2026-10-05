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
import androidx.compose.runtime.LaunchedEffect
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
private val FLOATING_HEADER_HORIZONTAL_PADDING = 12.dp
private val FLOATING_HEADER_TOP_PADDING = 8.dp

/** The docked header controls fade in over this last share of the way to full height (#241). */
internal const val DOCKED_HEADER_FADE_SPAN = 0.05f

/**
 * Alpha of the docked header controls at drawer [fraction] (#241). They stay hidden until the sheet's
 * top edge has passed [coverFraction] (where it covers the floating controls) and the last
 * [DOCKED_HEADER_FADE_SPAN] to full height has begun, then fade in to 1 at full height.
 */
internal fun dockedHeaderAlpha(fraction: Float, coverFraction: Float, lastSnap: Float = 1f): Float {
    val fadeStart = dockedHeaderFadeStart(coverFraction, lastSnap)
    return ((fraction - fadeStart) / (lastSnap - fadeStart)).coerceIn(0f, 1f)
}

/** Fraction at which the docked controls start fading in; the floating ones are composed below it. */
internal fun dockedHeaderFadeStart(coverFraction: Float, lastSnap: Float = 1f): Float =
    maxOf(lastSnap - DOCKED_HEADER_FADE_SPAN, coverFraction)
        .coerceAtMost(lastSnap - DOCKED_HEADER_FADE_SPAN / 5)

/**
 * A snappy pull-up drawer with configurable snap positions.
 *
 * - The handle row can always be dragged.
 * - [headerStart]/[headerEnd] (e.g. back arrow / overflow menu) float over the content, under the
 *   sheet in z-order, so the rising sheet covers them. Once covered, their docked versions fade into
 *   the handle row over the last few percent to full height (#241). Their `docked` argument tells
 *   the caller which of the two placements is being composed.
 * - Below 100%, the entire content area also acts as a drag handle
 *   (upward = expand, downward = collapse). Scrolling is only enabled at 100%.
 * - At 100% with scroll at top, a downward drag collapses the drawer.
 * - After snapping to 100% via body-drag, scrolling is blocked until a new touch begins.
 * - Early snap: 10% past the second-to-last position triggers a snap to the last position.
 * - [snapRequest] moves the drawer from code (e.g. lowering it to show a map marker, #230); each
 *   new request animates there and reports it through [onFractionSnapped].
 */
@Composable
fun PullUpDrawer(
    modifier: Modifier = Modifier,
    initialFraction: Float = 0.5f,
    snapFractions: List<Float> = listOf(0.15f, 0.50f, 1.00f),
    onFractionSnapped: (Float) -> Unit = {},
    snapRequest: DrawerSnapRequest? = null,
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

    LaunchedEffect(snapRequest) {
        val target = snapRequest?.fraction ?: return@LaunchedEffect
        currentFraction = target
        onFractionSnapped(target)
        animatedFraction.animateTo(target, animationSpec = snapSpec)
    }
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
        // The sheet covers the floating controls once its top edge reaches theirs.
        val floatingTopPx = with(density) { (statusBarTop + FLOATING_HEADER_TOP_PADDING).toPx() }
        val coverFraction = if (maxHeightPx > 0f) 1f - floatingTopPx / maxHeightPx else lastSnap
        val dockedAlpha = if (hasHeader) dockedHeaderAlpha(animatedFraction.value, coverFraction, lastSnap) else 0f
        val showFloating = hasHeader && animatedFraction.value < dockedHeaderFadeStart(coverFraction, lastSnap)

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

        // Drawn before the sheet so the sheet slides over them; fully opaque, no alpha layer (#241).
        if (showFloating) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(
                        start = FLOATING_HEADER_HORIZONTAL_PADDING,
                        end = FLOATING_HEADER_HORIZONTAL_PADDING,
                        top = FLOATING_HEADER_TOP_PADDING
                    )
            ) {
                Box(modifier = Modifier.align(Alignment.TopStart)) { headerStart?.invoke(false) }
                Box(modifier = Modifier.align(Alignment.TopEnd)) { headerEnd?.invoke(false) }
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
                    if (dockedAlpha > 0f) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = statusBarTop, start = 4.dp, end = 4.dp)
                                .graphicsLayer { alpha = dockedAlpha },
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
    }
}

/** A request to move a [PullUpDrawer] to [fraction]; not a data class, so repeating one re-applies it. */
class DrawerSnapRequest(val fraction: Float)
