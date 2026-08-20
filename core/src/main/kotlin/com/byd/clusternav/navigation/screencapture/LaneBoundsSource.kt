package com.byd.clusternav.navigation.screencapture

/**
 * B3 T1b — bounds của VIEW lane-guidance (a11y `findAccessibilityNodeInfosByViewId`, vd
 * `com.waze:id/laneGuidanceView`) + số làn (child count) — do `NavAccessibilityService` GHI, đọc bởi
 * `ScreenCaptureNavSource` để crop đúng vùng dải làn → [com.byd.clusternav.navigation.LaneSignature].
 *
 * Thuần `@Volatile`, không khoá (a11y ghi, capture đọc) — cùng khuôn [CaptureBoundsSource]. Toạ độ TUYỆT ĐỐI
 * trong không gian ảnh display. Chưa publish / rỗng → snapshot null → không đọc làn (degrade-safe).
 */
object LaneBoundsSource {

    const val FRESH_MS = 1500L

    @Volatile private var left = 0
    @Volatile private var top = 0
    @Volatile private var right = 0
    @Volatile private var bottom = 0

    /** Số làn (child count của view lane-guidance) — 0 nếu không rõ (LaneSignature tự dò). */
    @Volatile var laneCount: Int = 0
        private set

    @Volatile private var capturedAtMs = 0L

    fun publish(l: Int, t: Int, r: Int, b: Int, lanes: Int, now: Long) {
        left = l; top = t; right = r; bottom = b; laneCount = lanes; capturedAtMs = now
    }

    /** Snapshot vùng dải làn. null nếu chưa publish / rỗng. Caller tự chấm freshness (`now - capturedAtMs`). */
    fun snapshot(): CaptureBounds? {
        if (capturedAtMs <= 0L) return null
        val rect = CropRect(left, top, right, bottom)
        if (rect.isEmpty()) return null
        return CaptureBounds(rect, capturedAtMs)
    }

    fun clear() {
        left = 0; top = 0; right = 0; bottom = 0; laneCount = 0; capturedAtMs = 0L
    }
}
