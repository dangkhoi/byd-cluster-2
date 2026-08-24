package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract lock for the B3 :app screen-capture layer — the parts that touch Android (Bitmap/executor/shell/
 * MediaProjection) and therefore cannot run on the stubbed JVM android.jar. Asserts the safety/gate/lifecycle
 * invariants by scanning source, the same technique as [SpeedSignSourceLifecycleTest].
 */
class ScreenCaptureNavSourceContractTest {

    private val source = SourceRoots.text("src/main/java/com/byd/clusternav/screencapture/ScreenCaptureNavSource.kt")
    private val transport = SourceRoots.text("src/main/java/com/byd/clusternav/screencapture/ScreenCaptureTransport.kt")
    private val offscreen = SourceRoots.text("src/main/java/com/byd/clusternav/screencapture/OffscreenMirrorCapturer.kt")
    private val listener = SourceRoots.text("src/main/java/com/byd/clusternav/NavNotificationListener.kt")
    private val access = SourceRoots.text("src/main/java/com/byd/clusternav/modules/navaccess/NavAccessibilityService.kt")
    private val manifest = SourceRoots.text("src/main/AndroidManifest.xml")

    @Test
    fun `gate requires master ON plus a fresh data source or foreground app (R5)`() {
        assertTrue(source.contains("if (!Prefs.enabled(ctx)) return"), "R5(a): master Nav+HUD gate")
        assertTrue(source.contains("SourceArbiter.isFresh(nowWall)"), "R5(b): data-fresh gate (WALL clock — shared arbiter)")
        assertTrue(
            source.contains("CaptureForegroundSource.isFresh(now)") ||
                source.contains("NavAccessibilitySource.foreground(now)"),
            "R5(b): foreground gate",
        )
        // navFresh flows into the router which returns an empty plan list when the gate is closed.
        assertTrue(source.contains("CaptureRouter.routePlans("), "router drives capture decision (one plan per target)")
    }

    @Test
    fun `single-in-flight + off dedicated single thread + not-fast (R-nf2 R-nf3)`() {
        assertTrue(source.contains("isProcessing.compareAndSet(false, true)"), "single-in-flight guard")
        assertTrue(source.contains("scheduleWithFixedDelay"), "fixed-delay serialization (no overlap)")
        assertTrue(source.contains("newSingleThreadScheduledExecutor"), "dedicated single-thread executor")
        assertTrue(source.contains("isDaemon = true"), "daemon thread (never blocks shutdown)")
        // ≤ 2–4 Hz: tick period must be at least 250 ms.
        val tick = Regex("""TICK_MS\s*=\s*(\d+)L""").find(source)?.groupValues?.get(1)?.toLong()
        assertTrue(tick != null && tick >= 250L, "tick must be ≤4 Hz (period ≥250 ms), was $tick")
    }

    @Test
    fun `every capture-classify path is runCatching-guarded (R-nf1 degrade-safe)`() {
        assertTrue(source.contains("runCatching { tick() }"), "tick wrapped so executor never dies")
        assertTrue(transport.contains("runCatching"), "transport degrade-safe")
        assertTrue(offscreen.contains("runCatching"), "offscreen scaffold degrade-safe")
    }

    @Test
    fun `feeds SourceArbiter on the IMAGE channel so data beats image (R6)`() {
        assertTrue(source.contains("NavChannel.IMAGE"), "image channel")
        assertTrue(source.contains("SourceArbiter.shouldFeed("), "goes through the arbiter")
        // Publish only AFTER the arbiter allows (data > image): shouldFeed guard precedes publish for both targets.
        val arrowGuard = source.indexOf("SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) return")
        assertTrue(arrowGuard >= 0, "arbiter guard present before publish")
    }

