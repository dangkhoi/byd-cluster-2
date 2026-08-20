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
import com.byd.clusternav.navigation.NavChannel
import com.byd.clusternav.navigation.PixelFrame
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.screencapture.CaptureBoundsSource
import com.byd.clusternav.navigation.screencapture.CaptureCase
import com.byd.clusternav.navigation.screencapture.CaptureForegroundSource
import com.byd.clusternav.navigation.screencapture.CaptureLocationResolver
import com.byd.clusternav.navigation.screencapture.CaptureRouter
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
    // BUILTIN rỗng (template thật OQ4 trên xe) ⇒ camera match luôn NONE ở production → an toàn, không false-positive.
    private val cameraMatcher = VietMapCameraMatcher(VietMapCameraMatcher.BUILTIN)

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

        val pkg = SourceArbiter.activeSource ?: CaptureForegroundSource.pkg ?: return

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

        // Geometry THẬT = kích thước ảnh chụp (đúng không gian bounds).
        val geom = DisplayGeometry(bmp.width, bmp.height)
        val plan = CaptureRouter.route(loc, geom, CaptureBoundsSource.snapshot(), now) ?: return
        if (plan.bounds.isEmpty()) return

        val cropped = cropToFrame(bmp, plan.bounds) ?: return
        when (plan.target) {
            CaptureTarget.ARROW -> handleArrow(pkg, cropped, now, nowWall)
            CaptureTarget.CAMERA -> handleCamera(pkg, cropped, now, nowWall)
        }

        if (NavLog.verbose) saveDiag(bmp, plan, now)
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

    private fun handleArrow(pkg: String, frame: PixelFrame, now: Long, nowWall: Long) {
        // TÁI DÙNG NGUYÊN ManeuverSignature (đây chính là processArrowPixels của OpenBYD).
        val maneuver = runCatching { ManeuverSignature.classifyManeuver(frame) }.getOrNull()
        val amap = runCatching { ManeuverSignature.classify(frame) }.getOrNull()
        if (maneuver == null && amap == null) return             // không đọc được mũi tên → bỏ frame
        // Trọng tài: ảnh là FALLBACK — data cùng app còn tươi thì bỏ (không chiếm khoá). data > image (R6).
        // shouldFeed đọc/ghi state WALL của arbiter (dùng chung WazeMod/NavNotif → currentTimeMillis) ⇒ nowWall.
        if (!SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) return
        ScreenCaptureSignal.publishArrow(pkg, maneuver, amap, now)   // publish = holder B3-local ⇒ monotonic now
        if (NavLog.verbose) Log.i(TAG, "arrow(image) pkg=$pkg maneuver=$maneuver amap=$amap")
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
