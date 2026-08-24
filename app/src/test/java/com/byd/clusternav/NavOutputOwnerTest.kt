package com.byd.clusternav

import com.byd.clusternav.navigation.Lane
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavApps
import com.byd.clusternav.navigation.NavViewIdSource
import com.byd.clusternav.navigation.TurnDistancePlausibility
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
        var blankCount = 0
        override fun pushArrow(icon: Int, segMeters: Int) { arrowCount++; lastArrowIcon = icon; lastArrowSeg = segMeters }
        override fun pushLane(info: LaneInfo) { laneCount++; lastLane = info }
        override fun pushCamera(iconCode: Int, distanceMeters: Int) { cameraCount++; lastCameraCode = iconCode; lastCameraDist = distanceMeters }
        override fun blankDistance() { blankCount++ }
        override fun clear() { clearCount++ }
    }

    // NavViewIdSource là holder TOÀN CỤC (object) — không dọn thì cự-ly của test trước rò sang test sau và
    // ca "seg = -1" đỏ/xanh tuỳ thứ tự chạy.
    @BeforeEach fun reset() { ScreenCaptureSignal.clear(); NavViewIdSource.clear() }
    @AfterEach fun tearDown() { ScreenCaptureSignal.clear(); NavViewIdSource.clear() }

    /**
     * B-III: [speed] mặc định `null` (= HAL tốc độ câm) để test KHÔNG chạm reflection `SpeedProvider`; [guard]
     * tiêm được để test nào không nói về warmup thì khỏi phải dựng 3 mẫu.
     */
    private fun owner(
        sink: NavOutputOwner.Sink,
        guard: TurnDistancePlausibility = TurnDistancePlausibility(),
        speed: () -> Double? = { null },
    ) = NavOutputOwner(sink = sink, clock = { 0L }, log = {}, speed = speed, guard = guard)

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
            override fun blankDistance() { throw RuntimeException("boom") }
            override fun clear() { throw RuntimeException("boom") }
        }
        owner(throwing).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertDoesNotThrow { o.tick(8_000L) }   // clear path cung nem -> van khong sap
        }
    }

    // ── §R-BI: BẤT BIẾN MỘT-PACKAGE-MỘT-KHUNG ở tầng owner THẬT ───────────────────────────────────────────

    private val waze = "com.chisadin.wazemod"
    private val vietmap = NavApps.VIETMAP_LIVE

    /**
     * KHOÁ chính bug B-I tại tầng owner (không chỉ ở hàm thuần): mũi tên Waze còn tươi mà làn lại của VietMap
     * (kênh cũ còn tươi tới 6s sau khi đổi app) ⇒ chỉ bắn mũi tên, KHÔNG bắn làn của ngã ba khác.
     */
    @Test fun `arrow Waze + lane VietMap -- pushArrow=1, pushLane=0`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(vietmap, LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true))), now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, sink.arrowCount)
        assertEquals(0, sink.laneCount, "làn của app KHÁC không được nằm cạnh mũi tên này")
        assertEquals(0, sink.clearCount)
    }

    /** KHOÁ không hồi quy ca THƯỜNG (một app dẫn): cùng package thì hành vi Y HỆT trước B-I. */
    @Test fun `arrow Waze + lane Waze -- CA HAI van ban`() {
        val sink = FakeSink()
        val info = LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true)))
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(waze, info, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, sink.arrowCount)
        assertEquals(1, sink.laneCount)
        assertEquals(info, sink.lastLane)
    }

    /** KHOÁ: DROP ≠ nhả khung — khung đang hiện không được nhấp nháy chỉ vì xuất hiện một kênh lạ. */
    @Test fun `camera khac pkg bi bo va KHONG clear`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishCamera(
                vietmap,
                CameraMatch(hasCamera = true, score = 0.9f, templateName = "speed", distanceMeters = 200),
                now = 1_000L,
            )
            o.tick(1_000L)
        }
        assertEquals(1, sink.arrowCount)
        assertEquals(0, sink.cameraCount)
        assertEquals(0, sink.clearCount)
    }

    /**
     * KHOÁ: cự-ly đi theo DANH TÍNH khung. Không bao giờ ghi cự-ly của app khác cạnh mũi tên app này —
     * lệch ⇒ -1 ⇒ `BydHal.kt:393` BỎ GHI ô cự-ly (không xoá trắng, không bịa số).
     */
    @Test fun `cu-ly di theo DANH TINH khung`() {
        val sink = FakeSink()
        // B-III: warmup=1 để test này chỉ nói về DANH TÍNH, không lẫn với luật warmup (đã có test riêng T22).
        owner(sink, guard = TurnDistancePlausibility(warmupSamples = 1)).use { o ->
            NavViewIdSource.publish(waze, turnMeters = 140, road = "Quang Trung", arrivalClock = "",
                routeSeconds = -1, routeMeters = -1, now = 1_000L)
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            assertEquals(140, sink.lastArrowSeg, "cùng app ⇒ dùng cự-ly view-id thật")

            // Khung đổi sang VietMap: cự-ly view-id vẫn là của Waze ⇒ phải BỎ, không được bám sang.
            ScreenCaptureSignal.clear()
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_100L)
            o.tick(1_100L)
            assertEquals(-1, sink.lastArrowSeg, "cự-ly của app khác ⇒ -1 (BydHal bỏ ghi)")
        }
    }

    /**
     * KHOÁ bug HAI MIỀN ĐỒNG HỒ (NavOutputOwner.kt trước B-I chấm tươi view-id bằng `clock()` chứ không phải
     * `now` của tick). Với `clock = { 0L }`, mốc publish 1_000L sẽ thành "ở tương lai" ⇒ cũ ra -1, đúng phải 140.
     */
    @Test fun `cu-ly cham tuoi bang now cua tick, khong bang clock()`() {
        val sink = FakeSink()
        // B-III: warmup=1 — test này khoá MIỀN ĐỒNG HỒ, không phải warmup.
        owner(sink, guard = TurnDistancePlausibility(warmupSamples = 1)).use { o ->
            NavViewIdSource.publish(waze, turnMeters = 140, road = "", arrivalClock = "",
                routeSeconds = -1, routeMeters = -1, now = 1_000L)
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(140, sink.lastArrowSeg)
    }

    /** KHOÁ: chuyển nguồn giữa chừng KHÔNG làm mất khung (không clear thừa) và KHÔNG lai khung. */
    @Test fun `doi danh tinh giua chung -- van ban mui ten moi, khong clear thua`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(waze, LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true))), now = 1_000L)
            o.tick(1_000L)
            assertEquals(1, sink.arrowCount)
            assertEquals(1, sink.laneCount)

            // Đổi nguồn: mũi tên mới của VietMap, còn làn CŨ của Waze vẫn "tươi" (mới 500ms < 6s).
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_500L)
            o.tick(1_500L)
            assertEquals(2, sink.arrowCount, "mũi tên mới vẫn lên")
            assertEquals(Maneuver.TURN_RIGHT.toHudIcon(), sink.lastArrowIcon)
            assertEquals(1, sink.laneCount, "làn của app cũ bị bỏ, không bắn thêm")
            assertEquals(0, sink.clearCount, "đổi danh tính KHÔNG phát sinh clear")
        }
    }

    // ── B-III: GUARD hợp lý hoá cự ly ở tầng owner THẬT ───────────────────────────────────────────────────

    /** Bơm một mẫu view-id (giả lập một vòng đọc a11y ~800ms) rồi tick owner tại chính mốc đó. */
    private fun NavOutputOwner.feed(pkg: String, meters: Int, roadName: String, at: Long) {
        NavViewIdSource.publish(pkg, meters, roadName, "", -1, -1, now = at)
        tick(at)
    }

    /**
     * T22 — khoá E2E nối dây warmup: hai mẫu đầu chỉ ra mũi tên với `seg = -1`, mẫu thứ ba mới có số.
     * Đồng thời khoá **MŨI TÊN KHÔNG BAO GIỜ BỊ GUARD GIẾT** — guard chỉ gác ô cự-ly.
     */
    @Test fun `guard — truoc warmup pushArrow seg = -1, sau warmup moi co so`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            assertEquals(-1, sink.lastArrowSeg)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            assertEquals(-1, sink.lastArrowSeg)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertEquals(260, sink.lastArrowSeg, "qua warmup thì cự-ly thật mới được lái cụm")
            assertEquals(3, sink.arrowCount, "mũi tên lên đủ 3 nhịp — guard KHÔNG chạm kênh hướng")
            assertEquals(0, sink.blankCount, "chưa từng hiện số ⇒ không có lệnh xoá")
        }
    }

    /**
     * T23 — khoá cạnh xuống: guard từ chối (nhảy tăng cùng tên đường) ⇒ vẫn bắn mũi tên, `seg = -1`, và
     * `blankDistance()` gọi ĐÚNG MỘT LẦN (không spam HAL mỗi tick 4Hz).
     */
    @Test fun `guard — reject van pushArrow, va blankDistance goi DUNG mot lan`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertEquals(260, sink.lastArrowSeg)
            assertEquals(0, sink.blankCount)

            o.feed(waze, 1_500, "Quang Trung", 3_400L)          // nhảy tăng, cùng đường ⇒ REJECT_RISE
            assertEquals(-1, sink.lastArrowSeg)
            assertEquals(1, sink.blankCount)
            val arrows = sink.arrowCount
            o.tick(3_500L)                                       // đọc lại CÙNG mẫu (REPEAT)
            o.feed(waze, 1_500, "Quang Trung", 4_200L)           // vẫn từ chối
            assertEquals(1, sink.blankCount, "không xoá lại — cạnh xuống chỉ có một")
            assertEquals(arrows + 2, sink.arrowCount, "mũi tên vẫn re-assert mỗi nhịp")
        }
    }

    /**
     * T24 — khoá GIỮ NGUYÊN hành vi hiện có cho VietMap/GMaps: chúng không phơi bộ view-id của Waze nên
     * không bao giờ có mẫu ⇒ `seg = -1`, không crash, mũi tên vẫn lên bình thường.
     */
    @Test fun `guard — khung khong co mau view-id thi seg = -1 va khong crash`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            NavViewIdSource.publish(waze, 140, "Quang Trung", "", -1, -1, now = 1_000L)
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertEquals(1, sink.arrowCount)
            assertEquals(-1, sink.lastArrowSeg)
            assertEquals(0, sink.blankCount)
        }
    }

    /** T25 — khoá vòng đời: nhả frame (clear) reset guard ⇒ phiên sau phải warmup lại từ đầu. */
    @Test fun `guard — issueClear reset guard, phien moi phai warmup lai`() {
        val sink = FakeSink()
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertEquals(260, sink.lastArrowSeg)

            o.tick(9_000L)                                       // mọi kênh stale ⇒ clear ⇒ guard.reset()
            assertEquals(1, sink.clearCount)

            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 9_500L)
            o.feed(waze, 240, "Quang Trung", 9_500L)
            assertEquals(-1, sink.lastArrowSeg, "phiên mới KHÔNG được thừa hưởng lòng tin của phiên cũ")
        }
    }

    /** T26 — degrade-safe: sink ném ở đúng kênh blankDistance cũng không được làm sập tick. */
    @Test fun `guard — sink nem o blankDistance khong lam sap tick`() {
        val sink = object : NavOutputOwner.Sink {
            var arrows = 0
            override fun pushArrow(icon: Int, segMeters: Int) { arrows++ }
            override fun pushLane(info: LaneInfo) = Unit
            override fun pushCamera(iconCode: Int, distanceMeters: Int) = Unit
            override fun blankDistance() { throw RuntimeException("boom") }
            override fun clear() = Unit
        }
        owner(sink).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertDoesNotThrow { o.feed(waze, 1_500, "Quang Trung", 3_400L) }
            assertEquals(4, sink.arrows, "kênh mũi tên vẫn chạy dù kênh xoá cự-ly ném")
        }
    }

    // ── BỀ MẶT cụm (op 39) — khoá hồi quy 08-23 vòng 3 [P1] ───────────────────────────────────────────
    // VÌ SAO CÓ NHÓM NÀY: `ClusterNavLaneWidget.onNavActive` (op 39 "simple navigation" — thứ DỰNG lớp nav OEM
    // giữa cụm, on-car 2026-08-12) từng chỉ có MỘT call site là `NavNotificationListener`. VIỆC B thu
    // `MAPS_PACKAGES` về `NavApps.NOTIFICATION` (chỉ GMaps) ⇒ VietMap — app vừa được chuyển HẲN sang kênh ảnh —
    // mất luôn lệnh dựng bề mặt, tức đẩy nội dung vào một lớp chưa ai bật. Xem KDoc
    // `NavOutputOwner.assertNavSurface`.

    /** Có khung để bắn ⇒ PHẢI dựng bề mặt trước. Đây là ca VietMap-qua-ảnh mà VIỆC B tạo ra. */
    @Test fun `be mat — tick co khung PHAI assert op39 (khoi phuc hoi quy VIEC B)`() {
        val sink = FakeSink()
        var surface = 0
        NavOutputOwner(sink = sink, clock = { 0L }, log = {}, speed = { null }, assertNavSurface = { surface++ })
            .use { o ->
                ScreenCaptureSignal.publishArrow(NavApps.VIETMAP_LIVE, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
                o.tick(1_000L)
                assertEquals(1, surface, "khung ảnh đầu tiên phải bật lớp nav của cụm")
                o.tick(1_200L)   // keep-alive: còn tươi ⇒ re-assert (widget tự debounce 30s, không phải ở đây)
                assertEquals(2, surface)
                assertEquals(2, sink.arrowCount, "nội dung vẫn bắn đúng như trước, không bị bề mặt chặn")
            }
    }

    /**
     * KHÔNG có khung ⇒ KHÔNG chạm bề mặt. Khoá hai điều cùng lúc:
     *  · tick rỗng (chưa từng bắn gì) không gọi shell — không có "bật cụm khi không dẫn";
     *  · nhánh clear KHÔNG gọi `onNavIdle` (cố ý — xem KDoc `assertNavSurface`): idle reset `lastOkAtMs` nên
     *    một kênh ảnh nhấp nháy sẽ xoá debounce 30 s của đường notification GMaps đang chạy song song và ép
     *    dadb re-issue op-39 ở nhịp giây.
     */
    @Test fun `be mat — tick rong va nhanh clear KHONG cham op39`() {
        val sink = FakeSink()
        var surface = 0
        NavOutputOwner(sink = sink, clock = { 0L }, log = {}, speed = { null }, assertNavSurface = { surface++ })
            .use { o ->
                o.tick(1_000L)                                   // chưa có tín hiệu nào
                assertEquals(0, surface)
                ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 2_000L)
                o.tick(2_000L)
                assertEquals(1, surface)
                o.tick(20_000L)                                  // mọi kênh stale ⇒ clear
                assertEquals(1, sink.clearCount)
                assertEquals(1, surface, "clear KHÔNG được đụng tới op39 (không gọi onNavIdle từ đây)")
            }
    }

    /** Degrade-safe: đường shell/prefs ném (dadb loopback chết) không được nuốt mất nội dung của tick. */
    @Test fun `be mat — assert nem thi noi dung van bay`() {
        val sink = FakeSink()
        NavOutputOwner(
            sink = sink, clock = { 0L }, log = {}, speed = { null },
            assertNavSurface = { throw RuntimeException("dadb down") },
        ).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertEquals(1, sink.arrowCount, "mũi tên vẫn phải bắn dù bề mặt không dựng được")
        }
    }
}
