package com.velometrics.app.ui.components

import android.graphics.Paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb

/**
 * The one place chart axis/tick text is set up and drawn with `android.graphics.Paint`. Create it
 * inside a Canvas draw block with the text size in px, then call [draw] per label — colour and
 * alignment can change between calls (e.g. to highlight the selected label).
 */
class ChartLabelPainter(textSizePx: Float, bold: Boolean = false) {
    private val paint = Paint().apply {
        textSize = textSizePx
        isAntiAlias = true
        isFakeBoldText = bold
    }

    val textSize: Float get() = paint.textSize

    fun measure(text: String): Float = paint.measureText(text)

    fun draw(
        scope: DrawScope,
        text: String,
        x: Float,
        y: Float,
        color: Color,
        align: Paint.Align = Paint.Align.CENTER
    ) {
        paint.color = color.toArgb()
        paint.textAlign = align
        scope.drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
    }

    /** Draws [text] centred on ([cx], [cy]) rotated to read bottom-to-top. */
    fun drawVertical(scope: DrawScope, text: String, cx: Float, cy: Float, color: Color) {
        val canvas = scope.drawContext.canvas.nativeCanvas
        paint.color = color.toArgb()
        paint.textAlign = Paint.Align.CENTER
        canvas.save()
        canvas.rotate(-90f, cx, cy)
        canvas.drawText(text, cx, cy, paint)
        canvas.restore()
    }
}
