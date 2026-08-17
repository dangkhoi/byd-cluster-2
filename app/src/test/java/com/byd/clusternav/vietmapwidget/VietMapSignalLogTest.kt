package com.byd.clusternav.vietmapwidget

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pure-JVM contract test for the [VietMapSignalLog] CSV row shape (spec §4.4 C1). Verifies column
 * ORDER, count, boolean/null rendering and RFC-4180 escaping via the Android-free [VietMapSignalLog.buildRow]
 * / [VietMapSignalLog.buildViewsRow] builders — no Context, no file IO, no widget thread.
 */
class VietMapSignalLogTest {

    @Test
    fun `header lists the 13 signal columns in the exact contract order`() {
        assertEquals(
            "ts,freshness,providerVersion,currentSpeedKph,speedLimitKph," +
                "a1Limit,a1Dist,a1ImgVisible,a1ImgHash,a2Limit,a2Dist,a2ImgVisible,a2ImgHash",
            VietMapSignalLog.HEADER,
        )
        // Header column count == fields a fully-populated row emits (13).
        assertEquals(13, VietMapSignalLog.HEADER.split(",").size)
    }

    @Test
    fun `buildRow emits every column in header order with 13 fields`() {
        val row = VietMapSignalLog.buildRow(
            ts = 1234L,
            freshness = "FRESH",
            providerVersion = "3.2.1",
            currentSpeedKph = 60,
            speedLimitKph = 80,
            a1Limit = 50,
            a1Dist = "300 m",
            a1ImgVisible = true,
            a1ImgHash = "abcdef",
            a2Limit = 40,
            a2Dist = "1 km",
            a2ImgVisible = false,
            a2ImgHash = null,
        )
        assertEquals("1234,FRESH,3.2.1,60,80,50,300 m,true,abcdef,40,1 km,false,", row)
        // No embedded commas in this row → split gives exactly the 13 header columns.
        assertEquals(VietMapSignalLog.HEADER.split(",").size, row.split(",").size)
    }

    @Test
    fun `buildRow renders nulls as empty fields and booleans as true or false`() {
        val row = VietMapSignalLog.buildRow(
            ts = 0L,
            freshness = null,
            providerVersion = null,
            currentSpeedKph = null,
            speedLimitKph = null,
            a1Limit = null,
            a1Dist = null,
            a1ImgVisible = false,
            a1ImgHash = null,
            a2Limit = null,
            a2Dist = null,
            a2ImgVisible = false,
            a2ImgHash = null,
        )
        assertEquals("0,,,,,,,false,,,,false,", row)
    }

    @Test
    fun `buildRow RFC-4180 escapes fields containing commas and quotes`() {
        val row = VietMapSignalLog.buildRow(
            ts = 7L,
            freshness = "STALE",
            providerVersion = "v,1",
            currentSpeedKph = null,
            speedLimitKph = null,
            a1Limit = null,
            a1Dist = "1,2 km",
            a1ImgVisible = true,
            a1ImgHash = "a\"b",
            a2Limit = null,
            a2Dist = null,
            a2ImgVisible = false,
            a2ImgHash = null,
        )
        // "v,1" and "1,2 km" wrapped in quotes; a"b → quoted with the embedded quote doubled.
        assertEquals("7,STALE,\"v,1\",,,,\"1,2 km\",true,\"a\"\"b\",,,false,", row)
    }

    @Test
    fun `buildViewsRow joins the dump into one escaped field after the timestamp`() {
        val row = VietMapSignalLog.buildViewsRow(
            99L,
            listOf(
                "TV:osw_current_speed_tv=60",
                "IV:warning_alert_image=visible",
                "TV:some_tv=1,2",
            ),
        )
        // Joined with " | "; the embedded comma from "1,2" forces the whole dump field to be quoted.
        assertEquals(
            "99,\"TV:osw_current_speed_tv=60 | IV:warning_alert_image=visible | TV:some_tv=1,2\"",
            row,
        )
    }

    @Test
    fun `views header is ts and dump`() {
        assertEquals("ts,dump", VietMapSignalLog.VIEWS_HEADER)
    }
}
