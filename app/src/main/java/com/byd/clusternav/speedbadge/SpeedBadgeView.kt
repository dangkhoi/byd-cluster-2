package com.byd.clusternav.speedbadge

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.view.View
import com.byd.clusternav.contracts.SpeedSignType

/**
 * Canvas-drawn speed-limit badge (Vietnamese/EU regulatory style).
 * White circle, thick red ring, bold black number centered.
 * Designed for 120dp cluster overlay; scales to any measured size.
 */
class SpeedBadgeView(context: Context) : View(context) {

    var speedValue: Int = 0
        set(v) { field = v; invalidate() }

    var signType: SpeedSignType? = SpeedSignType.REGULATORY
        set(v) { field = v; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        if (speedValue <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(cx, cy)
        val ringWidth = radius * 0.15f

        // White filled circle
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, bgPaint)

        // Red ring
        ringPaint.strokeWidth = ringWidth
        canvas.drawCircle(cx, cy, radius - ringWidth / 2f, ringPaint)

        // Speed number
        val text = speedValue.toString()
        textPaint.textSize = radius * (if (text.length >= 3) 0.7f else 0.9f)
        val fm = textPaint.fontMetrics
        val textY = cy - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, textY, textPaint)
    }
}
