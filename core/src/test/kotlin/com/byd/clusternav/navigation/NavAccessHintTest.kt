package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Best-effort maneuver-hint extraction from the GMaps accessibility tree (T3). Diagnostics only — a wrong
 * or empty result must never reach the cluster — so these tests just pin the "which candidate wins" logic.
 */
class NavAccessHintTest {

    @Test
    fun `prefers a content description over screen text`() {
        val hint = NavAccessHint.maneuverHint(
            contentDescriptions = listOf("Turn right onto Nguyễn Huệ"),
            texts = listOf("Rẽ trái"),
        )
        assertEquals("Turn right onto Nguyễn Huệ", hint)
    }

    @Test
    fun `falls back to screen text when no description matches`() {
        val hint = NavAccessHint.maneuverHint(
            contentDescriptions = listOf("Google Maps", "12:30"),
            texts = listOf("250 m", "Rẽ phải vào Lê Lợi"),
        )
        assertEquals("Rẽ phải vào Lê Lợi", hint)
    }

    @Test
    fun `blank when nothing looks like a maneuver`() {
        val hint = NavAccessHint.maneuverHint(
            contentDescriptions = listOf("Google Maps", "Bản đồ"),
            texts = listOf("250 m", "8 phút"),
        )
        assertEquals("", hint)
    }

    @Test
    fun `recognises english and vietnamese directional cues`() {
        assertTrue(NavAccessHint.looksLikeManeuver("Keep left at the fork"))
        assertTrue(NavAccessHint.looksLikeManeuver("Đi thẳng 300 m"))
        assertTrue(NavAccessHint.looksLikeManeuver("Take exit 4"))
        assertTrue(NavAccessHint.looksLikeManeuver("Vào vòng xuyến, lối ra thứ 2"))
    }

    @Test
    fun `plain road names are not treated as maneuvers`() {
        assertFalse(NavAccessHint.looksLikeManeuver("Đường Trần Hưng Đạo"))
        assertFalse(NavAccessHint.looksLikeManeuver("250 m"))
        assertFalse(NavAccessHint.looksLikeManeuver(""))
    }
}