    @Test
    fun `routes and classifies EACH requested target in one tick (B3-8 multi-target, degrade-safe per target)`() {
        // VietMap needs BOTH the top-left arrow banner AND the map camera icon → routePlans returns one plan per
        // target; the source captures the display ONCE then iterates and crops/classifies each, guarded per target
        // so one target failing (or having no bounds) never drops the other.
        assertTrue(source.contains("CaptureRouter.routePlans("), "multi-target routing (one plan per target)")
        assertTrue(source.contains("for (plan in plans)"), "iterates every requested target in one tick")
        assertTrue(source.contains("CaptureTarget.ARROW -> handleArrow"), "arrow target handled")
        assertTrue(source.contains("CaptureTarget.CAMERA -> handleCamera"), "camera target handled")
        // per-target runCatching so a failure in one target does not abort the other.
        val loopIdx = source.indexOf("for (plan in plans)")
        assertTrue(loopIdx >= 0, "multi-target loop present")
        val loopBody = source.substring(loopIdx, (loopIdx + 500).coerceAtMost(source.length))
        assertTrue(loopBody.contains("runCatching {"), "each target guarded by runCatching (degrade-safe)")
    }

    @Test
    fun `transport maps each case to the proven fission display or the offscreen scaffold`() {
        assertTrue(source.contains("FISSION_MAIN"), "case 1/2 → fission main")
        assertTrue(source.contains("FISSION_CLUSTER"), "case 3 → fission cluster")
        assertTrue(source.contains("offscreen.capture()"), "case 4 → offscreen MediaProjection scaffold")
        // fission id mapping is the PROVEN one (opposite Android): -d1 MAIN, -d0 CLUSTER.
        assertTrue(transport.contains("FISSION_MAIN = 1"), "fission -d1 = MAIN")
        assertTrue(transport.contains("FISSION_CLUSTER = 0"), "fission -d0 = CLUSTER")
        assertTrue(transport.contains("fission_screencap -d"), "reuses proven fission_screencap path")
    }

    @Test
    fun `diagnostic image save is verbose-gated and honours the storage cap (R-nf4)`() {
        assertTrue(source.contains("if (NavLog.verbose) saveDiag"), "diag save gated by verbose (default OFF)")
        assertTrue(source.contains("DiagStorageCap.enforce"), "respects the A8 storage cap")
    }

    @Test
    fun `lifecycle starts on listener connect and stops on disconnect and destroy`() {
        assertTrue(source.contains("fun start()") && source.contains("fun stop()"), "start/stop lifecycle")
        // connect (enabled) starts; disconnect + destroy stop.
        assertTrue(listener.contains("ScreenCaptureNavSource.get(applicationContext).start()"), "start wired on connect")
        val disc = functionBody(listener, "override fun onListenerDisconnected()")
        val dest = functionBody(listener, "override fun onDestroy()")
        assertTrue(disc.contains("ScreenCaptureNavSource.get(applicationContext).stop()"), "stop on disconnect")
        assertTrue(dest.contains("ScreenCaptureNavSource.get(applicationContext).stop()"), "stop on destroy")
    }

    @Test
    fun `a11y publishes foreground + best-effort capture bounds for all nav packages`() {
        assertTrue(access.contains("CaptureForegroundSource.publish(pkg, b3Now)"), "publishes foreground pkg")
        assertTrue(access.contains("maybePublishCaptureBounds(event, pkg, b3Now)"), "publishes capture bounds")
        assertTrue(access.contains("CaptureBoundsSource.publish("), "bounds reach the router tier-1 holder")
    }

    /**
     * §R-BI — BẤT BIẾN MỘT-PACKAGE-MỘT-KHUNG ở dây :app. Không unit-test được (Bitmap/a11y là Android), nên
     * khoá bằng quét source như phần còn lại của lớp này.
     *
     * KHOÁ: rect dải làn đo trong cửa sổ app KHÁC vẫn crop ra pixel HỢP LỆ của app đang chụp ⇒ `publishLane`
     * sẽ tạo dữ liệu BỊA (nhãn A, pixel B) mà tầng quyết định không có cách nào biết. Vì vậy chốt phải nằm
     * ngay tại NƠI SẢN XUẤT, và producer phải dán nhãn chủ sở hữu.
     */
    @Test
    fun `lane chi crop khi bounds CUNG package (R-BI)`() {
        assertTrue(
            source.contains("NavFrameIdentity.sameFrame(pkg, lb.pkg)"),
            "consumer làn phải chốt danh tính trước khi crop",
        )
        val laneIdx = source.indexOf("LaneBoundsSource.snapshot()")
        assertTrue(laneIdx >= 0, "nhánh làn còn đó")
        val laneBody = source.substring(laneIdx, (laneIdx + 900).coerceAtMost(source.length))
        val gateIdx = laneBody.indexOf("NavFrameIdentity.sameFrame(pkg, lb.pkg)")
        val cropIdx = laneBody.indexOf("cropToFrame(bmp, lb.rect)")
        assertTrue(gateIdx in 0 until cropIdx, "cổng danh tính phải đứng TRƯỚC crop")
        // Producer dán nhãn: LaneBoundsSource.publish bắt buộc có pkg ở vị trí đầu (chữ ký không default).
        assertTrue(
            access.contains("LaneBoundsSource.publish(ownerPkg,"),
            "a11y publish rect làn kèm CHỦ (package runtime của root node)",
        )
        assertTrue(
            access.contains("root.packageName?.toString()"),
            "chủ của rect = chủ của node, KHÔNG phải tiền tố resource com.waze (R4: WazeMod sẽ bị DROP im lặng)",
        )
    }

