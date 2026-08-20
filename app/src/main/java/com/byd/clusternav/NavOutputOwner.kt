package com.byd.clusternav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.modules.hal.BydHal
import com.byd.clusternav.navigation.HudKeepAlivePolicy
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavOutputDecision
import com.byd.clusternav.navigation.screencapture.ScreenCaptureSignal
import com.byd.clusternav.navigation.screencapture.CameraMatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * B3 T4 (spec `docs/specs/b3-full-nav-capture.html` §R3/§R4/§R5) — OWNER "payload gốc" của nguồn dẫn đường ẢNH.
 *
 * Đọc [ScreenCaptureSignal] (mũi tên + làn + camera, đã QUA trọng tài `SourceArbiter` khi publish) và bắn ra
 * cụm-centre + HUD qua [BydHal]:
 *  - mũi tên tươi → [BydHal.pushNavigation] (icon guidance, domestic + oversea);
 *  - làn tươi     → [BydHal.pushLane] (mỗi làn: hướng + recommended sáng/mờ, R4);
 *  - camera tươi  → [BydHal.pushCamera] (icon + cự-ly, R5a).
 *
 * "CỨ BẮN" (R3a/OQ4): kênh nào tươi thì bắn kênh đó, KHÔNG gate theo HUD/kênh khác — HUD/cụm hỗ trợ thì lên,
 * không thì kệ (bắn thừa vô hại; cache reject của BydHal nuốt spam). Quyết định "bắn kênh nào / khi nào clear"
 * là thuần ([NavOutputDecision]) → test off-car; owner chỉ lo I/O + vòng đời + keep-alive.
 *
 * KEEP-ALIVE (giống [HudKeepAlivePolicy]): một scheduler daemon nhịp mỗi [intervalMs]
 * (mặc định [HudKeepAlivePolicy.DEFAULT_INTERVAL_MS] = 250ms) gọi [tick]; mỗi tick ĐỌC LẠI mức tươi SỐNG của
 * tín hiệu ⇒ còn tươi thì RE-ASSERT nội dung (OEM không blank), hết tươi (tất cả kênh stale, ngưỡng
 * `ScreenCaptureSignal.STALE_MS`) thì [BydHal.clearNavFrame] ĐÚNG một lần. Dùng ngưỡng-tươi 6s (KHÔNG phải trần
 * 180s của đường DATA) vì nguồn ẢNH tick 2Hz liên tục khi đang đọc — gap > 6s = capture đã dừng.
 *
 * RANH GIỚI SỞ HỮU (PhysicalHudOwnershipTest): owner này KHÔNG gọi đường `writeNavFrame` — session latch
 * (SEND_NAVI_STATUS / SET_NAVI_SCREEN_STATUS / SDK) vẫn là ĐỘC QUYỀN của [NavigationHudOwner]. Owner này chỉ ghi
 * CONTENT (mũi tên/cự-ly/làn/camera) qua các method push* THÊM (additive). Vì kênh ẢNH chỉ lên khi kênh DATA im
 * (SourceArbiter), hai owner KHÔNG cùng ghi một lúc.
 *
 * Đặt cùng package [NavigationHudOwner] (`com.byd.clusternav`) làm anh em owner — tránh tạo package
 * `com.byd.clusternav.navigation` phía :app (sẽ che khuất package :core cùng tên trong SourceRoots test helper).
 *
 * Degrade-safe: mọi I/O bọc runCatching; thiếu device/template/tín hiệu → bỏ frame, KHÔNG crash, KHÔNG bắn sai.
 *
 * ⚠ VÒNG ĐỜI DO ORCHESTRATOR (T6) NỐI — file này KHÔNG tự đăng ký với app lifecycle.
 * Test-friendly: [sink]/[clock] tiêm được (ctor internal); ctor công khai [NavOutputOwner] dựng đầu ra HAL thật.
 */
