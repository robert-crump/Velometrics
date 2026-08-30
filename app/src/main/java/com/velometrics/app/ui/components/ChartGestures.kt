package com.velometrics.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Drag-to-select gesture shared by chart canvases that pick the nearest data point under the
 * finger: selects on initial touch-down, then re-selects continuously while the finger drags,
 * ending when the finger lifts. [onSelect] receives the horizontal offset (in px, relative to
 * this element) to translate into a selected index/value; [keys] restart the gesture handler the
 * same way [androidx.compose.ui.input.pointer.pointerInput]'s keys do.
 */
fun Modifier.dragToSelectGesture(vararg keys: Any?, onSelect: (Float) -> Unit): Modifier =
    this.pointerInput(*keys) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            onSelect(down.position.x)
            down.consume()
            var dragging = true
            while (dragging) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull()
                if (change != null && change.pressed) {
                    onSelect(change.position.x)
                    change.consume()
                } else {
                    dragging = false
                }
            }
        }
    }
