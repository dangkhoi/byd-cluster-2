package com.byd.clusternav.navoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.byd.clusternav.navigation.LaneStripModel

/**
 * Canvas-drawn LANE STRIP for the cluster nav overlay (T5, spec `b3-full-nav-capture` R4/R6). Draws the lanes
 * LEFT→RIGHT on a translucent dark pill (so the glyphs read over any cast/map background), each lane showing its
 * arrow glyph(s); RECOMMENDED lanes are BRIGHT (white), the rest DIMMED (semi-transparent). Owner example — 4
 * lanes when the route goes straight: `[← dim · ↑ bright · ↑ bright · → dim]`.
 *
 * Pure geometry only — WHAT to draw comes from [NavOverlayModel] (unit-tested); this View just renders the
 * [LaneStripModel]. Empty model ⇒ draws nothing (the overlay hides the window). Scales to any measured size.
 */
class LaneStripView(context: Context) : View(context) {

    var model: LaneStripModel = LaneStripModel.EMPTY
        set(v) { field = v; invalidate() }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BG_COLOR
        style = Paint.Style.FILL
    }
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val bgRect = RectF()

    override fun onDraw(canvas: Canvas) {
        val cells = model.cells
        if (cells.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Translucent rounded backdrop for legibility over the map/cast surface.
        val radius = h * 0.18f
        bgRect.set(0f, 0f, w, h)
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint)

        val cellW = w / cells.size
        glyphPaint.textSize = h * 0.6f
        val fm = glyphPaint.fontMetrics
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f

        for (i in cells.indices) {
            val cell = cells[i]
            glyphPaint.color = if (cell.recommended) BRIGHT else DIM
            val cx = cellW * i + cellW / 2f
            canvas.drawText(cell.glyph, cx, baseline, glyphPaint)
        }
    }

    private companion object {
        /** Translucent black pill behind the glyphs (~69% opacity). */
        private const val BG_COLOR = 0xB0000000.toInt()
        /** Recommended lane — bright white. */
        private val BRIGHT = Color.WHITE
        /** Non-recommended lane — dimmed (semi-transparent white). */
        private const val DIM = 0x66FFFFFF
    }
}