class NavOutputOwner internal constructor(
    private val sink: Sink,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val intervalMs: Long = HudKeepAlivePolicy.DEFAULT_INTERVAL_MS,
    private val log: (String) -> Unit = {},
) : AutoCloseable {

    /** Prod: bơm ra HAL cụm/HUD in-process qua [BydHal] (đường CONTENT push*, KHÔNG phải session-latch owner). */
    constructor(appContext: Context) : this(
        sink = HalSink(appContext.applicationContext),
        log = { msg -> Log.i(TAG, msg) },
    )

    /** Seam đầu ra — cho phép test inject fake, tách owner khỏi Android/HAL. */
    interface Sink {
        fun pushArrow(icon: Int, segMeters: Int)
        fun pushLane(info: LaneInfo)
        fun pushCamera(iconCode: Int, distanceMeters: Int)
        fun clear()
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nav-output-owner").apply { isDaemon = true } }
    private val lifecycleLock = Any()
    private var task: ScheduledFuture<*>? = null

    private val stateLock = Any()
    private var hasFrame = false
    private var active = false   // log-on-change: có đang hiện frame không (đỡ spam ~4/s)

    /**
     * T6 seam: orchestrator cập nhật OVERLAY cụm mỗi tick (SONG SONG đầu ra BydHal — usecase khác, không
     * fallback). Nhận (làn tươi, camera tươi, mũi tên tươi, có-frame). null = không có overlay. @Volatile vì
     * set từ luồng lifecycle, đọc từ luồng scheduler.
     */
    @Volatile var overlaySink: ((laneInfo: LaneInfo?, camera: CameraMatch?, arrow: Maneuver?, anyFresh: Boolean) -> Unit)? = null

    /** Bật keep-alive: nhịp [intervalMs] gọi [tick]. Idempotent. Vòng đời do T6 gọi. */
    fun start() {
        synchronized(lifecycleLock) {
            if (task == null) {
                task = scheduler.scheduleWithFixedDelay(
                    ::safeTick, intervalMs, intervalMs, TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    /** Dừng nhịp + nhả frame (clear) nếu đang hiện. */
    fun stop() {
        synchronized(lifecycleLock) { task?.cancel(false); task = null }
        issueClear()
    }

    private fun safeTick() {
        runCatching { tick(clock()) }.onFailure { log("tick failed: ${it.message}") }
    }

    /**
     * Một tick: đọc mức tươi SỐNG của [ScreenCaptureSignal] → [NavOutputDecision.decide] → bắn kênh tươi
     * (re-assert mỗi nhịp = keep-alive) hoặc clear ĐÚNG một lần khi tất cả kênh stale. Public để T6/test gọi
     * trực tiếp (deterministic). Mỗi kênh bọc runCatching riêng (một kênh lỗi không chặn kênh khác).
     */
    fun tick(now: Long) {
        val plan = NavOutputDecision.decide(
            arrowFresh = ScreenCaptureSignal.arrowFresh(now),
            laneFresh = ScreenCaptureSignal.laneFresh(now),
            cameraFresh = ScreenCaptureSignal.cameraFresh(now),
        )
        if (plan.anyPush) {
            var pushed = false
            if (plan.pushArrow) arrowIconOrNull()?.let { icon ->
                runCatching { sink.pushArrow(icon, ARROW_DISTANCE_UNKNOWN) }
                    .onSuccess { pushed = true }
                    .onFailure { log("arrow push failed: ${it.message}") }
            }
            if (plan.pushLane) ScreenCaptureSignal.laneInfo?.takeIf { !it.isEmpty() }?.let { info ->
                runCatching { sink.pushLane(info) }
                    .onSuccess { pushed = true }
                    .onFailure { log("lane push failed: ${it.message}") }
            }
            if (plan.pushCamera) ScreenCaptureSignal.cameraMatch?.let { match ->
                runCatching {
                    sink.pushCamera(NavOutputDecision.cameraIconCode(match.hasCamera), match.distanceMeters ?: ARROW_DISTANCE_UNKNOWN)
                }.onSuccess { pushed = true }.onFailure { log("camera push failed: ${it.message}") }
            }
            if (pushed) {
                synchronized(stateLock) {
                    hasFrame = true
                }
            }
            // Log-on-change theo QUYẾT ĐỊNH (anyPush), KHÔNG theo sink-success: trên emulator/off-car HAL null
            // → sink no-op (pushed=false) nhưng ta VẪN thấy owner đã tiêu thụ signal + quyết định bắn. sink=
            // pushed cho biết HAL có nhận không (true trên xe, false off-car).
            synchronized(stateLock) {
                if (!active) { active = true; log("nav output ACTIVE decided(arrow=${plan.pushArrow} lane=${plan.pushLane} camera=${plan.pushCamera}) sink=$pushed") }
            }
        } else if (plan.clear) {
            issueClear()
        }
        // T6: cập nhật overlay cụm (song song BydHal). Truyền giá trị TƯƠI của mỗi kênh; anyFresh=plan.anyPush.
        overlaySink?.let { s ->
            val lane = ScreenCaptureSignal.laneInfo?.takeIf { ScreenCaptureSignal.laneFresh(now) && !it.isEmpty() }
            val cam = ScreenCaptureSignal.cameraMatch?.takeIf { ScreenCaptureSignal.cameraFresh(now) }
            val arr = if (ScreenCaptureSignal.arrowFresh(now)) {
                ScreenCaptureSignal.arrowManeuver ?: ScreenCaptureSignal.arrowAmap?.let { Maneuver.fromAmapIcon(it) }
            } else null
            runCatching { s(lane, cam, arr, plan.anyPush) }.onFailure { log("overlay sink failed: ${it.message}") }
        }
    }

    /** Convenience: tick theo đồng hồ hiện tại (T6 có thể gọi mỗi lần nguồn publish tín hiệu mới). */
    fun publish() = tick(clock())

    /** Nhả frame (clear) ĐÚNG một lần — no-op nếu chưa hiện gì (chống clear lặp mỗi tick stale). */
    private fun issueClear() {
        val was = synchronized(stateLock) {
            val w = hasFrame
            hasFrame = false
            if (active) { active = false; log("nav output CLEAR (all channels stale)") }
            w
        }
        if (was) runCatching { sink.clear() }.onFailure { log("clear failed: ${it.message}") }
    }

    /**
     * Icon guidance CAN (toHudIcon) cho mũi tên hiện tại: ưu tiên [ScreenCaptureSignal.arrowManeuver], nếu null
     * thì suy từ [ScreenCaptureSignal.arrowAmap] qua [Maneuver.fromAmapIcon]. null ⇒ không có hướng hợp lệ →
     * BỎ bắn mũi tên (degrade-safe: không bắn icon rác/rẽ giả — hợp với prereq B3.11).
     */
    private fun arrowIconOrNull(): Int? {
        ScreenCaptureSignal.arrowManeuver?.let { return it.toHudIcon() }
        ScreenCaptureSignal.arrowAmap?.let { amap -> Maneuver.fromAmapIcon(amap)?.let { return it.toHudIcon() } }
        return null
    }

    override fun close() {
        synchronized(lifecycleLock) { task?.cancel(false); task = null }
        scheduler.shutdownNow()
    }

    /**
     * Đầu ra thật ra HAL cụm/HUD (in-process reflection). Resolve InstrumentDevice MỘT lần (getInstance là
     * singleton, handle ổn định theo process) rồi tái dùng. Mọi call degrade-safe (runCatching) — off-car
     * device null → no-op im lặng.
     */
    private class HalSink(private val app: Context) : Sink {
        @Volatile private var instrument: Any? = null
        private fun instr(): Any? {
            instrument?.let { return it }
            val d = runCatching { BydHal.device(BydHal.INSTRUMENT, BydHal.systemBypassContext(), BydHal.bypass(app)) }.getOrNull()
            instrument = d
            return d
        }
        override fun pushArrow(icon: Int, segMeters: Int) { instr()?.let { runCatching { BydHal.pushNavigation(it, icon, segMeters) } } }
        override fun pushLane(info: LaneInfo) { instr()?.let { runCatching { BydHal.pushLane(it, info) } } }
        override fun pushCamera(iconCode: Int, distanceMeters: Int) { instr()?.let { runCatching { BydHal.pushCamera(it, iconCode, distanceMeters) } } }
        override fun clear() { runCatching { BydHal.clearNavFrame(app) } }
    }

    companion object {
        private const val TAG = "NavOutputOwner"
        /** Nguồn ẢNH không mang cự-ly (chỉ mũi tên/làn/camera); -1 ⇒ BydHal bỏ ghi ô cự-ly (không xoá trắng). */
        private const val ARROW_DISTANCE_UNKNOWN = -1
    }
}