    /**
     * KHOÁ: bounds tier-1 (node mũi tên/camera) cũng gate theo pkg — rect của app khác vẫn crop ra pixel hợp
     * lệ ⇒ có thể ra **SAI HƯỚNG**, nguy hiểm hơn cả ca làn. Lệch chủ ⇒ null ⇒ router rơi về rect cố định.
     */
    @Test
    fun `bounds mui ten tier-1 cung gate theo package (R-BI)`() {
        assertTrue(
            source.contains("CaptureBoundsSource.snapshot()?.takeIf { NavFrameIdentity.sameFrame(pkg, it.pkg) }"),
            "tier-1 bounds lọc theo danh tính trước khi vào router",
        )
        val tierIdx = source.indexOf("CaptureBoundsSource.snapshot()?.takeIf")
        val routeIdx = source.indexOf("CaptureRouter.routePlans(")
        assertTrue(tierIdx in 0 until routeIdx, "lọc PHẢI đứng trước routePlans")
        assertTrue(access.contains("CaptureBoundsSource.publish(pkg,"), "producer dán nhãn chủ cho rect tier-1")
    }

    /**
     * KHOÁ [P1] B3.53 vòng review (08-23) — **producer phải dán nhãn MỤC TIÊU mà rect được đo cho**.
     *
     * Holder tier-1 là MỘT Ô và trước bản vá chỉ mang `rect` + `pkg`; `CaptureRouter.computeBounds` áp
     * snapshot đó cho MỌI target. Với VietMap, `maybePublishCaptureBounds` chọn node bằng
     * `CaptureTarget.forPackage(pkg)` = **CAMERA**, nên plan **ARROW** của cùng app nhận rect của icon
     * camera, gắn nhãn `A11Y_DYNAMIC` ⇒ đi vào nhánh khớp **MỀM** của [handleArrow] (đúng lớp lỗi B3.53 vá
     * ở tier rect-cố-định). [ĐO] 08-23: `routePlans` phát `PLAN target=ARROW … src=A11Y_DYNAMIC` mang y hệt
     * rect CAMERA; crop 87 khung VietMap qua `VIETMAP_CAMERA_SEED` cho khớp mềm ra mã 1 khung
     * (`arrive_straight` → amap 12, đúng phải 9) còn khớp cứng ra 0.
     *
     * Hành vi thật khoá ở `:core` (`CaptureRouterTest`, `ScreenCaptureHoldersTest`); test này chỉ khoá dây
     * nối ở `:app` — nơi hai producer bơm vào holder.
     */
    @Test
    fun `producer dan nhan MUC TIEU cho rect tier-1 (B3_53 review)`() {
        // Nhánh view-id đích danh (`navBarDirection`) = KHUNG VẼ mũi tên ⇒ phải khai ARROW.
        assertTrue(
            access.contains("CaptureBoundsSource.publish(pkg, CaptureTarget.ARROW,"),
            "rect `navBarDirection` phải khai target ARROW",
        )
        // Nhánh heuristic: khai đúng cái target vừa dùng để CHỌN node, không hardcode.
        val body = functionBody(access, "private fun maybePublishCaptureBounds(")
        assertTrue(
            body.contains("CaptureBoundsSource.publish(pkg, target,"),
            "rect heuristic phải khai đúng target đã dùng để pick node (VietMap = CAMERA), không hardcode ARROW",
        )
        val pickIdx = body.indexOf("CaptureBoundsHeuristic.pick(target,")
        val pubIdx = body.indexOf("CaptureBoundsSource.publish(pkg, target,")
        assertTrue(pickIdx in 0 until pubIdx, "target dùng để pick và target khai lên holder phải là MỘT")
    }

