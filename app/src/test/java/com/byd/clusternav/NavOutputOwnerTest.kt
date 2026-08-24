package com.byd.clusternav

import com.byd.clusternav.navigation.Lane
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavApps
import com.byd.clusternav.navigation.NavViewIdSource
import com.byd.clusternav.navigation.NavigationFrameContent
import com.byd.clusternav.navigation.TurnDistancePlausibility
import com.byd.clusternav.navigation.screencapture.CameraMatch
import com.byd.clusternav.navigation.screencapture.ScreenCaptureSignal
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit test [NavOutputOwner] — thuần JVM: fake [NavOutputOwner.Sink] + fake CỬA CHÍNH + đồng hồ tiêm +
 * publish thẳng vào [ScreenCaptureSignal] rồi gọi [NavOutputOwner.tick] theo thời điểm cố định. KHÔNG chạm
 * Android/HAL.
 *
 * ⚠ 2026-08-24 (F4 bước 1, spec `docs/specs/nav-input-output-architecture.html`): mũi tên KHÔNG còn đi
 * `sink.pushArrow` mà đi **cửa chính** — `NavContentBuilder.fromImage` → seam `ingest` →
 * `NavRepository.ingestContent`. Vì thế mọi khẳng định "mũi tên có lên không" nay đọc [FakeFunnel], và
 * "cự ly có đúng không" đọc `content.distanceMeters` (null = -1 = xoá trắng ô cự-ly ở `writeNavFrame`).
 * Nhả khung cũng không còn là `sink.clear()` mà là seam `stopSession` (nhả CẢ phiên, chỉ khi đúng chủ).
 *
 * Khoá hành vi: bắn arrow / lane / camera theo mức tươi (CỨ BẮN, độc lập); CHỐNG SPAM PHỄU (nội dung y hệt
 * ⇒ không ingest lại); nhả phiên ĐÚNG một lần khi tất cả kênh stale; degrade-safe (cửa chính ném không làm
 * sập tick, và nhịp sau thử lại); mũi tên suy từ amap khi maneuver null; bỏ khung khi không có hướng hợp lệ.
 */
class NavOutputOwnerTest {

    private class FakeSink : NavOutputOwner.Sink {
        var laneCount = 0
        var lastLane: LaneInfo? = null
        var cameraCount = 0
        var lastCameraCode = Int.MIN_VALUE
        var lastCameraDist = Int.MIN_VALUE
        override fun pushLane(info: LaneInfo) { laneCount++; lastLane = info }
        override fun pushCamera(iconCode: Int, distanceMeters: Int) {
            cameraCount++; lastCameraCode = iconCode; lastCameraDist = distanceMeters
        }
    }

    /** CỬA CHÍNH giả — ghi lại đúng thứ `NavRepository.ingestContent` / `stopIfSource` sẽ nhận. */
    private class FakeFunnel {
        val frames = mutableListOf<Pair<String, NavigationFrameContent>>()
        val stopped = mutableListOf<String>()
        var throwOnIngest = false
        val count: Int get() = frames.size
        val lastPkg: String? get() = frames.lastOrNull()?.first
        val last: NavigationFrameContent? get() = frames.lastOrNull()?.second
        fun ingest(pkg: String, content: NavigationFrameContent) {
            if (throwOnIngest) throw RuntimeException("boom")
            frames += pkg to content
        }
        fun stop(pkg: String) { stopped += pkg }
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
        funnel: FakeFunnel,
        guard: TurnDistancePlausibility = TurnDistancePlausibility(),
        speed: () -> Double? = { null },
        assertNavSurface: () -> Unit = {},
    ) = NavOutputOwner(
        sink = sink,
        ingest = funnel::ingest,
        stopSession = funnel::stop,
        clock = { 0L },
        log = {},
        speed = speed,
        guard = guard,
        assertNavSurface = assertNavSurface,
    )

