package com.byd.clusternav.navigation.screencapture

import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.LaneInfo

/**
 * Kết quả nguồn ẢNH đã QUA trọng tài (`SourceArbiter.shouldFeed(..., NavChannel.IMAGE)` = true ⇒ data không
 * đè). Đây là SEAM mà đầu ra (làn cụm / HUD / badge camera — T7, on-car) đọc; ở lát B3 `:app` này ta CHỈ
 * capture→classify→trọng-tài→publish vào đây (KHÔNG tự ghi cụm — đó là T7, cần verify từng case trên xe).
 *
 * Thuần `@Volatile`, không khoá (một producer = luồng capture đơn; nhiều consumer đọc). Không Android → test được.
 *
 * ⚠ VERIFY-ON-CAR: giá trị thật (mũi tên nhận đúng khi capture Waze; camera match khi có template OQ4) tuỳ xe.
 * Camera hiện luôn NONE ở production (BUILTIN template rỗng) tới khi thu template trên xe (OQ4).
 */
object ScreenCaptureSignal {

    // ── ARROW (Waze/GMaps mũi tên) ────────────────────────────────────────────
    @Volatile var arrowPkg: String? = null; private set
    @Volatile var arrowManeuver: Maneuver? = null; private set
    @Volatile var arrowAmap: Int? = null; private set
    @Volatile var arrowAtMs: Long = 0L; private set

    /** Publish kết quả mũi tên đã qua trọng tài. [maneuver] = có hướng (vòng xuyến); [amap] = mã làn cụm. */
    fun publishArrow(pkg: String, maneuver: Maneuver?, amap: Int?, now: Long) {
        arrowPkg = pkg; arrowManeuver = maneuver; arrowAmap = amap; arrowAtMs = now
    }

    /** Mũi tên còn tươi không (consumer T7 quyết dùng). */
    fun arrowFresh(now: Long, staleMs: Long = STALE_MS): Boolean =
        arrowAtMs > 0L && now - arrowAtMs <= staleMs

    // ── CAMERA (VietMap phạt nguội) ─────────────────────────────────────────────
    @Volatile var cameraPkg: String? = null; private set
    @Volatile var cameraMatch: CameraMatch? = null; private set
    @Volatile var cameraAtMs: Long = 0L; private set

    fun publishCamera(pkg: String, match: CameraMatch, now: Long) {
        cameraPkg = pkg; cameraMatch = match; cameraAtMs = now
    }

    fun cameraFresh(now: Long, staleMs: Long = STALE_MS): Boolean =
        cameraAtMs > 0L && now - cameraAtMs <= staleMs

    // ── LANE (dải lane-guidance Waze/VietMap) ───────────────────────────────────
    @Volatile var lanePkg: String? = null; private set
    @Volatile var laneInfo: LaneInfo? = null; private set
    @Volatile var laneAtMs: Long = 0L; private set

    /** Publish dải làn đã đọc (đã qua trọng tài). [info] TRÁI→PHẢI; rỗng ⇒ không có lane-guidance. */
    fun publishLane(pkg: String, info: LaneInfo, now: Long) {
        lanePkg = pkg; laneInfo = info; laneAtMs = now
    }

    fun laneFresh(now: Long, staleMs: Long = STALE_MS): Boolean =
        laneAtMs > 0L && now - laneAtMs <= staleMs

    /** Xoá khi nav idle / nguồn dừng. */
    fun clear() {
        arrowPkg = null; arrowManeuver = null; arrowAmap = null; arrowAtMs = 0L
        cameraPkg = null; cameraMatch = null; cameraAtMs = 0L
        lanePkg = null; laneInfo = null; laneAtMs = 0L
    }

    /** Cùng ngưỡng tươi với `SourceArbiter.STALE_MS`. */
    const val STALE_MS = 6000L
}