    /**
     * KHOÁ [P1] 08-23 vòng 3b — **đường glyph cũng phải chốt danh tính khung, và theo cách của riêng nó**.
     *
     * Đây là đường DUY NHẤT tự đi tìm rect thay vì nhận rect từ a11y, nên `NavFrameIdentity.sameFrame` không
     * áp được. Thứ tương đương là ô cửa sổ: `loc.windowRect`. [ĐO] trên khung chia đôi dựng từ hai fixture
     * thật (Waze trái rẽ TRÁI / VietMap phải rẽ PHẢI): thiếu `windowRect` thì locator trả mũi tên của app
     * NỬA KIA với Hamming 0 ⇒ `publishArrow(pkg=VietMap, amap=2)` trong khi VietMap đang bảo rẽ phải.
     * Không biết cửa sổ ⇒ bỏ đường glyph nhịp này (`return false`), không đoán "cả khung".
     */
    @Test
    fun `duong glyph bo nhip khi KHONG biet o cua so (R-BI)`() {
        val body = functionBody(source, "private fun handleArrowByGlyph(")
        val winIdx = body.indexOf("loc.windowRect")
        val locIdx = body.indexOf("NavGlyphLocator.locate(")
        assertTrue(winIdx in 0 until locIdx, "phải đọc windowRect TRƯỚC khi gọi locator")
        assertTrue(
            Regex("""loc\.windowRect[\s\S]{0,160}\?:\s*return false""").containsMatchIn(body),
            "windowRect null ⇒ return false (rơi xuống rect cố định), KHÔNG được truyền null cho locator",
        )
    }

    /**
     * KHOÁ [P1] 08-23 vòng 3b — **dò được bbox ⇒ tầng glyph SỞ HỮU kênh ARROW nhịp đó**.
     *
     * Rect ARROW cố định (`CaptureRouter.WAZE_ARROW_BANNER_D240`) hiệu chuẩn trên banner Waze và tra theo
     * (target, W, H) chứ không theo package, nên nó cũng bị áp lên khung VietMap. Khi locator đã ĐO ĐƯỢC mũi
     * tên nằm chỗ khác, rơi tiếp xuống rect đó rồi chấm bằng `classifyDetailed` (có nhánh NCC mềm) là bốc
     * thăm: [ĐO] 3/48 khung ra mã, **2 SAI HƯỚNG** (`depart_right`→11 thay vì 3, `fork_slight_right`→3 thay
     * vì 5). Nên sau khi `locate` trả non-null, mọi lối thoát của hàm phải là `return true`.
     */
    @Test
    fun `do duoc bbox thi tang glyph SO HUU kenh ARROW (khong roi ve rect co dinh)`() {
        val body = functionBody(source, "private fun handleArrowByGlyph(")
        val locIdx = body.indexOf("NavGlyphLocator.locate(")
        assertTrue(locIdx >= 0, "còn gọi locator")
        val after = body.substring(locIdx)
        // Sau lời gọi locate, chỉ còn ĐÚNG MỘT `return false` — chính cái `?: return false` của locate.
        assertEquals(
            1, Regex("""return false""").findAll(after).count(),
            "sau khi locate trả non-null, không được còn đường nào rơi xuống rect ARROW cố định",
        )
        assertTrue(
            source.contains("if (glyphHandled && plan.target == CaptureTarget.ARROW) continue"),
            "caller phải bỏ plan ARROW cố định khi tầng glyph đã sở hữu nhịp",
        )
    }

