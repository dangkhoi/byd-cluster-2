package com.byd.clusternav.screencapture

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.DiagStorageCap
import com.byd.clusternav.NavLog
import com.byd.clusternav.Prefs
import com.byd.clusternav.asPixelFrame
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.modules.navaccess.NavAccessibilitySource
import com.byd.clusternav.navigation.ManeuverSignature
import com.byd.clusternav.navigation.LaneSignature
import com.byd.clusternav.navigation.NavChannel
import com.byd.clusternav.navigation.PixelFrame
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.screencapture.CaptureBoundsSource
import com.byd.clusternav.navigation.NavFrameIdentity
import com.byd.clusternav.navigation.screencapture.AppLocation
import com.byd.clusternav.navigation.screencapture.BoundsSource
import com.byd.clusternav.navigation.screencapture.CaptureCase
import com.byd.clusternav.navigation.screencapture.CaptureForegroundSource
import com.byd.clusternav.navigation.screencapture.CaptureLocationResolver
import com.byd.clusternav.navigation.screencapture.LaneBoundsSource
import com.byd.clusternav.navigation.screencapture.CaptureRouter
import com.byd.clusternav.navigation.screencapture.NavGlyphLocator
import com.byd.clusternav.navigation.screencapture.CaptureTarget
import com.byd.clusternav.navigation.screencapture.CapturePlan
import com.byd.clusternav.navigation.screencapture.CropRect
import com.byd.clusternav.navigation.screencapture.DisplayGeometry
import com.byd.clusternav.navigation.screencapture.ScreenCaptureSignal
import com.byd.clusternav.navigation.screencapture.VietMapCameraMatcher
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Nguồn dẫn đường bằng SCREEN-CAPTURE (B3, spec `waze-vietmap-screen-capture` §4.1). Điều phối MỘT nhịp chụp:
 *
 *   GATE (R5) → dựng [AppLocation] (`am stack`) → [CaptureRouter] chọn case + bounds → capture (transport theo
 *   case) → crop → `ManeuverSignature` (mũi tên Waze) / [VietMapCameraMatcher] (camera VietMap) → trọng tài
 *   `SourceArbiter` (data > image) → publish [ScreenCaptureSignal].
 *
 * Envelope an toàn (mirror `SegmentShotCapturer`, R-nf1/2/3):
 *   - Chạy trên EXECUTOR đơn-luồng daemon RIÊNG (không main/notification thread).
 *   - Nhịp ≤ 2–4 Hz ([TICK_MS]); scheduleWithFixedDelay ⇒ nhịp sau chỉ chạy khi nhịp trước XONG (single-in-flight
 *     by construction) + thêm guard [isProcessing] (mirror `isPixelCopyInProgress`/`isProcessing` OpenBYD).
 *   - MỌI capture/classify bọc `runCatching` — lỗi ⇒ bỏ frame, KHÔNG crash, KHÔNG đụng feed cụm.
 *   - Lưu ảnh chẩn đoán CHỈ khi [NavLog.verbose] (mặc định OFF) + tôn trọng [DiagStorageCap] (A8).
 *
 * LÁT NÀY (B3 :app) DỪNG ở feed trọng tài + publish [ScreenCaptureSignal]. Đầu ra thật (làn cụm / HUD / badge
 * camera = T7) cần verify từng case trên xe nên KHÔNG tự ghi cụm ở đây (trace-den-tan-cung: không tuyên bố
 * "chạy" khi chưa verify). CASE 4 (offscreen MediaProjection) + a11y bounds + giá trị calibration = VERIFY-ON-CAR.
 *
 * Process-singleton: một executor/nhịp cho cả app (giống `SegmentShotCapturer`).
 */
