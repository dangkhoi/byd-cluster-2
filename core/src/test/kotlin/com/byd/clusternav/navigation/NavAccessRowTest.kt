package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the `nav_access` telemetry CSV shape (multi-source voice-guidance capture). The SOURCE `pkg` column
 * is what lets GMaps / VietMap / Waze / WazeMod rows be told apart off-car, and the `text` column carries the
 * announced guidance for the three that post no nav notifications — so a column shift here would silently
 * merge the sources back together. Off-car unit test; the Android file I/O lives in :app NavAccessLog.
 */
class NavAccessRowTest {

    @Test
    fun `header carries the source package and text columns in order`() {
        assertEquals("t_ms,pkg,screenRead_m,screenRead_road,screenRead_maneuverHint,text", NavAccessRow.HEADER)
        // Header column count must equal the row column count.
        val row = NavAccessRow.row(1L, "com.google.android.apps.maps", 250, "Lê Lợi", "Turn right", "")
        assertEquals(NavAccessRow.HEADER.split(",").size, row.split(",").size)
    }

    @Test
    fun `gmaps screen-scan row keeps distance ground-truth and blank text`() {
        val row = NavAccessRow.row(
            tMs = 123L,
            pkg = "com.google.android.apps.maps",
            screenReadMeters = 250,
            road = "Lê Lợi",
            maneuverHint = "Turn right",
            text = "",
        )
        assertEquals("123,com.google.android.apps.maps,250,Lê Lợi,Turn right,", row)
    }

    @Test
    fun `vietmap announcement row is source-tagged with the spoken text and no distance`() {
        val row = NavAccessRow.row(
            tMs = 456L,
            pkg = "vn.vietmap.live",
            screenReadMeters = NavAccessRow.NO_METERS,
            road = "",
            maneuverHint = "",
            text = "Còn 300 mét rẽ phải",
        )
        assertEquals("456,vn.vietmap.live,-1,,,Còn 300 mét rẽ phải", row)
    }

    @Test
    fun `waze and wazemod rows are distinguishable by package`() {
        val waze = NavAccessRow.row(789L, "com.waze", NavAccessRow.NO_METERS, "", "", "Turn left")
        val wazemod = NavAccessRow.row(789L, "com.chisadin.wazemod", NavAccessRow.NO_METERS, "", "", "Turn left")
        assertTrue(waze.contains(",com.waze,"))
        assertTrue(wazemod.contains(",com.chisadin.wazemod,"))
        // Same guidance, different source → different rows.
        assertTrue(waze != wazemod)
    }

    @Test
    fun `text with a comma is quoted so the source columns never shift`() {
        val row = NavAccessRow.row(
            tMs = 10L,
            pkg = "com.waze",
            screenReadMeters = NavAccessRow.NO_METERS,
            road = "",
            maneuverHint = "",
            text = "In 500 m, turn right",
        )
        // The comma inside the guidance text is quoted → still exactly 6 logical columns.
        assertEquals("10,com.waze,-1,,,\"In 500 m, turn right\"", row)
    }
}