    /**
     * KHOÁ [P1] B3.53 (08-23) — **crop lấy bằng rect CỐ ĐỊNH chỉ được chấp nhận khớp CỨNG**.
     *
     * `CaptureCalibration` tra rect ARROW theo `Key(target, displayW, displayH)`, KHÔNG theo package, nên
     * `WAZE_ARROW_BANNER_D240` (hiệu chuẩn trên banner Waze) cũng được áp lên khung VietMap. Crop sai chỗ
     * vẫn có mực ⇒ vẫn ra chữ ký; mà `ManeuverSignature.classify` có nhánh **NCC mềm** (0.45) nên nó gần
     * như luôn tìm được một cái tên. [ĐO] trên 87 khung VietMap ghép từ asset: 3 khung ra mã, **2 SAI
     * HƯỚNG** (`depart_right`→amap 11 thay vì 3; `fork_slight_right`→amap 3 thay vì 5), cả 3 trượt Hamming
     * 27/37/38 ≫ 18. Đổi sang `classifyStrict` cho tier này ⇒ 3 → **0** (khoá bằng
     * `FixedRectSoftMatchTest` ở :core).
     *
     * Tier `A11Y_DYNAMIC` phải GIỮ NGUYÊN đường cũ: ở đó rect là bounds ĐO ĐƯỢC của chính node mũi tên nên
     * giả định "crop đúng quy ước khung vẽ" có căn cứ, và đó là đường OpenBYD đã proven (CLAUDE.md §6).
     */
    @Test
    fun `rect CO DINH chi chap nhan khop CUNG, tier a11y giu nguyen (B3_53)`() {
        val body = functionBody(source, "private fun handleArrow(")
        assertTrue(
            body.contains("boundsSource == BoundsSource.FIXED_CALIBRATED"),
            "handleArrow phải rẽ nhánh theo NGUỒN GỐC của rect (đo được / hiệu chuẩn sẵn), không theo tên gói",
        )
        val strictIdx = body.indexOf("ManeuverSignature.classifyStrict(")
        val softIdx = body.indexOf("ManeuverSignature.classify(")
        assertTrue(strictIdx >= 0, "tier rect cố định phải dùng classifyStrict (chỉ Hamming, KHÔNG NCC)")
        assertTrue(softIdx >= 0, "tier a11y phải giữ nguyên classify (còn nhánh NCC) — đường đã proven")
        val fixedIdx = body.indexOf("boundsSource == BoundsSource.FIXED_CALIBRATED")
        assertTrue(fixedIdx in 0 until strictIdx, "classifyStrict phải nằm TRONG nhánh rect cố định")
        assertTrue(strictIdx < softIdx, "nhánh rect cố định đứng trước nhánh a11y (else)")
        // Caller phải truyền tầng bounds THẬT của plan xuống, không hardcode.
        assertTrue(
            source.contains("CaptureTarget.ARROW -> handleArrow(pkg, cropped, plan.boundsSource, now, nowWall)"),
            "caller phải truyền plan.boundsSource — hardcode một tầng là mất cả cổng",
        )
        // Đường notification large-icon (nơi NCC SINH RA để phục vụ) KHÔNG được đổi sang khớp cứng:
        // [ĐO] 256/418 khung GMaps nhiễu-hình-học sẽ câm nếu bỏ NCC ở đó.
        assertFalse(
            listener.contains("classifyStrict"),
            "NavNotificationListener là đường notification large-icon — nhánh NCC ở đó là thiết yếu (B3.53)",
        )
    }

    @Test
    fun `no exported MediaProjection consent surface (R-nf6)`() {
        // The consent activity MUST be exported=false in the manifest.
        val idx = manifest.indexOf(".screencapture.CaptureRequestActivity")
        assertTrue(idx >= 0, "CaptureRequestActivity registered")
        val window = manifest.substring((idx - 200).coerceAtLeast(0), (idx + 200).coerceAtMost(manifest.length))
        assertTrue(window.contains("android:exported=\"false\""), "consent activity must be non-exported")
        // And no new exported test/receiver surface was added by this slice.
        assertFalse(source.contains("exported = true"), "no exported surface in the capture source")
    }

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var opened = false
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> { depth++; opened = true }
                '}' -> if (opened && --depth == 0) return text.substring(start, i + 1)
            }
        }
        error("unterminated $signature")
    }
}