class ScreenCaptureNavSource private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val transport = ScreenCaptureTransport(appContext)
    private val offscreen = OffscreenMirrorCapturer(appContext)
    // BUILTIN + template nạp lúc chạy (OQ4 trên xe). Rỗng ở production ⇒ camera match luôn NONE → an toàn, không
    // false-positive. Dùng fromRegistry() để nếu orchestrator nạp template camera thật (trước khi start) thì honor.
    private val cameraMatcher = VietMapCameraMatcher.fromRegistry()

    private val lifecycleLock = Any()
    @Volatile private var executor: ScheduledExecutorService? = null
    @Volatile private var future: ScheduledFuture<*>? = null
    private val isProcessing = AtomicBoolean(false)
    @Volatile private var started = false

    /** Bật nguồn khi GATE mở (master Nav+HUD ON — gọi từ `NavNotificationListener.onListenerConnected`). Idempotent. */
    fun start() {
        synchronized(lifecycleLock) {
            if (started) return
            val exec = executor ?: Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "screencap-nav").apply { isDaemon = true }
            }.also { executor = it }
            future = runCatching {
                exec.scheduleWithFixedDelay({ safeTick() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
            }.getOrNull()
            started = true
            Log.i(TAG, "screen-capture nav source STARTED (tick=${TICK_MS}ms)")
        }
    }

    /** Dừng khi idle (gọi từ `onListenerDisconnected`/`onDestroy`). Giữ executor để bật lại. Idempotent. */
    fun stop() {
        synchronized(lifecycleLock) {
            if (!started) return
            runCatching { future?.cancel(false) }
            future = null
            started = false
        }
        runCatching { offscreen.release() }
        ScreenCaptureSignal.clear()
        CaptureBoundsSource.clear()
        CaptureForegroundSource.clear()
        Log.i(TAG, "screen-capture nav source STOPPED")
    }

    private fun safeTick() {
        // Guard single-in-flight (belt-and-suspenders: scheduleWithFixedDelay đã serial hoá; guard chống mọi
        // đường gọi thẳng tick). Bọc toàn bộ trong runCatching để executor KHÔNG bao giờ chết vì 1 nhịp lỗi.
        if (!isProcessing.compareAndSet(false, true)) return
        try {
            runCatching { tick() }.onFailure { Log.w(TAG, "tick threw (dropped frame)", it) }
        } finally {
            isProcessing.set(false)
        }
    }

    private fun tick() {
        val ctx = appContext
        // GATE R5(a): master Nav+HUD ON.
        if (!Prefs.enabled(ctx)) return
        // HAI MIỀN ĐỒNG HỒ — KHÔNG được trộn (nếu trộn thì mọi phép so tươi đều sai → R5/R6 gãy):
        //   • now (MONOTONIC, elapsedRealtime): các holder B3-local (a11y foreground / capture bounds / signal),
        //     tất cả đều được publish bằng elapsedRealtime trong NavAccessibilityService + NavAccessibilitySource.
        //   • nowWall (WALL, currentTimeMillis): SourceArbiter DÙNG CHUNG — các caller khác của nó (WazeMod HLP/1
        //     + NavNotificationListener) đóng mốc bằng System.currentTimeMillis(); đọc/ghi nó bằng đồng hồ monotonic
        //     sẽ khiến isFresh/isDataFresh luôn "tươi" (gate kẹt mở + ảnh không bao giờ fallback khi data im).
        val now = SystemClock.elapsedRealtime()
        val nowWall = System.currentTimeMillis()
        // GATE R5(b): có nguồn dẫn đang active/tươi (data — đồng hồ arbiter = WALL) HOẶC app dẫn foreground (a11y —
        // đồng hồ MONOTONIC). Không dẫn → drain+skip.
        val dataFresh = SourceArbiter.isFresh(nowWall)
        val fgFresh = CaptureForegroundSource.isFresh(now) || NavAccessibilitySource.foreground(now)
        if (!dataFresh && !fgFresh) return

        // B3.10: CAPTURE route theo app ĐANG HIỂN THỊ (CaptureForegroundSource — B3.13 window-enum) khi còn
        // tươi, KHÔNG theo activeSource (nguồn giữ-khoá-CỤM có thể là app cũ đã nền/tắt mà vẫn "tươi" qua poll
        // → route SAI ARROW/CAMERA + crop nhầm pkg). Fallback: activeSource → bất kỳ foreground pkg.
        val pkg = CaptureForegroundSource.pkg?.takeIf { CaptureForegroundSource.isFresh(now) }
            ?: SourceArbiter.activeSource ?: CaptureForegroundSource.pkg ?: return

        // Dựng AppLocation từ `am stack list` (đường THUẦN CaptureLocationResolver test off-car).
        val amOut = runCatching {
            SimpleCastRuntime.coordinator(ctx).executeShell("am stack list")
        }.getOrNull()?.takeIf { it.success }?.stdout ?: return
        val loc = CaptureLocationResolver.resolve(
            amOut = amOut,
            pkg = pkg,
            navFresh = true,                                    // đã qua gate ở trên
            foregroundHint = CaptureForegroundSource.isFresh(now),
        )

        // Chọn case (kích thước không ảnh hưởng selectCase) rồi capture display tương ứng.
        val probeGeom = DisplayGeometry(0, 0)
        val case = CaptureRouter.selectCase(loc, probeGeom)
        val bmp = capture(case) ?: return                        // degrade: không chụp được → bỏ frame

        // Geometry THẬT = kích thước ảnh chụp (đúng không gian bounds) + dpi THẬT của display (đọc từ cùng
        // output `am stack list`, không tốn thêm round-trip). dpi cần cho NavGlyphLocator vì người dùng chỉnh
        // được mật độ của cụm khi cast (CastShell: `wm density`), mà cỡ glyph tỉ lệ thẳng với dpi.
        val geom = DisplayGeometry(bmp.width, bmp.height, densityDpi = loc.densityDpi)
        // B3.8: VietMap khi dẫn phơi CẢ banner mũi tên (top-left) LẪN icon camera trên bản đồ → routePlans trả 1
        // plan MỖI target (VietMap = [ARROW, CAMERA]; Waze/WazeMod/GMaps = [ARROW] — tương đương hành vi cũ). Rỗng
        // ⇒ gate đóng. Chụp display MỘT lần (ở trên) rồi crop+classify theo TỪNG target; mỗi target bọc runCatching
        // RIÊNG (degrade-safe R-nf1: một target lỗi/không-bounds KHÔNG rớt target kia trong cùng nhịp).
        // ── TẦNG GLYPH (B3.27, 08-22) — chạy TRƯỚC rect cố định cho kênh ARROW ─────────────────────────
        // Người dùng chỉnh dpi/kích-thước/vị-trí cửa sổ cast và cast 1 hay 2 app, thêm nữa Waze tự đổi bố cục
        // banner theo độ dài tên đường ⇒ MỌI rect cố định đều trượt. Locator dò bbox mực của glyph bằng pixel
        // (đảo sáng · vành tối 4 phía · sàn max(dp, %chiều-cao-cửa-sổ) · tỉ lệ khung · tỉ lệ lấp · neo trái),
        // neo trong ô app THẬT (`loc.windowRect` — BẮT BUỘC, xem KDoc `NavGlyphLocator.locate`) nên bất biến
        // với cả bốn biến đó. Dò ĐƯỢC BBOX ⇒ tầng glyph SỞ HỮU kênh ARROW nhịp này và BỎ QUA plan ARROW cố
        // định (kể cả khi registry chưa có template — xem [handleArrowByGlyph]).
        // KHÔNG dò ra (hoặc không biết ô cửa sổ) ⇒ rơi xuống đường cũ (rect cố định), không đụng đường proven (§6).
        val glyphHandled = runCatching { handleArrowByGlyph(pkg, bmp, loc, geom, now, nowWall) }
            .onFailure { Log.w(TAG, "glyph locator threw (bỏ qua, dùng rect cố định)", it) }
            .getOrDefault(false)

        // §R-BI: bounds tier-1 (node mũi tên/camera do a11y đo) chỉ dùng được khi ĐÚNG app đang chụp — rect
        // của app khác vẫn crop ra pixel hợp lệ ⇒ có thể ra SAI HƯỚNG (nguy hiểm hơn cả ca làn). Lệch chủ ⇒
        // null ⇒ router rơi xuống tier rect cố định (đường cũ, đã có test).
        val tier1 = CaptureBoundsSource.snapshot()?.takeIf { NavFrameIdentity.sameFrame(pkg, it.pkg) }
        val plans = CaptureRouter.routePlans(loc, geom, tier1, now)
        if (plans.isEmpty()) return
        for (plan in plans) {
            if (glyphHandled && plan.target == CaptureTarget.ARROW) continue
            runCatching {
                if (plan.bounds.isEmpty()) return@runCatching       // target này không có vùng hợp lệ → bỏ, giữ target kia
                val cropped = cropToFrame(bmp, plan.bounds) ?: return@runCatching
                when (plan.target) {
                    CaptureTarget.ARROW -> handleArrow(pkg, cropped, plan.boundsSource, now, nowWall)
                    CaptureTarget.CAMERA -> handleCamera(pkg, cropped, now, nowWall)
                }
                if (NavLog.verbose) saveDiag(bmp, plan, now)
            }.onFailure { Log.w(TAG, "target ${plan.target} threw (dropped frame)", it) }
        }
        // B3 T1b: LÀN — nếu a11y có bounds view lane-guidance còn tươi → crop bmp → LaneSignature → publishLane
        // (kênh riêng, song song arrow/camera). Degrade-safe; không có bounds/làn → bỏ.
        runCatching {
            val lb = LaneBoundsSource.snapshot()
            // §R-BI — CHỐT TẠI NƠI SẢN XUẤT: rect đo trong cửa sổ app KHÁC vẫn crop ra pixel HỢP LỆ của app
            // đang chụp ⇒ publishLane(pkg, …) bên dưới sẽ tạo dữ liệu BỊA (nhãn A, pixel B) mà tầng quyết định
            // không có cách nào biết. Lệch chủ ⇒ bỏ làn nhịp này (im lặng, degrade-safe).
            if (lb != null && now - lb.capturedAtMs <= LaneBoundsSource.FRESH_MS &&
                NavFrameIdentity.sameFrame(pkg, lb.pkg)
            ) {
                cropToFrame(bmp, lb.rect)?.let { laneCrop ->
                    LaneSignature.classify(laneCrop, lb.laneCount.takeIf { it > 0 })
                        ?.takeIf { !it.isEmpty() }
                        ?.let { info ->
                            if (SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) {
                                ScreenCaptureSignal.publishLane(pkg, info, now)
                                if (NavLog.verbose) Log.i(TAG, "lane(image) pkg=$pkg lanes=${info.count} rec=${info.lanes.count { it.recommended }}")
                            }
                        }
                }
            }
        }.onFailure { Log.w(TAG, "lane classify threw (dropped)", it) }
    }

    /** Transport theo case: 1/2 = màn chính (fission -d1), 3 = cụm (fission -d0), 4 = offscreen MediaProjection. */
    private fun capture(case: CaptureCase): Bitmap? = when (case) {
        CaptureCase.FULL_MAIN, CaptureCase.HALF_MAIN_SPLIT ->
            transport.captureFission(ScreenCaptureTransport.FISSION_MAIN)
        CaptureCase.CLUSTER_CAST ->
            transport.captureFission(ScreenCaptureTransport.FISSION_CLUSTER)
        CaptureCase.NOT_ACTIVE ->
            offscreen.capture()                                  // scaffold, VERIFY-ON-CAR (null nếu chưa có token)
    }

    /** Crop `Bitmap` về bounds (đã clamp về khung ảnh) → `PixelFrame`. null nếu vùng rỗng/không hợp lệ. */
    private fun cropToFrame(bmp: Bitmap, bounds: CropRect): PixelFrame? = runCatching {
        val clamped = bounds.clampTo(CropRect(0, 0, bmp.width, bmp.height))
        if (clamped.isEmpty()) return null
        // Bitmap.createBitmap(sub) = đường on-device rẻ; PixelFrameOps.crop (:core) là bản THUẦN tương đương đã
        // test off-car (route→crop→classify) — cùng pixel. Ở đây dùng sub-bitmap để giảm alloc mỗi nhịp.
        val sub = Bitmap.createBitmap(bmp, clamped.left, clamped.top, clamped.width, clamped.height)
        sub.asPixelFrame()
    }.getOrNull()

    /**
     * Dò glyph bằng pixel rồi chấm bằng [WazeArrowRegistry].
     *
     * true = **đường glyph SỞ HỮU kênh ARROW nhịp này** ⇒ caller bỏ qua plan ARROW cố định. Điều kiện đủ để
     * sở hữu là **[NavGlyphLocator] dò ra một bbox**, chứ KHÔNG phải "đã publish": ba ca cho true, hai ca sau
     * không publish gì:
     *   · đã `publishArrow` (ca thường);
     *   · `SourceArbiter` CHẶN gói này nhịp này (`shouldFeed` = false). Rơi tiếp xuống rect cố định là vô
     *     nghĩa — [handleArrow] hỏi CÙNG một cổng với CÙNG (pkg, mode, IMAGE) nên chắc chắn cũng bị chặn, chỉ
     *     tốn thêm một lượt `classifyManeuver`/`classify` rồi vứt. Trả true = bỏ sớm, KHÔNG phải "đã bắn";
     *   · dò ra bbox nhưng **[WazeArrowRegistry] chưa có template** cho glyph đó ⇒ **im lặng** (xem dưới).
     * false = KHÔNG biết mũi tên ở đâu (không có `windowRect`, hoặc locator trả null) ⇒ caller VẪN chạy
     * đường rect cố định.
     *
     * ⚠ [P1] 08-23 vòng 3b — VÌ SAO "dò ra bbox nhưng registry trượt" nay IM LẶNG thay vì rơi xuống rect cố
     * định. Rect ARROW cố định là `CaptureRouter.WAZE_ARROW_BANNER_D240 = (78,50,158,163)`, hiệu chuẩn trên
     * banner **Waze** và tra theo (target, W, H) — KHÔNG theo package — nên nó cũng được áp lên khung
     * VietMap. Khi locator đã ĐO ĐƯỢC mũi tên nằm ở chỗ khác, đem một rect phỏng đoán đè lên rồi chấm bằng
     * [ManeuverSignature.classifyDetailed] (`match` **?: matchNCC** — khớp MỀM, ngưỡng NCC 0.45) là bốc thăm.
     * [ĐO] trên 87 khung VietMap: trong 48 khung đường glyph không giải được, rect cố định ra mã cho 3 khung
     * và **2 trong 3 là SAI HƯỚNG**:
     * ```
     * depart_right      -> maneuver_roundabout_enter_and_exit_cw_normal_left  amap=11  (đúng phải là 3)
     * fork_slight_right -> maneuver_turn_normal_right                         amap=3   (đúng phải là 5)
     * fork              -> maneuver_turn_normal_right                         amap=3
     * ```
     * Cả 3 đều qua nhánh NCC (Hamming tới template gần nhất 27/37/38 ≫ ngưỡng 18). CLAUDE.md: mũi tên SAI
     * HƯỚNG nguy hiểm hơn hẳn không hiện gì ⇒ đánh đổi phủ-sóng lấy an-toàn, có chủ ý.
     * Đường rect cố định vẫn nguyên vẹn cho ca `locate` trả null (Waze bố cục lạ, app chưa phủ, v.v.) — tức
     * không đảo thứ tự đường đã proven ngoài hiện trường (CLAUDE.md §6). Nhánh NCC-sai-hướng của CHÍNH đường
     * rect cố định đã đóng ở **B3.53** (08-23): tier [BoundsSource.FIXED_CALIBRATED] nay chỉ chấp nhận khớp
     * CỨNG — xem KDoc [handleArrow].
     *
     * Hai đường dùng quy ước khớp KHỚP NHAU nội bộ (đường này = bbox mực × [WazeArrowRegistry]; đường kia =
     * khung-vẽ × [ManeuverRegistry]) nên không có nguy cơ lai quy ước — cái lỗi ra SAI hướng do lai quy ước
     * chỉ xảy ra khi đem crop bbox-mực đi khớp registry khung-vẽ.
     *
     * ⚠ ĐÍNH CHÍNH 08-23 vòng 2: câu trên chỉ ĐÚNG TỪ BẢN VÁ NÀY. Trước đó `ManeuverSignature.match`/`matchNCC`
     * — đường mà [handleArrow] bên dưới dùng — quét CẢ HAI registry, nên crop khung-vẽ VẪN đang được khớp với
     * template bbox-mực: đúng cái lai quy ước mà đoạn này khẳng định là không có. Nay hai matcher đã tách hẳn
     * (xem KDoc `ManeuverSignature.match`) nên phát biểu mới thành sự thật.
     *
     * Và registry mực KHÔNG còn "chỉ có 2 maneuver": [WazeArrowRegistry] có 38 template / ~16 lớp. Đo bằng
     * chính [NavGlyphLocator] trên 87 khung VietMap ghép (B3.47 vòng 3, khoá bằng `VietMapGlyphGateTest`):
     * locator dò đúng mũi tên **86/87**, đi trọn đường tới mã AMAP **39/87** — phần hụt nay là do THIẾU
     * TEMPLATE chứ không còn do cổng locator chặn (B3.52).
     */
    private fun handleArrowByGlyph(
        pkg: String, bmp: Bitmap, loc: AppLocation, geom: DisplayGeometry, now: Long, nowWall: Long,
    ): Boolean {
        val full = bmp.asPixelFrame()
        // §R-BI cho đường glyph: KHÔNG biết ô cửa sổ app ⇒ không được đoán "cả khung". Chia đôi màn + windowRect
        // null thì locator sẽ trả mũi tên của APP KIA với Hamming 0 ⇒ publish SAI HƯỚNG dưới tên app dẫn
        // ([ĐO] 08-23 vòng 3b, xem KDoc `NavGlyphLocator.locate`). Không biết ⇒ rơi xuống đường cũ.
        val win = loc.windowRect?.clampTo(CropRect(0, 0, bmp.width, bmp.height))?.takeIf { !it.isEmpty() }
            ?: return false
        val ink = NavGlyphLocator.locate(full, win, geom.effectiveDensityDpi) ?: return false
        // Từ đây trở xuống đường glyph ĐÃ SỞ HỮU kênh ARROW nhịp này ⇒ mọi lối thoát đều `return true`.
        val frame = cropToFrame(bmp, ink) ?: return true
        if (NavLog.verbose) {
            val sig = runCatching { ManeuverSignature.signatureBits(frame) }.getOrNull()
            Log.i(TAG, "glyph-ink pkg=$pkg rect=$ink dpi=${geom.effectiveDensityDpi} sig=$sig")
        }
        val m = runCatching { ManeuverSignature.classifyWazeInk(frame) }.getOrNull() ?: return true
        val amap = m.amap ?: run {
            if (NavLog.verbose) Log.i(TAG, "glyph-ink pkg=$pkg CHƯA có trong registry Waze (${m.name}) → IM LẶNG nhịp này")
            return true
        }
        if (!SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) return true
        // m.maneuver = hướng CÓ CHIỀU của họ vòng xuyến (null cho mọi maneuver khác) — y hệt thứ [handleArrow]
        // lấy từ `classifyManeuver` ở đường rect cố định. Trước 08-23 vòng 2 chỗ này truyền cứng `null`, nên
        // glyph vòng-xuyến-trái của VietMap ra CAN vòng-xuyến GENERIC (mất chiều) dù registry biết chiều.
        ScreenCaptureSignal.publishArrow(pkg, m.maneuver, amap, now)
        if (NavLog.verbose) Log.i(TAG, "arrow(glyph) pkg=$pkg name=${m.name} amap=$amap man=${m.maneuver}")
        return true
    }

    /**
     * Chấm kênh ARROW cho crop lấy theo [boundsSource].
     *
     * ⚠ [P1] B3.53 (08-23) — **rect CỐ ĐỊNH chỉ được chấp nhận khớp CỨNG**. `CaptureRouter` tra rect ARROW
     * cố định theo `(target, W, H)` chứ KHÔNG theo package, nên `WAZE_ARROW_BANNER_D240` (hiệu chuẩn trên
     * banner Waze) cũng được áp lên khung VietMap; crop sai chỗ vẫn có mực và [ManeuverSignature.classify]
     * có nhánh NCC MỀM (0.45) nên luôn tìm được một cái tên. [ĐO] trên 87 khung VietMap ghép từ asset: rect
     * cố định ra mã cho 3 khung, **2 SAI HƯỚNG** (`depart_right`→amap 11 thay vì 3; `fork_slight_right`→
     * amap 3 thay vì 5), cả 3 trượt Hamming 27/37/38 ≫ 18. Dùng [ManeuverSignature.classifyStrict] (chỉ
     * Hamming) cho tier này ⇒ 3 → **0**.
     *
     * Tier [BoundsSource.A11Y_DYNAMIC] giữ NGUYÊN đường cũ (`classifyManeuver` + `classify`, có NCC): ở đó
     * rect là bounds ĐO ĐƯỢC của một node **được chọn CHO chính target ARROW**, nên giả định "crop đúng quy
     * ước khung vẽ" có căn cứ, và đó là đường OpenBYD đã proven (CLAUDE.md §6 — không đảo thứ tự đường đang
     * chạy tốt). Rẽ nhánh theo NGUỒN GỐC của rect (đo được hay hiệu chuẩn sẵn), KHÔNG theo tên gói (§7).
     *
     * ⚠ ĐÍNH CHÍNH 08-23 (vòng review B3.53): câu trên chỉ ĐÚNG TỪ BẢN VÁ `CaptureBounds.target`. Trước đó
     * holder tier-1 không mang mục tiêu và `CaptureRouter.computeBounds` áp snapshot cho MỌI target, nên với
     * VietMap (producer chọn node bằng `CaptureTarget.forPackage` = CAMERA) plan ARROW nhận **rect icon
     * camera** rồi rơi thẳng vào nhánh `else` này — đúng lớp lỗi mà chính KDoc này nói là đã chặn. [ĐO]
     * 08-23: `PLAN target=ARROW … src=A11Y_DYNAMIC` mang y hệt rect CAMERA; crop 87 khung VietMap qua
     * `CaptureCalibration.VIETMAP_CAMERA_SEED` cho khớp mềm ra mã 1 khung (`arrive_straight` → amap 12,
     * đúng phải 9), khớp cứng ra 0. Nay tầng-1 đòi `a11y.target == plan.target` (khoá bằng
     * `CaptureRouterTest`), nên nhánh `else` chỉ còn nhận rect thật sự đo cho mũi tên.
     *
     * ⚠ CÒN LẠI, CHƯA VÁ (ghi trong backlog B3.53 mục "còn lại"): trong hai producer của rect ARROW thì
     * `navBarDirection` là view-id ĐÍCH DANH (proven), còn `CaptureBoundsHeuristic.pick(ARROW, …)` là
     * **phỏng đoán** — nó chấm điểm theo từ khoá/kích thước và có bậc `IMAGE_BASE` nhận cả `ImageView` không
     * từ khoá, tức vẫn có thể trả về node KHÔNG phải mũi tên. Phân biệt hai nguồn đó cần thêm một bậc
     * provenance nữa; chưa làm trong vòng này, và bản thân heuristic vẫn là VERIFY-ON-CAR (OQ2).
     */
    private fun handleArrow(
        pkg: String,
        frame: PixelFrame,
        boundsSource: BoundsSource,
        now: Long,
        nowWall: Long,
    ) {
        // TÁI DÙNG NGUYÊN ManeuverSignature (đây chính là processArrowPixels của OpenBYD).
        // B3.6 CALIBRATE AID (verbose): in chữ ký 225-bit MỖI frame arrow (kể cả khi đang khớp SAI template
        // GMaps gần nhất) để dựng template glyph Waze/VietMap ĐÚNG NHÃN cho WazeArrowRegistry (off-car/on-car).
        if (NavLog.verbose) {
            val sig = runCatching { ManeuverSignature.signatureBits(frame) }.getOrNull()
            Log.i(TAG, "arrow-sig pkg=$pkg ${frame.width}x${frame.height} src=$boundsSource sig=$sig")
        }
        val maneuver: com.byd.clusternav.navigation.Maneuver?
        val amap: Int?
        if (boundsSource == BoundsSource.FIXED_CALIBRATED) {
            val m = runCatching { ManeuverSignature.classifyStrict(frame) }.getOrNull()
            maneuver = m?.maneuver
            amap = m?.amap
            if (amap == null && NavLog.verbose) {
                Log.i(TAG, "arrow(image) pkg=$pkg rect CỐ ĐỊNH không khớp cứng (${m?.name}) → IM LẶNG nhịp này")
            }
        } else {
            maneuver = runCatching { ManeuverSignature.classifyManeuver(frame) }.getOrNull()
            amap = runCatching { ManeuverSignature.classify(frame) }.getOrNull()
        }
        if (maneuver == null && amap == null) return             // không đọc được mũi tên → bỏ frame
        // Trọng tài: ảnh là FALLBACK — data cùng app còn tươi thì bỏ (không chiếm khoá). data > image (R6).
        // shouldFeed đọc/ghi state WALL của arbiter (dùng chung WazeMod/NavNotif → currentTimeMillis) ⇒ nowWall.
        if (!SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) return
        ScreenCaptureSignal.publishArrow(pkg, maneuver, amap, now)   // publish = holder B3-local ⇒ monotonic now
        if (NavLog.verbose) Log.i(TAG, "arrow(image) pkg=$pkg src=$boundsSource maneuver=$maneuver amap=$amap")
    }

    private fun handleCamera(pkg: String, frame: PixelFrame, now: Long, nowWall: Long) {
        val match = runCatching { cameraMatcher.match(frame) }.getOrNull() ?: return
        if (!match.hasCamera) return                             // template rỗng (OQ4) ⇒ luôn NONE ở production
        if (!SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) return
        ScreenCaptureSignal.publishCamera(pkg, match, now)
        if (NavLog.verbose) Log.i(TAG, "camera(image) pkg=$pkg score=${match.score} tmpl=${match.templateName}")
    }

    /** Lưu ảnh crop chẩn đoán (verbose-gated, off-thread đã sẵn) vào `getExternalFilesDir/diag`; tôn trọng cap. */
    private fun saveDiag(full: Bitmap, plan: CapturePlan, now: Long) {
        runCatching {
            val b = plan.bounds.clampTo(CropRect(0, 0, full.width, full.height))
            if (b.isEmpty()) return
            val crop = Bitmap.createBitmap(full, b.left, b.top, b.width, b.height)
            val dir = File(appContext.getExternalFilesDir(null), "diag").apply { mkdirs() }
            val f = File(dir, "capnav-${now}-${plan.case}-${plan.target}-${plan.boundsSource}.png")
            FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { Log.w(TAG, "diag save failed", it) }
        // Trim thư mục chẩn đoán về cap ~150MB (throttled, off-thread) — không để 1 chuyến verbose đầy bộ nhớ.
        runCatching { DiagStorageCap.enforce(appContext) }
    }

    companion object {
        private const val TAG = "ScreenCapNav"

        /** Nhịp chụp = 500ms (2 Hz) — trong ngân sách ≤2–4 Hz (R-nf3). Perf thật = OQ3 VERIFY-ON-CAR. */
        const val TICK_MS = 500L

        @Volatile private var instance: ScreenCaptureNavSource? = null

        fun get(context: Context): ScreenCaptureNavSource = instance ?: synchronized(this) {
            instance ?: ScreenCaptureNavSource(context.applicationContext).also { instance = it }
        }
    }
}
