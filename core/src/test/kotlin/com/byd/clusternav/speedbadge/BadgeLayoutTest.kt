package com.byd.clusternav.speedbadge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the badge placement math so the on-car corner/size UI can't silently drift.
 *
 * The four gravity values are the exact Android bitmasks the overlay assigns to `lp.gravity`
 * (android.view.Gravity: TOP=0x30, BOTTOM=0x50, LEFT=0x03, RIGHT=0x05). If someone renumbers a corner id
 * or breaks the OR, a driver's "move the badge" tap would send it to the wrong edge — caught here off-car.
 */
class BadgeLayoutTest {

    @Test
    fun `top-left corner maps to TOP or LEFT`() {
        assertEquals(0x30 or 0x03, BadgeLayout.gravityForCorner(BadgeLayout.CORNER_TOP_LEFT))
        assertEquals(0x33, BadgeLayout.gravityForCorner(0))
    }

    @Test
    fun `top-right corner maps to TOP or RIGHT`() {
        assertEquals(0x30 or 0x05, BadgeLayout.gravityForCorner(BadgeLayout.CORNER_TOP_RIGHT))
        assertEquals(0x35, BadgeLayout.gravityForCorner(1))
    }

    @Test
    fun `bottom-left corner maps to BOTTOM or LEFT`() {
        assertEquals(0x50 or 0x03, BadgeLayout.gravityForCorner(BadgeLayout.CORNER_BOTTOM_LEFT))
        assertEquals(0x53, BadgeLayout.gravityForCorner(2))
    }

    @Test
    fun `bottom-right corner maps to BOTTOM or RIGHT`() {
        assertEquals(0x50 or 0x05, BadgeLayout.gravityForCorner(BadgeLayout.CORNER_BOTTOM_RIGHT))
        assertEquals(0x55, BadgeLayout.gravityForCorner(3))
    }

    @Test
    fun `out-of-range corner degrades to the default TOP-RIGHT`() {
        val defaultGravity = BadgeLayout.gravityForCorner(BadgeLayout.CORNER_DEFAULT)
        assertEquals(defaultGravity, BadgeLayout.gravityForCorner(-1))
        assertEquals(defaultGravity, BadgeLayout.gravityForCorner(99))
        assertEquals(0x35, defaultGravity)
    }

    @Test
    fun `clampCorner keeps valid ids and snaps invalid ones to default`() {
        assertEquals(0, BadgeLayout.clampCorner(0))
        assertEquals(1, BadgeLayout.clampCorner(1))
        assertEquals(2, BadgeLayout.clampCorner(2))
        assertEquals(3, BadgeLayout.clampCorner(3))
        assertEquals(BadgeLayout.CORNER_DEFAULT, BadgeLayout.clampCorner(-5))
        assertEquals(BadgeLayout.CORNER_DEFAULT, BadgeLayout.clampCorner(4))
    }

    @Test
    fun `clampSizeDp clamps below, within, and above the bounds`() {
        assertEquals(60, BadgeLayout.clampSizeDp(10))     // below min → min
        assertEquals(60, BadgeLayout.clampSizeDp(60))     // at min
        assertEquals(120, BadgeLayout.clampSizeDp(120))   // default, within range
        assertEquals(240, BadgeLayout.clampSizeDp(240))   // at max
        assertEquals(240, BadgeLayout.clampSizeDp(1000))  // above max → max
    }

    @Test
    fun `size bounds and default are the documented values`() {
        assertEquals(60, BadgeLayout.SIZE_MIN_DP)
        assertEquals(240, BadgeLayout.SIZE_MAX_DP)
        assertEquals(120, BadgeLayout.SIZE_DEFAULT_DP)
    }
}
