package com.byd.clusternav.navigation

/**
 * Pure decision for the per-segment diagnostic screenshot (T4 telemetry): (1) did the nav segment change,
 * and (2) has enough time elapsed since the last shot to fire again (debounce). Android-free so it is
 * unit-tested off-car; the Android capture + off-main executor + dadb screencap live in
 * [com.byd.clusternav.SegmentShotCapturer].
 *
 * "Segment change" mirrors the listener's existing log-on-change key (`dist|road|eta`) plus a maneuver-icon
 * change, so a screenshot is taken exactly when the cluster content the driver sees actually turns over —
 * not on every ~4 Hz notification heartbeat.
 */
object SegmentShotDecision {

    /** Minimum gap between two screenshots, ~3 s, so a burst of notifications yields at most one capture. */
    const val DEFAULT_MIN_GAP_MS = 3000L

    /**
     * Debounce: fire only if we never fired ([lastFireMs] <= 0) or at least [minGapMs] has elapsed since the
     * last fire. Monotonic clock (elapsedRealtime) expected; equal timestamps do not re-fire.
     */
    fun shouldFire(lastFireMs: Long, nowMs: Long, minGapMs: Long = DEFAULT_MIN_GAP_MS): Boolean =
        lastFireMs <= 0L || nowMs - lastFireMs >= minGapMs

    /**
     * Did the nav segment change? True when the `dist|road|eta` key differs from the previous emission, OR
     * when a VALID new maneuver icon (0..28) differs from the previous one. A held/invalid icon (outside
     * 0..28) never counts on its own — matching the listener's `ManeuverHold` semantics.
     */
    fun segmentChanged(
        prevKey: String?,
        newKey: String,
        prevManeuverIcon: Int,
        newManeuverIcon: Int,
    ): Boolean =
        newKey != prevKey || (newManeuverIcon in 0..28 && newManeuverIcon != prevManeuverIcon)
}
