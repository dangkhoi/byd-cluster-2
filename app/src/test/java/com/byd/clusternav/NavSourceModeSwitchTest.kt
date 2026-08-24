package com.byd.clusternav

import com.byd.clusternav.navigation.Lane
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavApps
import com.byd.clusternav.navigation.NavSourceMode
import com.byd.clusternav.navigation.NavSourceModeSwitch
import com.byd.clusternav.navigation.NavViewIdSource
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.screencapture.CameraMatch
import com.byd.clusternav.navigation.screencapture.ScreenCaptureSignal
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * **B3.49** — đổi menu nguồn phải có hiệu lực **TỨC THÌ** trên cụm.
 *
 * Ca hiện trường được khoá ở đây: đang dẫn bằng **Waze**, tài xế đổi menu sang "VietMap" (VietMap không
 * dẫn). Cổng nguồn chặn khung MỚI ngay, nhưng [com.byd.clusternav.NavOutputOwner] quyết định bắn **chỉ** theo
 * độ tươi của [ScreenCaptureSignal] (`STALE_MS` = 6 000 ms) — nó không đọc `Prefs`, không hỏi [SourceArbiter]
 * — nên mẫu CŨ còn tươi và cụm vẫn vẽ mũi tên Waze thêm tới **6 giây**. Owner chốt ở B3.48: *"chọn đích danh
 * app thì nếu app đó không dẫn thì không hiện gì"*.
 *
 * ⚠ **VÌ SAO FIXTURE LÀ Waze CHỨ KHÔNG PHẢI GMaps** (đính chính 08-23 vòng 2): GMaps đang dẫn thì noti ~1 Hz
 * giữ `SourceArbiter.isDataFresh(gmaps)` = true, mà `shouldFeed(…, IMAGE)` từ chối kênh ẢNH của gói có mốc
 * DATA còn tươi ⇒ production gần như không bao giờ để GMaps làm chủ kênh ảnh lúc đang dẫn. [ĐO] cùng phiên:
 * `data=true · image(+0,5 s)=false · image(+7 s)=true`. Luật thì thuần theo NHÓM (không phân biệt gói), nên
 * ca GMaps vẫn được duyệt trong test ma trận `duyet moi to hop`; chỉ ca "hiện trường" phải dùng app
 * **chỉ-có-kênh-ảnh** mới đại diện đúng.
 *
 * Đặt ở `:app` (cạnh `NavOutputOwnerTest`) vì phép đo đi qua [com.byd.clusternav.NavOutputOwner] — người tiêu
 * thụ thật của tín hiệu, ở `:app`. Luật thì thuần và nằm ở `:core` ([NavSourceModeSwitch]).
 */
class NavSourceModeSwitchTest {

    private class FakeSink : NavOutputOwner.Sink {
        var laneCount = 0
        var cameraCount = 0
        override fun pushLane(info: LaneInfo) { laneCount++ }
        override fun pushCamera(iconCode: Int, distanceMeters: Int) { cameraCount++ }
    }

    /**
     * CỬA CHÍNH giả (F4 bước 1, 08-24). Mũi tên không còn đi `sink.pushArrow` mà vào phễu
     * (`NavRepository.ingestContent`); nhả khung không còn là `sink.clear()` mà là `stopIfSource(pkg)`.
     *
     * ⚠ ĐỌC SỐ ĐÚNG CÁCH: [count] là số lần khung vào phễu, KHÔNG phải số nhịp re-assert. Owner dedup theo
     * nội dung (4 Hz × `prefs.commit()` đồng bộ là không chấp nhận được), còn việc nhắc lại nội dung cho OEM
     * nằm SAU phễu (`NavigationHudOwner.keepAliveTick`). Vì thế các test dưới đây đo "còn tươi hay không"
     * bằng [stopped] (nhả khung) chứ không bằng cách đếm nhịp bắn.
     */
    private class FakeFunnel {
        val frames = mutableListOf<Pair<String, com.byd.clusternav.navigation.NavigationFrameContent>>()
        val stopped = mutableListOf<String>()
        val count: Int get() = frames.size
        val lastPkg: String? get() = frames.lastOrNull()?.first
        fun ingest(pkg: String, content: com.byd.clusternav.navigation.NavigationFrameContent) {
            frames += pkg to content
        }
        fun stop(pkg: String) { stopped += pkg }
    }

