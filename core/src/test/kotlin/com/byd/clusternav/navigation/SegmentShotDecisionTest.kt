package com.byd.clusternav.navigation

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure decision behind the T4 per-turn screenshot: fire once per segment change, debounced to ~3 s so a
 * burst of GMaps notifications can't spam the dadb screencap path (which is off-main + degrade-safe but
 * still a shell round-trip we don't want to hammer while driving).
 */
class SegmentShotDecisionTest {

    // ── debounce ─────────────────────────────────────────────────────────────
    @Test
    fun `first ever fire is allowed`() {
        assertTrue(SegmentShotDecision.shouldFire(lastFireMs = 0L, nowMs = 12_345L, minGapMs = 3000L))
    }

    @Test
    fun `within the gap is suppressed`() {
        assertFalse(SegmentShotDecision.shouldFire(lastFireMs = 10_000L, nowMs = 11_500L, minGapMs = 3000L))
    }

    @Test
    fun `exactly at the gap fires`() {
        assertTrue(SegmentShotDecision.shouldFire(lastFireMs = 10_000L, nowMs = 13_000L, minGapMs = 3000L))
    }

    @Test
    fun `after the gap fires`() {
        assertTrue(SegmentShotDecision.shouldFire(lastFireMs = 10_000L, nowMs = 20_000L, minGapMs = 3000L))
    }

    @Test
    fun `equal timestamps do not re-fire`() {
        assertFalse(SegmentShotDecision.shouldFire(lastFireMs = 10_000L, nowMs = 10_000L, minGapMs = 3000L))
    }

    // ── segment change ─────────────────────────────────────────────────────────
    @Test
    fun `nav key change is a segment change`() {
        assertTrue(
            SegmentShotDecision.segmentChanged(
                prevKey = "250 m|Nguyễn Huệ|10:30", newKey = "180 m|Nguyễn Huệ|10:30",
                prevManeuverIcon = 2, newManeuverIcon = 2,
            ),
        )
    }

    @Test
    fun `first segment (null prev key) is a change`() {
        assertTrue(
            SegmentShotDecision.segmentChanged(
                prevKey = null, newKey = "250 m|Nguyễn Huệ|10:30",
                prevManeuverIcon = -1, newManeuverIcon = -1,
            ),
        )
    }

    @Test
    fun `valid maneuver-icon change is a segment change even if the key is identical`() {
        assertTrue(
            SegmentShotDecision.segmentChanged(
                prevKey = "250 m|Nguyễn Huệ|10:30", newKey = "250 m|Nguyễn Huệ|10:30",
                prevManeuverIcon = 2, newManeuverIcon = 3,
            ),
        )
    }

    @Test
    fun `identical key and icon is NOT a change (heartbeat)`() {
        assertFalse(
            SegmentShotDecision.segmentChanged(
                prevKey = "250 m|Nguyễn Huệ|10:30", newKey = "250 m|Nguyễn Huệ|10:30",
                prevManeuverIcon = 2, newManeuverIcon = 2,
            ),
        )
    }

    @Test
    fun `an out-of-range icon change alone is NOT a segment change (held frame)`() {
        assertFalse(
            SegmentShotDecision.segmentChanged(
                prevKey = "250 m|Nguyễn Huệ|10:30", newKey = "250 m|Nguyễn Huệ|10:30",
                prevManeuverIcon = 2, newManeuverIcon = -1,
            ),
        )
    }
}
