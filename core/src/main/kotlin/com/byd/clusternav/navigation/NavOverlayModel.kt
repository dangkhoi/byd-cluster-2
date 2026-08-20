package com.byd.clusternav.navigation

/**
 * PURE view-model for the cluster NAV OVERLAY (T5, spec `b3-full-nav-capture` R4/R5/R6). Lives in :core (JVM
 * only, no Android — enforced by `LayeringRulesTest`) so, exactly like `ManeuverSignature`/`LaneSignature`, ALL
 * rendering decisions (which glyph, bright vs dim, hide-when-empty) are unit-testable off-device. The Android
 * overlay Views in :app (`navoverlay/`) only render the instructions produced here.
 *
 * R4 — lane strip: each lane draws its arrow glyph(s) left→right; RECOMMENDED lanes BRIGHT, the rest DIMMED
 * (owner example: 4 lanes `[left dim · straight bright · straight bright · right dim]`).
 * R5 — camera: an icon + distance to the RIGHT of the speed sign (distance may be absent this cycle — OQ4).
 * Degrade-safe: a null/empty [LaneInfo] maps to [LaneStripModel.EMPTY] (the strip hides).
 */

/** One lane cell to draw: its combined arrow [glyph] + whether it is a RECOMMENDED (bright) lane. */
data class LaneCell(val glyph: String, val recommended: Boolean)

/** Ordered (left→right) lane strip to draw. Empty ⇒ hide the strip (degrade-safe, R-nf2). */
data class LaneStripModel(val cells: List<LaneCell>) {
    val isEmpty: Boolean get() = cells.isEmpty()
    val count: Int get() = cells.size

    companion object {
        val EMPTY = LaneStripModel(emptyList())
    }
}

object NavOverlayModel {

    /** Glyph for a lane whose arrows are unknown (degrade-safe placeholder, still drawn dim/bright per lane). */
    const val UNKNOWN_GLYPH = "•"

    /**
     * Map a single [Maneuver] to a widely-supported Unicode arrow glyph (BMP arrows — render on the stock
     * cluster/system font, no asset needed). Lane guidance is dominated by the plain turn family; the richer
     * members (ramp/fork/keep ≈ slight; roundabout family ≈ ↻; tunnel/toll/service ≈ straight) fall back to the
     * closest directional glyph so the strip is always drawable.
     */
    fun glyph(m: Maneuver): String = when (m) {
        Maneuver.STRAIGHT, Maneuver.CONTINUE, Maneuver.MERGE,
        Maneuver.TUNNEL, Maneuver.SERVICE_AREA, Maneuver.TOLL -> "\u2191"          // ↑
        Maneuver.TURN_LEFT -> "\u2190"                                              // ←
        Maneuver.TURN_RIGHT -> "\u2192"                                             // →
        Maneuver.SLIGHT_LEFT, Maneuver.RAMP_LEFT, Maneuver.FORK_LEFT,
        Maneuver.KEEP_LEFT -> "\u2196"                                              // ↖
        Maneuver.SLIGHT_RIGHT, Maneuver.RAMP_RIGHT, Maneuver.FORK_RIGHT,
        Maneuver.KEEP_RIGHT -> "\u2197"                                             // ↗
        Maneuver.SHARP_LEFT -> "\u2199"                                             // ↙
        Maneuver.SHARP_RIGHT -> "\u2198"                                            // ↘
        Maneuver.UTURN -> "\u21A9"                                                  // ↩
        Maneuver.UTURN_RIGHT -> "\u21AA"                                            // ↪
        Maneuver.DESTINATION -> "\u2691"                                            // ⚑
        Maneuver.WAYPOINT -> "\u25C6"                                               // ◆
        Maneuver.ROUNDABOUT, Maneuver.ROUNDABOUT_EXIT,
        Maneuver.ROUNDABOUT_LEFT, Maneuver.ROUNDABOUT_RIGHT,
        Maneuver.ROUNDABOUT_STRAIGHT, Maneuver.ROUNDABOUT_UTURN,
        Maneuver.ROUNDABOUT_LEFT_CW, Maneuver.ROUNDABOUT_RIGHT_CW,
        Maneuver.ROUNDABOUT_STRAIGHT_CW, Maneuver.ROUNDABOUT_UTURN_CW -> "\u21BB"   // ↻
    }

    /**
     * Combined glyph for one lane's [arrows] (a lane can allow several directions, e.g. straight OR right →
     * "↑→"). Distinct + order-preserving so duplicates collapse; an empty list ⇒ [UNKNOWN_GLYPH].
     */
    fun laneGlyph(arrows: List<Maneuver>): String {
        if (arrows.isEmpty()) return UNKNOWN_GLYPH
        return arrows.map { glyph(it) }.distinct().joinToString("")
    }

    /**
     * Build the left→right lane strip from a (nullable) [LaneInfo]. Null or empty ⇒ [LaneStripModel.EMPTY]
     * (strip hidden). Each lane becomes a [LaneCell] carrying its glyph(s) and its recommended flag (bright).
     */
    fun laneStrip(info: LaneInfo?): LaneStripModel {
        if (info == null || info.isEmpty()) return LaneStripModel.EMPTY
        return LaneStripModel(info.lanes.map { LaneCell(laneGlyph(it.arrows), it.recommended) })
    }

    /**
     * Camera countdown label (R5). Reuses the shared [NavParse.formatMeters] ("250 m" / "1.2 km") so the overlay
     * text matches every other distance the app renders. A null/≤0 distance ⇒ "" (icon only — the common case
     * this cycle, since OCR of the camera distance is OQ4/on-car).
     */
    fun cameraLabel(distanceMeters: Int?): String =
        if (distanceMeters != null && distanceMeters > 0) NavParse.formatMeters(distanceMeters) else ""
}