    private val gmaps = NavApps.GMAPS.first()
    private val waze = NavApps.WAZE_RES_PREFIX          // "com.waze" — app CHỈ có kênh ảnh (xem KDoc lớp)
    private val vietmap = NavApps.VIETMAP.first()
    private val lanes = LaneInfo(listOf(Lane(listOf(Maneuver.STRAIGHT), recommended = true)))

    // Ba holder TOÀN CỤC (object) — không dọn thì trạng thái rò từ test này sang test khác.
    @BeforeEach fun reset() { ScreenCaptureSignal.clear(); NavViewIdSource.clear(); SourceArbiter.clear() }
    @AfterEach fun tearDown() { ScreenCaptureSignal.clear(); NavViewIdSource.clear(); SourceArbiter.clear() }

    private fun owner(sink: NavOutputOwner.Sink, funnel: FakeFunnel) = NavOutputOwner(
        sink = sink, ingest = funnel::ingest, stopSession = funnel::stop,
        clock = { 0L }, log = {}, speed = { null },
    )

    // ── 1. Ca hiện trường: đổi sang PREFER_* của app KHÁC ⇒ nhịp tick KẾ TIẾP im ────────────────────────
    /**
     * ĐÂY LÀ TEST KHOÁ CHÍNH. Gỡ lời gọi `ScreenCaptureSignal.dropDisallowed` khỏi
     * [NavSourceModeSwitch.onModeSelected] là test này ĐỎ ngay (mũi tên GMaps vẫn được bắn ở t+250 ms).
     */
    @Test fun `doi sang PREFER app khac - nhip tick KE TIEP khong ban gi`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_000L)
            assertEquals(1, funnel.count, "tiền đề: Waze đang lái cụm qua đường ảnh")

            var persisted = -1
            val changed = NavSourceModeSwitch.onModeSelected(
                previousMode = NavSourceMode.AUTO,
                selectedMode = NavSourceMode.PREFER_VIETMAP,
                persist = { persisted = it },
            )
            assertTrue(changed, "mode đổi thật ⇒ phải trả true")
            assertEquals(NavSourceMode.PREFER_VIETMAP, persisted, "phải ghi mode mới xuống prefs")

            // Nhịp keep-alive KẾ TIẾP (250 ms sau), tức còn RẤT xa mốc stale 6 000 ms.
            o.tick(1_250L)
        }
        assertEquals(1, funnel.count, "sau khi đổi menu, KHÔNG được đưa thêm khung của app cũ vào phễu")
        assertEquals(listOf(waze), funnel.stopped, "phiên của app cũ phải được NHẢ, không để nhịp tim ghim lại")
    }

    /**
     * ĐỐI CHỨNG cho test trên — chứng minh con số 6 giây là THẬT chứ không phải giả định: cùng dữ liệu, cùng
     * hai mốc thời gian, chỉ bỏ đúng bước đổi mode ⇒ mũi tên app cũ vẫn lên cụm. Và ở mốc 6 001 ms thì hết.
     */
    @Test fun `doi chung - khong doi mode thi mui ten app cu song toi moc 6000ms`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            o.tick(1_250L)
            assertEquals(1, funnel.count, "250 ms sau: còn tươi ⇒ khung đã vào phễu")
            o.tick(1_000L + ScreenCaptureSignal.STALE_MS)
            // Mốc tươi đo bằng ĐƯỜNG NHẢ, không đếm nhịp bắn: nội dung y hệt nên phễu dedup (xem KDoc
            // FakeFunnel). Còn tươi ⇔ CHƯA nhả phiên.
            assertTrue(funnel.stopped.isEmpty(), "đúng mốc 6 000 ms: VẪN còn tươi — đây chính là cửa sổ 6 giây")
            o.tick(1_001L + ScreenCaptureSignal.STALE_MS)
        }
        assertEquals(1, funnel.count, "quá 6 000 ms mới hết tươi ⇒ không có khung mới")
        assertEquals(listOf(waze), funnel.stopped, "6 001 ms: hết tươi ⇒ nhả phiên")
    }

    // ── 1b. VÒNG 2: app KHÁC còn giữ khung ⇒ gỡ sample KHÔNG đủ, phải NHẢ khung ────────────────────────
    /**
     * **HỞ [P1] do phản biện 08-23 chỉ ra, [ĐO] bằng probe rồi vá.** Bản đầu của B3.49 chỉ gỡ sample của app
     * bị loại. Nhưng `NavOutputDecision` nhả khung theo `clear = !anyFresh` — TẤT CẢ ba kênh phải hết tươi.
     * Nếu một app khác (vẫn được phép) còn giữ một kênh tươi thì khung sống tiếp, mà `BydHal.pushLane` KHÔNG
     * ghi `INSTRUMENT_GUIDE_INFO_SIMPLE_SET` ⇒ **mũi tên của app vừa bị loại nằm lại trên cụm**. Không phải
     * 6 giây — VÔ HẠN, vì VietMap publish lại làn mỗi ~500 ms.
     *
     * [ĐO] trước khi vá (probe `PROBE-A`, đã xoá): `t0 arrow=1 clear=0` → đổi mode → `t+250 lane=1 clear=0`
     * → `t+500 lane=2 clear=0`. Không một lệnh nhả khung nào.
     */
    @Test fun `app bi loai ma app KHAC con giu khung - van phai NHA khung dung mot lan`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            ScreenCaptureSignal.publishLane(vietmap, lanes, now = 1_000L)
            o.tick(1_000L)
            assertEquals(1, funnel.count, "tiền đề: mũi tên Waze đang là danh tính khung")
            assertEquals(0, sink.laneCount, "làn VietMap lệch danh tính ⇒ DROP (không kéo theo clear)")

            NavSourceModeSwitch.onModeSelected(NavSourceMode.AUTO, NavSourceMode.PREFER_VIETMAP) {}
            assertNull(ScreenCaptureSignal.arrow, "mũi tên Waze bị gỡ")
            assertNotNull(ScreenCaptureSignal.lane, "làn VietMap được phép ⇒ KHÔNG bị đụng")

            o.tick(1_250L)
            assertEquals(listOf(waze), funnel.stopped, "phải NHẢ phiên: register mũi tên còn giữ nội dung của Waze")
            assertEquals(1, funnel.count, "không được đưa thêm khung của app cũ vào phễu")
            assertEquals(0, sink.laneCount, "nhịp nhả khung KHÔNG bắn tiếp trong cùng tick")

            // Nhịp kế: khung dựng lại SẠCH, chỉ còn app được phép.
            o.tick(1_500L)
            assertEquals(1, sink.laneCount, "nhịp sau dựng lại khung từ kênh còn được phép")
            assertEquals(1, funnel.count)
            assertEquals(listOf(waze), funnel.stopped, "nhả đúng MỘT lần, không nhả lặp mỗi nhịp")
        }
    }

    /**
     * ĐỐI XỨNG với test trên: nhả khung phải **CHỈ** xảy ra khi danh tính khung ĐANG hiện đúng là gói bị loại.
     * Ở đây khung là của VietMap (app vừa được chọn), gói bị loại là Waze trên một kênh đang bị DROP sẵn —
     * nhả khung lúc này là làm chính app vừa chọn chớp tắt, tức "hiện SAI".
     */
    @Test fun `goi bi loai KHONG phai danh tinh khung - khong duoc nha khung`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_000L)
            ScreenCaptureSignal.publishLane(waze, lanes, now = 1_000L)
            o.tick(1_000L)
            assertEquals(1, funnel.count, "tiền đề: khung là của VietMap")

            NavSourceModeSwitch.onModeSelected(NavSourceMode.AUTO, NavSourceMode.PREFER_VIETMAP) {}
            assertNull(ScreenCaptureSignal.lane, "làn Waze bị gỡ")

            o.tick(1_250L)
            assertTrue(funnel.stopped.isEmpty(), "phiên của app vừa được chọn KHÔNG được nhả (nháy khung)")
            assertEquals(vietmap, funnel.lastPkg, "khung vẫn là của VietMap")
            assertEquals(1, funnel.count, "nội dung không đổi ⇒ phễu không bị đập lại (re-assert nằm sau phễu)")
        }
    }

    /** Yêu cầu nhả khung là ONE-SHOT: đọc một lần rồi thôi, không được nhả lại ở các nhịp sau. */
    @Test fun `yeu cau nha khung chi tieu thu MOT lan`() {
        ScreenCaptureSignal.publishArrow(waze, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
        val dropped = ScreenCaptureSignal.dropDisallowed { it in NavApps.VIETMAP }
        assertEquals(setOf(waze), dropped, "trả về đúng gói vừa bị gỡ kênh")
        assertEquals(setOf(waze), ScreenCaptureSignal.consumeFrameRelease())
        assertTrue(ScreenCaptureSignal.consumeFrameRelease().isEmpty(), "one-shot: lần hai phải rỗng")
    }

    // ── 2. Bẫy Spinner: onItemSelected bắn cả khi setSelection() lúc dựng màn hình ─────────────────────
    @Test fun `chon lai DUNG mode dang luu - no-op tuyet doi (khong ghi prefs, khong xoa kenh)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(gmaps, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
            var persistCalls = 0
            val changed = NavSourceModeSwitch.onModeSelected(
                previousMode = NavSourceMode.AUTO,
                selectedMode = NavSourceMode.AUTO,
                persist = { persistCalls++ },
            )
            assertFalse(changed, "trùng mode ⇒ false")
            assertEquals(0, persistCalls, "trùng mode thì KHÔNG được ghi prefs")
            assertNotNull(ScreenCaptureSignal.arrow, "trùng mode thì KHÔNG được xoá kênh nào")
            o.tick(1_250L)
        }
        assertEquals(1, funnel.count, "mở app không được làm cụm mất mũi tên")
    }

    // ── 3. CHỈ THU HẸP: app được chọn không bị nháy ───────────────────────────────────────────────────
    @Test fun `chon dung app dang dan - kenh cua no GIU nguyen (khong nhay khung)`() {
        val sink = FakeSink(); val funnel = FakeFunnel()
        owner(sink, funnel).use { o ->
            ScreenCaptureSignal.publishArrow(vietmap, Maneuver.TURN_RIGHT, amap = 3, now = 1_000L)
            NavSourceModeSwitch.onModeSelected(NavSourceMode.AUTO, NavSourceMode.PREFER_VIETMAP) {}
            assertNotNull(ScreenCaptureSignal.arrow, "app vừa được chọn phải giữ nguyên kênh")
            o.tick(1_250L)
        }
        assertEquals(1, funnel.count)
        assertTrue(funnel.stopped.isEmpty(), "không được nhả rồi dựng lại khung của chính app vừa chọn")
    }

    @Test fun `doi ve AUTO - khong xoa kenh nao (AUTO khong dien dat uu tien)`() {
        ScreenCaptureSignal.publishArrow(gmaps, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
        ScreenCaptureSignal.publishLane(vietmap, LaneInfo(emptyList()), now = 1_000L)
        NavSourceModeSwitch.onModeSelected(NavSourceMode.PREFER_VIETMAP, NavSourceMode.AUTO) {}
        assertNotNull(ScreenCaptureSignal.arrow, "AUTO không loại gói nào ⇒ không kênh nào bị bỏ")
        assertNotNull(ScreenCaptureSignal.lane)
    }

    @Test fun `ba kenh doc lap - chi kenh cua app bi loai bi bo`() {
        ScreenCaptureSignal.publishArrow(gmaps, Maneuver.TURN_LEFT, amap = 2, now = 1_000L)
        ScreenCaptureSignal.publishLane(gmaps, LaneInfo(emptyList()), now = 1_000L)
        ScreenCaptureSignal.publishCamera(vietmap, CameraMatch(true, 1f, "t", null, 120), now = 1_000L)
        NavSourceModeSwitch.onModeSelected(NavSourceMode.AUTO, NavSourceMode.PREFER_VIETMAP) {}
        assertNull(ScreenCaptureSignal.arrow, "mũi tên GMaps bị loại")
        assertNull(ScreenCaptureSignal.lane, "làn GMaps bị loại")
        assertNotNull(ScreenCaptureSignal.camera, "camera VietMap được giữ")
    }

    @Test fun `duyet moi to hop mode x nhom - kenh song sot dung bang cong allowedByMode`() {
        val modes = listOf(
            NavSourceMode.AUTO to null,
            NavSourceMode.PREFER_GMAPS to NavApps.GMAPS,
            NavSourceMode.PREFER_WAZE to NavApps.WAZE,
            NavSourceMode.PREFER_VIETMAP to NavApps.VIETMAP,
        )
        val allPkgs = (NavApps.GMAPS + NavApps.WAZE + NavApps.VIETMAP).toList()
        modes.forEach { (mode, allowed) ->
            allPkgs.forEach { pkg ->
                ScreenCaptureSignal.clear()
                ScreenCaptureSignal.publishArrow(pkg, Maneuver.STRAIGHT, amap = 9, now = 1_000L)
                // previousMode cố ý khác mode để nhánh "đổi thật" luôn chạy.
                val prev = if (mode == NavSourceMode.AUTO) NavSourceMode.PREFER_GMAPS else NavSourceMode.AUTO
                NavSourceModeSwitch.onModeSelected(prev, mode, {})
                val shouldSurvive = allowed == null || pkg in allowed
                assertEquals(
                    shouldSurvive, ScreenCaptureSignal.arrow != null,
                    "mode=$mode pkg=$pkg — kênh phải sống đúng bằng cổng SourceArbiter.allowedByMode",
                )
            }
        }
    }

    // ── 4. KHÔNG NỚI: trọng tài không được đụng tới ────────────────────────────────────────────────────
    /**
     * Khoá bài học B3.48: `NavNotificationListener` phát lệnh dừng cụm ĐÚNG khi
     * `SourceArbiter.release(pkg)` = true. Nếu chỗ đổi mode gọi `SourceArbiter.clear()` (đề xuất đầu tiên
     * trong backlog B3.49) thì `activeSource` bị xoá trước ⇒ `release` trả false ⇒ **không ai phát STOP** ⇒
     * nhịp tim ghim khung cuối tới 180 s. Ngoài ra `activeSource = null` còn mở nhánh AUTO (`h == null` cho
     * MỌI gói qua) và `lastDataByPkg.clear()` gỡ mốc DATA đang chặn kênh ẢNH — cả hai đều là NỚI.
     */
    @Test fun `doi mode KHONG duoc dung vao SourceArbiter (giu duong dung cum cua B3-48)`() {
        assertTrue(SourceArbiter.shouldFeed(gmaps, NavSourceMode.AUTO, 10_000L), "tiền đề: GMaps giữ cụm")
        assertEquals(gmaps, SourceArbiter.activeSource)
        assertTrue(SourceArbiter.isDataFresh(gmaps, 10_000L))

        NavSourceModeSwitch.onModeSelected(NavSourceMode.AUTO, NavSourceMode.PREFER_VIETMAP) {}

        assertEquals(gmaps, SourceArbiter.activeSource, "activeSource PHẢI còn ⇒ release() còn bắn được STOP")
        assertTrue(SourceArbiter.release(gmaps), "đúng đường B3.48: cổng loại gói đang giữ ⇒ phát lệnh dừng")
        // Mốc DATA cũng không được xoá (xoá = mở lại kênh ẢNH của chính gói đó — nới tầng kênh R6).
        assertTrue(SourceArbiter.isDataFresh(gmaps, 10_000L), "mốc DATA không được xoá ở chỗ đổi mode")
    }

    // ── 5. WIRING: call site nằm đúng trên đường đổi mode, phục vụ CẢ HAI layout ───────────────────────
    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative)
        else current.resolve("app").resolve(relative)
    }

    private fun read(relative: String): String = app(relative).toFile().readText()

    private val mainActivity by lazy { read("src/main/java/com/byd/clusternav/MainActivity.kt") }

    /** Bỏ dòng chú thích `//` — KDoc/comment được phép NHẮC TÊN một hàm bị cấm gọi (và ở đây có nhắc). */
    private fun codeOnly(block: String): String =
        block.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    /** Đoạn mã của selector nguồn = từ chỗ bind `spinner_nav_source` tới chỗ bind spinner kế tiếp. */
    private fun navSourceBlock(): String {
        val start = mainActivity.indexOf("R.id.spinner_nav_source")
        require(start >= 0) { "MainActivity không còn bind spinner_nav_source" }
        val end = mainActivity.indexOf("R.id.spinner_cluster_mode", start)
        require(end > start) { "không tìm được điểm kết của khối nav-source" }
        return mainActivity.substring(start, end)
    }

    @Test fun `MainActivity goi NavSourceModeSwitch ngay tren duong doi mode`() {
        val block = navSourceBlock()
        assertTrue(
            block.contains("NavSourceModeSwitch.onModeSelected"),
            "đường đổi mode phải gọi NavSourceModeSwitch.onModeSelected — nếu không, mũi tên app cũ sống 6 s",
        )
        assertTrue(
            block.contains("previousMode = Prefs.sourceMode("),
            "phải so với mode ĐANG LƯU (Spinner bắn onItemSelected cả lúc setSelection lúc dựng màn hình)",
        )
        assertTrue(
            Regex("""persist\s*=\s*\{[^}]*Prefs\.setSourceMode""").containsMatchIn(block),
            "việc ghi prefs phải đi qua seam persist của NavSourceModeSwitch (một đường, một nơi)",
        )
        assertFalse(
            Regex("""(?m)^\s*Prefs\.setSourceMode\(""").containsMatchIn(block),
            "không được còn đường ghi prefs TẮT bỏ qua NavSourceModeSwitch",
        )
        assertFalse(
            codeOnly(block).contains("SourceArbiter.clear()"),
            "cấm GỌI SourceArbiter.clear() ở đây — nuốt lệnh dừng cụm của B3.48 và nới cổng AUTO",
        )
        assertFalse(
            codeOnly(block).contains("ScreenCaptureSignal.clear()"),
            "cấm GỌI ScreenCaptureSignal.clear() ở đây — xoá cả ba kênh làm chính app vừa chọn nháy khung",
        )
    }

    /**
     * B3.17 đã một lần crash vì bản `layout-w960dp` thiếu view mà code bind. Ở đây cả hai layout khai CÙNG
     * một id và `MainActivity` bind ĐÚNG MỘT lần ⇒ một đường mã phục vụ cả hai bố cục, không thể lệch.
     */
    @Test fun `ca hai layout dung chung mot duong doi mode`() {
        listOf("src/main/res/layout/activity_main.xml", "src/main/res/layout-w960dp/activity_main.xml")
            .forEach { rel ->
                assertTrue(
                    read(rel).contains("@+id/spinner_nav_source"),
                    "$rel phải khai spinner_nav_source (bind một lần, dùng chung đường B3.49)",
                )
            }
        assertEquals(
            1,
            Regex("""R\.id\.spinner_nav_source""").findAll(mainActivity).count(),
            "chỉ được bind spinner_nav_source ĐÚNG một chỗ — hai chỗ là hai đường, một đường sẽ thiếu vá",
        )
        assertEquals(
            1,
            Regex("""NavSourceModeSwitch\.onModeSelected""").findAll(mainActivity).count(),
            "một call site duy nhất trên đường đổi mode",
        )
    }
}
