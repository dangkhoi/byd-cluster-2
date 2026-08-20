package com.byd.clusternav.navoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/**
 * Canvas-drawn CAMERA chip for the cluster nav overlay (T5, spec `b3-full-nav-capture` R5/R6): a speed/red-light
 * camera icon with an optional distance label, placed to the RIGHT of the speed sign by [NavClusterOverlay].
 * The icon is drawn from primitives (rounded body + lens + viewfinder bump) so it needs no drawable asset. When
 * [hasCamera] is false it draws nothing (the overlay hides the window). [label] is the countdown distance
 * ("300 m"), often empty this cycle since camera-distance OCR is on-car/OQ4 — then only the icon shows.
 */
class CameraChipView(context: Context) : View(context) {

    var hasCamera: Boolean = false
        set(v) { field = v; invalidate() }

    /** Countdown distance text (e.g. "300 m"); empty ⇒ icon only. */
    var label: String = ""
        set(v) { field = v; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG_COLOR
        style = Paint.Style.FILL
    }
    private val camBodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.FILL
    }
    private val lensPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val lensHolePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        if (!hasCamera) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Translucent rounded backdrop.
        val radius = w * 0.16f
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, bgPaint)

        val hasLabel = label.isNotEmpty()
        // Icon occupies the top ~70% when a label is present, else it is centred.
        val iconAreaH = if (hasLabel) h * 0.66f else h
        val iconSize = minOf(w, iconAreaH) * 0.66f
        val iconCx = w / 2f
        val iconCy = if (hasLabel) iconAreaH / 2f else h / 2f

        // Camera body (rounded rect).
        val bodyHalfW = iconSize / 2f
        val bodyHalfH = iconSize * 0.36f
        rect.set(iconCx - bodyHalfW, iconCy - bodyHalfH, iconCx + bodyHalfW, iconCy + bodyHalfH)
        canvas.drawRoundRect(rect, iconSize * 0.12f, iconSize * 0.12f, camBodyPaint)
        // Viewfinder bump on top-left of the body.
        rect.set(
            iconCx - bodyHalfW * 0.55f, iconCy - bodyHalfH - iconSize * 0.12f,
            iconCx - bodyHalfW * 0.05f, iconCy - bodyHalfH + 1f,
        )
        canvas.drawRoundRect(rect, iconSize * 0.05f, iconSize * 0.05f, camBodyPaint)
        // Lens: white disc + accent hole.
        val lensR = bodyHalfH * 0.78f
        canvas.drawCircle(iconCx, iconCy, lensR, lensPaint)
        canvas.drawCircle(iconCx, iconCy, lensR * 0.5f, lensHolePaint)

        if (hasLabel) {
            textPaint.textSize = h * 0.24f
            val fm = textPaint.fontMetrics
            val labelBaseline = iconAreaH + (h - iconAreaH) / 2f - (fm.ascent + fm.descent) / 2f
            canvas.drawText(label, w / 2f, labelBaseline, textPaint)
        }
    }

    private companion object {
        /** Translucent black backdrop (~69% opacity). */
        private const val BG_COLOR = 0xB0000000.toInt()
        /** Warning accent (amber-red) for the camera body + lens hole. */
        private const val ACCENT = 0xFFE53935.toInt()
    }
}
