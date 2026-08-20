package com.byd.clusternav

import com.byd.clusternav.navigation.Lane
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.screencapture.CameraMatch
import com.byd.clusternav.navigation.screencapture.ScreenCaptureSignal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit test [NavOutputOwner] (B3 T4) — thuần JVM: fake [NavOutputOwner.Sink] + đồng hồ tiêm + publish thẳng
 * vào [ScreenCaptureSignal] rồi gọi [NavOutputOwner.tick] theo thời điểm cố định. KHÔNG chạm Android/HAL.
 *
 * Khoá hành vi task T4: bắn arrow / lane / camera theo mức tươi (CỨ BẮN, độc lập); keep-alive re-assert mỗi
 * tick khi còn tươi; clear ĐÚNG một lần khi tất cả kênh stale; degrade-safe (sink ném không làm sập tick);
 * mũi tên suy từ amap khi maneuver null; bỏ bắn khi không có hướng hợp lệ (chống rẽ giả).
 */
class NavOutputOwnerTest {

    private class FakeSink : NavOutputOwner.Sink {
        var arrowCount = 0
        var lastArrowIcon = Int.MIN_VALUE
        var lastArrowSeg = Int.MIN_VALUE
        var laneCount = 0
        var lastLane: LaneInfo? = null
        var cameraCount = 0
        var lastCameraCode = Int.MIN_VALUE
        var lastCameraDist = Int.MIN_VALUE
        var clearCount = 0
        override fun pushArrow(icon: Int, segMeters: Int) { arrowCount++; lastArrowIcon = icon; lastArrowSeg = segMeters }
        override fun pushLane(info: LaneInfo) { laneCount++; lastLane = info }
        override fun pushCamera(iconCode: Int, distanceMeters: Int) { cameraCount++; lastCameraCode = iconCode; lastCameraDist = distanceMeters }
        override fun clear() { clearCount++ }
    }

    @BeforeEach fun reset() = ScreenCaptureSignal.clear()
    @AfterEach fun tearDown() = ScreenCaptureSignal.clear()

    private fun owner(sink: NavOutputOwner.Sink) = NavOutputOwner(sink = sink, clock = { 0L }, log = {})

    @Test fun `arrow-only — chi ban arrow, cu-ly = -1 (nguon anh khong co cu-ly)`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, sink.arrowCount)
        assertEquals(Maneuver.TURN_LEFT.toHudIcon(), sink.lastArrowIcon)   // CAN 1
        assertEquals(-1, sink.lastArrowSeg)
        assertEquals(0, sink.laneCount)
        assertEquals(0, sink.cameraCount)
        assertEquals(0, sink.clearCount)
    }

    @Test fun `lane+arrow — ban CA hai (doc lap, cu ban)`() {
        val sink = FakeSink()
        val info = LaneInfo(listOf(Lane(listOf(Maneuver.TURN_LEFT), false), Lane(listOf(Maneuver.STRAIGHT), true)))
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.STRAIGHT, amap = 9, now = 1_000L)
            ScreenCaptureSignal.publishLane("com.waze", info, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, sink.arrowCount)
        assertEquals(1, sink.laneCount)
        assertEquals(info, sink.lastLane)
        assertEquals(0, sink.cameraCount)
        assertEquals(0, sink.clearCount)
    }

    @Test fun `camera — ban icon + cu-ly, khong can arrow tuoi`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishCamera(
                "vn.vietmap.live",
                CameraMatch(hasCamera = true, score = 0.9f, templateName = "speed", distanceMeters = 200),
                now = 1_000L,
            )
            o.tick(1_000L)
        }
        assertEquals(1, sink.cameraCount)
        assertEquals(1, sink.lastCameraCode)      // hasCamera → 1
        assertEquals(200, sink.lastCameraDist)
        assertEquals(0, sink.arrowCount)
    }

    @Test fun `mui ten suy tu amap khi maneuver null`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", maneuver = null, amap = 3, now = 1_000L)  // AMAP 3 = TURN_RIGHT
            o.tick(1_000L)
        }
        assertEquals(1, sink.arrowCount)
        assertEquals(Maneuver.TURN_RIGHT.toHudIcon(), sink.lastArrowIcon)  // CAN 2
    }

    @Test fun `arrow tuoi nhung khong co huong hop le -- BO ban (chong re gia)`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", maneuver = null, amap = null, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(0, sink.arrowCount)
        assertEquals(0, sink.clearCount)
    }

    @Test fun `all-stale -- clear dung mot lan (idempotent)`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)                                  // active
            assertEquals(1, sink.arrowCount)
            o.tick(8_000L)                                  // 7s > STALE_MS(6s) -> clear
            assertEquals(1, sink.clearCount)
            assertEquals(1, sink.arrowCount)                // khong ban arrow khi stale
            o.tick(8_250L)                                  // van stale, da clear -> KHONG clear lai
            o.tick(8_500L)
            assertEquals(1, sink.clearCount)
        }
    }

    @Test fun `keep-alive -- con tuoi thi re-assert moi tick`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            o.tick(1_250L)   // con tuoi (250ms < 6s)
            o.tick(1_500L)   // con tuoi
            assertEquals(3, sink.arrowCount, "re-assert moi nhip khi con tuoi (keep-alive)")
            assertEquals(0, sink.clearCount)
        }
    }

    @Test fun `degrade-safe -- sink nem KHONG lam sap tick`() {
        val throwing = object : NavOutputOwner.Sink {
            override fun pushArrow(icon: Int, segMeters: Int) { throw RuntimeException("boom") }
            override fun pushLane(info: LaneInfo) { throw RuntimeException("boom") }
            override fun pushCamera(iconCode: Int, distanceMeters: Int) { throw RuntimeException("boom") }
            override fun clear() { throw RuntimeException("boom") }
        }
        NavOutputOwner(sink = throwing, clock = { 0L }, log = {}).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertDoesNotThrow { o.tick(8_000L) }   // clear path cung nem -> van khong sap
        }
    }
}