    @Test fun `arrow-only — khung vao cua chinh, cu-ly null (chua co mau a11y)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, funnel.count)
        assertEquals("com.waze", funnel.lastPkg)
        assertEquals(Maneuver.TURN_LEFT, funnel.last?.maneuver)
        assertNull(funnel.last?.distanceMeters)
        assertEquals(0, sink.laneCount)
        assertEquals(0, sink.cameraCount)
        assertTrue(funnel.stopped.isEmpty())
    }

    @Test fun `lane+arrow — ban CA hai (doc lap, cu ban)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        val info = LaneInfo(listOf(Lane(listOf(Maneuver.TURN_LEFT), false), Lane(listOf(Maneuver.STRAIGHT), true)))
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.STRAIGHT, amap = 9, now = 1_000L)
            ScreenCaptureSignal.publishLane("com.waze", info, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, funnel.count)
        assertEquals(1, sink.laneCount)
        assertEquals(info, sink.lastLane)
        assertEquals(0, sink.cameraCount)
        assertTrue(funnel.stopped.isEmpty())
    }

    @Test fun `camera — ban icon + cu-ly, khong can arrow tuoi`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
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
        assertEquals(0, funnel.count, "camera-only KHÔNG dựng khung guidance")
    }

    @Test fun `mui ten suy tu amap khi maneuver null`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", maneuver = null, amap = 3, now = 1_000L)  // AMAP 3 = TURN_RIGHT
            o.tick(1_000L)
        }
        assertEquals(1, funnel.count)
        assertEquals(Maneuver.TURN_RIGHT, funnel.last?.maneuver)
    }

    @Test fun `arrow tuoi nhung khong co huong hop le -- BO khung (chong re gia)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", maneuver = null, amap = null, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(0, funnel.count)
        assertTrue(funnel.stopped.isEmpty())
    }

    @Test fun `all-stale -- nha PHIEN dung mot lan (idempotent)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)                                  // active
            assertEquals(1, funnel.count)
            o.tick(8_000L)                                  // 7s > STALE_MS(6s) -> nhả phiên
            assertEquals(listOf("com.waze"), funnel.stopped)
            assertEquals(1, funnel.count)                   // không đưa khung mới vào phễu khi stale
            o.tick(8_250L)                                  // vẫn stale, đã nhả -> KHÔNG nhả lại
            o.tick(8_500L)
            assertEquals(listOf("com.waze"), funnel.stopped)
        }
    }

    /**
     * CHỐNG SPAM PHỄU (F4 bước 1). Trước 08-24 mỗi nhịp 250 ms là một `pushArrow` — rẻ vì chỉ ghi register.
     * Nay mỗi khung vào phễu kéo theo `PersistentNavigationFrameStore.append` → `prefs.commit()` ĐỒNG BỘ, tức
     * 4 lần ghi đĩa/giây nếu không dedup. Việc RE-ASSERT nội dung cho OEM nằm SAU phễu
     * (`NavigationHudOwner.keepAliveTick` 250 ms + `AmapEmissionArbiter.heartbeat` 400 ms) nên không mất gì.
     */
    @Test fun `keep-alive -- noi dung y het thi KHONG dua vao phieu lai`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            o.tick(1_250L)   // con tuoi (250ms < 6s)
            o.tick(1_500L)   // con tuoi
            assertEquals(1, funnel.count, "3 nhịp, nội dung y hệt ⇒ đúng MỘT lần vào phễu")
            assertTrue(funnel.stopped.isEmpty())

            // Nội dung ĐỔI thật (hướng khác) ⇒ phải vào phễu ngay nhịp đó.
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_RIGHT, amap = 3, now = 1_750L)
            o.tick(1_750L)
            assertEquals(2, funnel.count)
            assertEquals(Maneuver.TURN_RIGHT, funnel.last?.maneuver)
        }
    }

    @Test fun `degrade-safe -- cua chinh nem KHONG lam sap tick`() {
        val sink = object : NavOutputOwner.Sink {
            override fun pushLane(info: LaneInfo) { throw RuntimeException("boom") }
            override fun pushCamera(iconCode: Int, distanceMeters: Int) { throw RuntimeException("boom") }
        }
        val funnel = FakeFunnel().apply { throwOnIngest = true }
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane("com.waze", LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true))), now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertDoesNotThrow { o.tick(8_000L) }   // đường nhả cũng phải sống
        }
    }

    /**
     * Cửa chính ném ⇒ KHÔNG được ghi vào bộ nhớ dedup, nếu không thì một lần lỗi là khung đó câm vĩnh viễn
     * (nhịp sau tưởng "đã đưa vào rồi"). Khoá đúng cái đó.
     */
    @Test fun `cua chinh nem thi nhip SAU phai thu lai (khong ghi dedup khi that bai)`() {
        val sink = FakeSink()
        val funnel = FakeFunnel().apply { throwOnIngest = true }
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow("com.waze", Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            assertEquals(0, funnel.count)
            funnel.throwOnIngest = false
            o.tick(1_250L)                       // CÙNG nội dung — vẫn phải thử lại
            assertEquals(1, funnel.count)
        }
    }

    // ── §R-BI: BẤT BIẾN MỘT-PACKAGE-MỘT-KHUNG ở tầng owner THẬT ───────────────────────────────────────────

    private val waze = "com.chisadin.wazemod"
    private val vietmap = NavApps.VIETMAP_LIVE

    /**
     * KHOÁ chính bug B-I tại tầng owner (không chỉ ở hàm thuần): mũi tên Waze còn tươi mà làn lại của VietMap
     * (kênh cũ còn tươi tới 6s sau khi đổi app) ⇒ chỉ bắn mũi tên, KHÔNG bắn làn của ngã ba khác.
     */
    @Test fun `arrow Waze + lane VietMap -- khung=1, pushLane=0`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(vietmap, LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true))), now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, funnel.count)
        assertEquals(0, sink.laneCount, "làn của app KHÁC không được nằm cạnh mũi tên này")
        assertTrue(funnel.stopped.isEmpty())
    }

    /** KHOÁ không hồi quy ca THƯỜNG (một app dẫn): cùng package thì hành vi Y HỆT trước B-I. */
    @Test fun `arrow Waze + lane Waze -- CA HAI van ban`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        val info = LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true)))
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(waze, info, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(1, funnel.count)
        assertEquals(1, sink.laneCount)
        assertEquals(info, sink.lastLane)
    }

    /** KHOÁ: DROP ≠ nhả khung — khung đang hiện không được nhấp nháy chỉ vì xuất hiện một kênh lạ. */
    @Test fun `camera khac pkg bi bo va KHONG nha phien`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishCamera(
                vietmap,
                CameraMatch(hasCamera = true, score = 0.9f, templateName = "speed", distanceMeters = 200),
                now = 1_000L,
            )
            o.tick(1_000L)
        }
        assertEquals(1, funnel.count)
        assertEquals(0, sink.cameraCount)
        assertTrue(funnel.stopped.isEmpty())
    }

    /**
     * KHOÁ: cự-ly (và tên đường + ETA) đi theo DANH TÍNH khung. Không bao giờ ghi số của app khác cạnh mũi
     * tên app này — lệch ⇒ `distanceMeters = null` ⇒ `writeNavFrame` ghi -1 = ô cự-ly bị XOÁ TRẮNG.
     */
    @Test fun `cu-ly di theo DANH TINH khung`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        // B-III: warmup=1 để test này chỉ nói về DANH TÍNH, không lẫn với luật warmup (đã có test riêng T22).
        owner(sink, funnel, guard = TurnDistancePlausibility(warmupSamples = 1)).use { o ->
            NavViewIdSource.publish(waze, turnMeters = 140, road = "Quang Trung", arrivalClock = "",
                routeSeconds = -1, routeMeters = -1, now = 1_000L)
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            assertEquals(140, funnel.last?.distanceMeters, "cùng app ⇒ dùng cự-ly view-id thật")
            assertEquals("Quang Trung", funnel.last?.roadName)

            // Khung đổi sang VietMap: mẫu view-id vẫn là của Waze ⇒ phải BỎ, không được bám sang.
            ScreenCaptureSignal.clear()
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_100L)
            o.tick(1_100L)
            assertEquals(vietmap, funnel.lastPkg)
            assertNull(funnel.last?.distanceMeters, "cự-ly của app khác ⇒ null (ô cự-ly xoá trắng)")
            assertNull(funnel.last?.roadName, "tên đường của app khác cũng không được bám sang")
        }
    }

    /**
     * KHOÁ bug HAI MIỀN ĐỒNG HỒ (NavOutputOwner.kt trước B-I chấm tươi view-id bằng `clock()` chứ không phải
     * `now` của tick). Với `clock = { 0L }`, mốc publish 1_000L sẽ thành "ở tương lai" ⇒ cũ ra -1, đúng phải 140.
     */
    @Test fun `cu-ly cham tuoi bang now cua tick, khong bang clock()`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        // B-III: warmup=1 — test này khoá MIỀN ĐỒNG HỒ, không phải warmup.
        owner(sink, funnel, guard = TurnDistancePlausibility(warmupSamples = 1)).use { o ->
            NavViewIdSource.publish(waze, turnMeters = 140, road = "", arrivalClock = "",
                routeSeconds = -1, routeMeters = -1, now = 1_000L)
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals(140, funnel.last?.distanceMeters)
    }

    /**
     * F4 — LỢI ÍCH MỚI, ĐO ĐƯỢC: `Reading.arrivalClock/routeSeconds/routeMeters` đọc được từ Waze/VietMap
     * nhưng trước 08-24 KHÔNG có consumer nào (KDoc `NavViewIdSource.Reading` tự khai). Nay chúng đi qua cửa
     * chính nên VietMap/Waze cũng có giờ tới + quãng/thời-gian còn lại trên cụm-centre, thứ `pushNavigation`
     * không bao giờ mang được.
     */
    @Test fun `ETA + quang con lai cua nguon ANH nay CO duong ra`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel, guard = TurnDistancePlausibility(warmupSamples = 1)).use { o ->
            NavViewIdSource.publish(waze, turnMeters = 140, road = "Quang Trung", arrivalClock = "18:21",
                routeSeconds = 900, routeMeters = 5_200, now = 1_000L)
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
        }
        assertEquals("18:21", funnel.last?.arrivalClock)
        assertEquals(900, funnel.last?.routeRemainingSeconds)
        assertEquals(5_200, funnel.last?.routeRemainingMeters)
    }

    /** KHOÁ: chuyển nguồn giữa chừng KHÔNG làm mất khung (không nhả thừa) và KHÔNG lai khung. */
    @Test fun `doi danh tinh giua chung -- van co khung moi, khong nha thua`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(waze, LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), true))), now = 1_000L)
            o.tick(1_000L)
            assertEquals(1, funnel.count)
            assertEquals(1, sink.laneCount)

            // Đổi nguồn: mũi tên mới của VietMap, còn làn CŨ của Waze vẫn "tươi" (mới 500ms < 6s).
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_500L)
            o.tick(1_500L)
            assertEquals(2, funnel.count, "khung mới vẫn lên")
            assertEquals(vietmap, funnel.lastPkg)
            assertEquals(Maneuver.TURN_RIGHT, funnel.last?.maneuver)
            assertEquals(1, sink.laneCount, "làn của app cũ bị bỏ, không bắn thêm")
            assertTrue(funnel.stopped.isEmpty(), "đổi danh tính KHÔNG phát sinh nhả phiên")
        }
    }

    /**
     * KHOÁ bài học F1 (thứ đang gánh hộ): nhả khung phải đi qua `NavRepository.stopIfSource(pkg)` với ĐÚNG
     * gói đang giữ khung. Nhả vô điều kiện (đường `sink.clear()` cũ) là xoá trắng cả khung notification của
     * Google Maps đang chạy song song.
     */
    @Test fun `nha phien mang DUNG goi dang giu khung`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            o.tick(9_000L)
        }
        assertEquals(listOf(vietmap), funnel.stopped)
    }

    /** Chưa từng có khung ⇒ KHÔNG được nhả phiên của ai cả (kể cả khi mọi kênh đều stale). */
    @Test fun `chua tung co khung thi KHONG nha phien cua ai`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            o.tick(1_000L)
            o.tick(20_000L)
        }
        assertTrue(funnel.stopped.isEmpty())
        assertEquals(0, funnel.count)
    }

    // ── B-III: GUARD hợp lý hoá cự ly ở tầng owner THẬT ───────────────────────────────────────────────────

    /** Bơm một mẫu view-id (giả lập một vòng đọc a11y ~800ms) rồi tick owner tại chính mốc đó. */
    private fun NavOutputOwner.feed(pkg: String, meters: Int, roadName: String, at: Long) {
        NavViewIdSource.publish(pkg, meters, roadName, "", -1, -1, now = at)
        tick(at)
    }

    /**
     * T22 — khoá E2E nối dây warmup: hai mẫu đầu chỉ ra khung với cự-ly null, mẫu thứ ba mới có số.
     * Đồng thời khoá **MŨI TÊN KHÔNG BAO GIỜ BỊ GUARD GIẾT** — guard chỉ gác ô cự-ly.
     */
    @Test fun `guard — truoc warmup cu-ly null, sau warmup moi co so`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            assertNull(funnel.last?.distanceMeters)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            assertNull(funnel.last?.distanceMeters)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertEquals(260, funnel.last?.distanceMeters, "qua warmup thì cự-ly thật mới được lái cụm")
            assertEquals(2, funnel.count, "nhịp giữa có nội dung y hệt nhịp đầu ⇒ dedup nuốt (đúng ý)")
            assertTrue(
                funnel.frames.all { it.second.maneuver == Maneuver.TURN_LEFT },
                "guard KHÔNG chạm kênh hướng — mọi khung vẫn mang mũi tên",
            )
        }
    }

    /**
     * T23 — khoá cạnh xuống: guard từ chối (nhảy tăng cùng tên đường) ⇒ khung VẪN có mũi tên, cự-ly về null.
     *
     * `null` ở đây CHÍNH LÀ lệnh xoá trắng cũ (`BydHal.blankNavDistance`): `NavigationHudOwner` truyền -1
     * xuống `BydHal.writeNavFrame`, mà hàm đó ghi `INSTRUMENT_FRONT_CROSSING_DISTANCE_SET` VÔ ĐIỀU KIỆN.
     * Đường `pushNavigation` cũ thì `if (segMeters >= 0)` nên -1 = GIỮ SỐ CŨ — đó là lý do từng phải có một
     * hàm HAL riêng. Xem `NavOutputGuardWiringTest`.
     */
    @Test fun `guard — reject van co mui ten, cu-ly ve null (xoa trang thua huong qua phieu)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertEquals(260, funnel.last?.distanceMeters)

            o.feed(waze, 1_500, "Quang Trung", 3_400L)          // nhảy tăng, cùng đường ⇒ REJECT_RISE
            assertNull(funnel.last?.distanceMeters)
            assertEquals(Maneuver.TURN_LEFT, funnel.last?.maneuver, "hướng vẫn đáng tin, không được mất")
            val n = funnel.count
            o.tick(3_500L)                                       // đọc lại CÙNG mẫu (REPEAT)
            o.feed(waze, 1_500, "Quang Trung", 4_200L)           // vẫn từ chối
            assertEquals(n, funnel.count, "nội dung không đổi ⇒ không đập phễu thêm nhịp nào")
        }
    }

    /**
     * T24 — khoá GIỮ NGUYÊN hành vi hiện có cho app không phơi bộ view-id: không bao giờ có mẫu ⇒ cự-ly null,
     * không crash, mũi tên vẫn lên bình thường.
     */
    @Test fun `guard — khung khong co mau view-id thi cu-ly null va khong crash`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            NavViewIdSource.publish(waze, 140, "Quang Trung", "", -1, -1, now = 1_000L)
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertEquals(1, funnel.count)
            assertNull(funnel.last?.distanceMeters)
        }
    }

    /** T25 — khoá vòng đời: nhả khung reset guard ⇒ phiên sau phải warmup lại từ đầu. */
    @Test fun `guard — nha khung reset guard, phien moi phai warmup lai`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.feed(waze, 300, "Quang Trung", 1_000L)
            o.feed(waze, 280, "Quang Trung", 1_800L)
            o.feed(waze, 260, "Quang Trung", 2_600L)
            assertEquals(260, funnel.last?.distanceMeters)

            o.tick(9_000L)                                       // mọi kênh stale ⇒ nhả phiên ⇒ guard.reset()
            assertEquals(listOf(waze), funnel.stopped)

            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 9_500L)
            o.feed(waze, 240, "Quang Trung", 9_500L)
            assertNull(funnel.last?.distanceMeters, "phiên mới KHÔNG được thừa hưởng lòng tin của phiên cũ")
        }
    }

    // ── BỀ MẶT cụm (op 39) — khoá hồi quy 08-23 vòng 3 [P1] ───────────────────────────────────────────
    // VÌ SAO CÓ NHÓM NÀY: `ClusterNavLaneWidget.onNavActive` (op 39 "simple navigation" — thứ DỰNG lớp nav OEM
    // giữa cụm, on-car 2026-08-12) từng chỉ có MỘT call site là `NavNotificationListener`. VIỆC B thu
    // `MAPS_PACKAGES` về `NavApps.NOTIFICATION` (chỉ GMaps) ⇒ VietMap — app vừa được chuyển HẲN sang kênh ảnh —
    // mất luôn lệnh dựng bề mặt, tức đẩy nội dung vào một lớp chưa ai bật. Xem KDoc
    // `NavOutputOwner.assertNavSurface`. F4 bước 2 sẽ DỜI lời gọi này vào `NavRepository`; tới lúc đó nhóm
    // test này phải theo sang, KHÔNG được xoá cho xanh (đúng bẫy F1).

    /** Có khung để bắn ⇒ PHẢI dựng bề mặt trước. Đây là ca VietMap-qua-ảnh mà VIỆC B tạo ra. */
    @Test fun `be mat — tick co khung PHAI assert op39 (khoi phuc hoi quy VIEC B)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        var surface = 0
        owner(sink, funnel, assertNavSurface = { surface++ }).use { o ->
            ScreenCaptureSignal.publishArrow(NavApps.VIETMAP_LIVE, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            assertEquals(1, surface, "khung ảnh đầu tiên phải bật lớp nav của cụm")
            o.tick(1_200L)   // keep-alive: còn tươi ⇒ re-assert (widget tự debounce 30s, không phải ở đây)
            assertEquals(2, surface)
            assertEquals(1, funnel.count, "bề mặt re-assert mỗi nhịp, nhưng nội dung y hệt thì không đập phễu")
        }
    }

    /**
     * KHÔNG có khung ⇒ KHÔNG chạm bề mặt. Khoá hai điều cùng lúc:
     *  · tick rỗng (chưa từng bắn gì) không gọi shell — không có "bật cụm khi không dẫn";
     *  · nhánh nhả khung KHÔNG gọi `onNavIdle` (cố ý — xem KDoc `assertNavSurface`): idle reset `lastOkAtMs`
     *    nên một kênh ảnh nhấp nháy sẽ xoá debounce 30 s của đường notification GMaps đang chạy song song và
     *    ép dadb re-issue op-39 ở nhịp giây.
     */
    @Test fun `be mat — tick rong va nhanh nha khung KHONG cham op39`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        var surface = 0
        owner(sink, funnel, assertNavSurface = { surface++ }).use { o ->
            o.tick(1_000L)                                   // chưa có tín hiệu nào
            assertEquals(0, surface)
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 2_000L)
            o.tick(2_000L)
            assertEquals(1, surface)
            o.tick(20_000L)                                  // mọi kênh stale ⇒ nhả phiên
            assertEquals(listOf(waze), funnel.stopped)
            assertEquals(1, surface, "nhả khung KHÔNG được đụng tới op39 (không gọi onNavIdle từ đây)")
        }
    }

    /** Degrade-safe: đường shell/prefs ném (dadb loopback chết) không được nuốt mất nội dung của tick. */
    @Test fun `be mat — assert nem thi noi dung van bay`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel, assertNavSurface = { throw RuntimeException("dadb down") }).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            assertDoesNotThrow { o.tick(1_000L) }
            assertEquals(1, funnel.count, "khung vẫn phải vào phễu dù bề mặt không dựng được")
        }
    }
}
