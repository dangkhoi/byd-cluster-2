package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure view-model mapping for the cluster nav overlay (T5, spec b3-full-nav-capture R4/R5). Locks the
 * lane→cell mapping (glyph + bright/dim) and the camera label off-device, exactly like the arrow/lane
 * signature tests in :core.
 */
class NavOverlayModelTest {

    // Unicode glyphs the model emits (kept explicit so the mapping can't silently drift).
    private val up = "\u2191"       // ↑
    private val left = "\u2190"     // ←
    private val right = "\u2192"    // →
    private val slightL = "\u2196"  // ↖
    private val slightR = "\u2197"  // ↗

    @Test fun `glyph maps the core turn family`() {
        assertEquals(up, NavOverlayModel.glyph(Maneuver.STRAIGHT))
        assertEquals(left, NavOverlayModel.glyph(Maneuver.TURN_LEFT))
        assertEquals(right, NavOverlayModel.glyph(Maneuver.TURN_RIGHT))
        assertEquals(slightL, NavOverlayModel.glyph(Maneuver.SLIGHT_LEFT))
        assertEquals(slightR, NavOverlayModel.glyph(Maneuver.SLIGHT_RIGHT))
    }

    @Test fun `glyph is total — every Maneuver yields a non-empty glyph`() {
        // A new enum member without a glyph would fail to compile (exhaustive when) OR be caught blank here.
        for (m in Maneuver.values()) {
            assertTrue(NavOverlayModel.glyph(m).isNotEmpty(), "no glyph for $m")
        }
    }

    @Test fun `laneGlyph joins distinct directions and handles empty`() {
        assertEquals(up, NavOverlayModel.laneGlyph(listOf(Maneuver.STRAIGHT)))
        // straight OR right → combined, order preserved.
        assertEquals(up + right, NavOverlayModel.laneGlyph(listOf(Maneuver.STRAIGHT, Maneuver.TURN_RIGHT)))
        // duplicate directions collapse (STRAIGHT + CONTINUE both map to ↑).
        assertEquals(up, NavOverlayModel.laneGlyph(listOf(Maneuver.STRAIGHT, Maneuver.CONTINUE)))
        // unknown/empty → neutral placeholder.
        assertEquals(NavOverlayModel.UNKNOWN_GLYPH, NavOverlayModel.laneGlyph(emptyList()))
    }

    @Test fun `laneStrip is empty (hidden) for null or empty LaneInfo`() {
        assertTrue(NavOverlayModel.laneStrip(null).isEmpty)
        assertTrue(NavOverlayModel.laneStrip(LaneInfo.EMPTY).isEmpty)
    }

    @Test fun `laneStrip renders the owner 4-lane straight example — inner two bright, outer two dim`() {
        // Owner example: map says go STRAIGHT ⇒ [left dim · straight bright · straight bright · right dim].
        val info = LaneInfo(
            listOf(
                Lane(listOf(Maneuver.TURN_LEFT), recommended = false),
                Lane(listOf(Maneuver.STRAIGHT), recommended = true),
                Lane(listOf(Maneuver.STRAIGHT), recommended = true),
                Lane(listOf(Maneuver.TURN_RIGHT), recommended = false),
            ),
        )
        val strip = NavOverlayModel.laneStrip(info)
        assertFalse(strip.isEmpty)
        assertEquals(4, strip.count)
        assertEquals(listOf(left, up, up, right), strip.cells.map { it.glyph })
        assertEquals(listOf(false, true, true, false), strip.cells.map { it.recommended })
    }

    @Test fun `cameraLabel formats meters and km, blanks when absent`() {
        assertEquals("", NavOverlayModel.cameraLabel(null))
        assertEquals("", NavOverlayModel.cameraLabel(0))
        assertEquals("", NavOverlayModel.cameraLabel(-5))
        assertEquals("300 m", NavOverlayModel.cameraLabel(300))
        assertEquals("1.2 km", NavOverlayModel.cameraLabel(1200))
    }
}
