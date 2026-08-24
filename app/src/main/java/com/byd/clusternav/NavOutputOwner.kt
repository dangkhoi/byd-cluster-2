package com.byd.clusternav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.modules.hal.BydHal
import com.byd.clusternav.navigation.NavViewIdSource
import com.byd.clusternav.navigation.HudKeepAlivePolicy
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavOutputDecision
import com.byd.clusternav.navigation.NavOutputPlan
import com.byd.clusternav.navigation.TurnDistancePlausibility
import com.byd.clusternav.navigation.stateAt
import com.byd.clusternav.navigation.screencapture.ArrowSample
import com.byd.clusternav.navigation.screencapture.ScreenCaptureSignal
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
 * §R-BI — BẤT BIẾN MỘT-PACKAGE-MỘT-KHUNG: ba kênh publish ĐỘC LẬP và mỗi kênh tự tươi 6s, nên khi đổi app dẫn
 * (Waze → VietMap) kênh của app cũ còn tươi tới 6 giây ⇒ nếu không so danh tính thì dải làn của một ngã ba KHÁC
 * nằm ngay cạnh mũi tên đang báo. Một tick chỉ bắn các kênh CÙNG một package ([NavOutputDecision.decide] chọn
 * danh tính theo thứ tự ARROW > LANE > CAMERA); kênh tươi khác package bị DROP im lặng (KHÔNG kéo theo clear —
 * khung đang hiện không được nhấp nháy chỉ vì xuất hiện một kênh lạ).
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
 * CONTENT (mũi tên/cự-ly/làn/camera) qua các method push* THÊM (additive).
 *
 * ◐ "hai owner KHÔNG cùng ghi một lúc" — **ĐÓNG MỘT PHẦN 2026-08-23** (backlog **B3.48**). Cảnh báo cũ (08-23
 * vòng 2) đúng vào lúc viết: cổng DATA↔IMAGE của [SourceArbiter] là **theo package** (`isDataFresh(pkg)`),
 * không phải toàn cục, và ở `PREFER_*` thì `allowedByMode` khi đó còn cửa thoát `|| !isGroupFresh(X)` — nhóm
 * ưu tiên im quá `STALE_MS` là nó mở cho MỌI package khác (vd `PREFER_VIETMAP` mà VietMap không đập nhịp ⇒
 * GMaps qua cổng DATA và Waze qua cổng IMAGE cùng lúc). Khi đó `writeNavFrame` (BydHal.kt §writeNavFrame,
 * [NavigationHudOwner]) và `pushNavigation` (§pushNavigation, owner này) ghi XEN KẼ cùng một register
 * `INSTRUMENT_GUIDE_INFO_SIMPLE_SET` ⇒ cự ly của app này cạnh mũi tên của app kia. Bất biến
 * MỘT-PACKAGE-MỘT-KHUNG mà [NavOutputDecision.decide] đóng chỉ phủ BÊN TRONG đường ẢNH, KHÔNG phủ ranh giới
 * giữa hai owner — nên nó không cứu được ca này.
 *
 * ĐÓNG ĐƯỢC ĐÚNG PHẦN NÀO: cửa thoát đó đã bị GỠ khỏi `SourceArbiter.allowedByMode` (cùng sổ `lastSeenByPkg`
 * + `noteSeen` + `isGroupFresh`). Ở `PREFER_X` cổng nay CHỈ là `pkg in X`. Bất biến **CHỨNG MINH ĐƯỢC**, không
 * hơn: *ở `PREFER_*`, hai package thuộc HAI NHÓM KHÁC NHAU không bao giờ cùng qua cổng* — tức ca "cự ly
 * VietMap cạnh mũi tên GMaps" ở trên đã hết đường. Khoá bằng `SourceArbiterAllowsTest` (nhóm B3.48). Quyết
 * định owner 2026-08-23: app đích danh không dẫn thì **không hiện gì** (cụm im lặng) — đó là ý owner, KHÔNG
 * phải suy giảm cần bù, nên đừng thêm fallback/timeout/degrade vào đây.
 *
 * ⚠ CÒN HỞ, ĐỪNG ĐỌC NHẦM THÀNH "ĐÃ ĐÓNG HẲN" — "một NHÓM" ≠ "một PACKAGE". `NavApps.GMAPS` có **2** gói
 * (zin + ReVanced) và `NavApps.WAZE` có **2** gói (`com.waze` + WazeMod — cấu hình CÓ THẬT của owner, xem
 * `NavAccessibilityService` "cài cả Waze zin lẫn WazeMod"). Ở `PREFER_GMAPS`/`PREFER_WAZE`, CẢ HAI thành viên
 * qua cổng cùng lúc, và `isDataFresh` là **theo package** nên mốc DATA của gói A không chặn kênh IMAGE của
 * gói B ⇒ về lý thuyết `writeNavFrame`(A) và `pushNavigation`(B) vẫn ghi xen kẽ được. Đây là hiện trạng CÓ
 * TỪ TRƯỚC B3.48 (cổng `PREFER_*` chưa bao giờ có khoá-giữ), không phải hồi quy của nó; `NavSourceDwell` chỉ
 * che đường enum a11y, KHÔNG che `NavNotificationListener` và 4 lối `ScreenCaptureNavSource`. Cần hạng mục
 * backlog riêng — đừng vá ở đây.
 *
 * ⚠ Ở **AUTO** ranh giới do khoá-giữ `activeSource` (hạn `SourceArbiter.STALE_MS` = 6 s) — nhánh đó KHÔNG
 * đổi. Nhưng cũng đừng đọc "khoá 6 s" thành "bảo đảm": `NavigationHudOwner` giữ khung tới TRẦN TUỔI 180 s
 * (`HudKeepAlivePolicy.DEFAULT_MAX_AGE_MS`), tức khoá nguồn hết hạn trước khung cũ rất lâu.
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
    /**
     * B-III: tốc độ THẬT cho luật đóng-băng của [guard]. `null` = KHÔNG đọc được (HAL câm / off-car) ⇒ luật đó
     * tắt hẳn, không đoán bừa. Bọc runCatching vì đường này là reflection xuống HAL: một lần ném không được
     * phép làm sập cả tick.
     */
    private val speed: () -> Double? = { runCatching { SpeedProvider.mpsOrNull() }.getOrNull() },
    /** B-III: guard hợp lý hoá cự-ly view-id. Một instance/owner ⇒ owner mới (rebind listener) = warmup lại. */
    private val guard: TurnDistancePlausibility = TurnDistancePlausibility(),
    /**
     * BẬT **BỀ MẶT** nav của cụm — clusterDebug op 39 "simple navigation"
     * ([com.byd.clusternav.modules.clustercast.ClusterNavLaneWidget.onNavActive]). Mặc định no-op để test
     * off-car không chạm shell.
     *
     * ── VÌ SAO CÓ SEAM NÀY (lỗi THẬT, sửa 08-23 vòng 3 — [P1]) ────────────────────────────────────────
     * BỀ MẶT và NỘI DUNG là hai thứ khác nhau, và owner này trước đây chỉ bắn NỘI DUNG. Bằng chứng op-39 là
     * thứ dựng BỀ MẶT: on-car 2026-08-12 (KDoc [com.byd.clusternav.modules.clustercast.ClusterNavLaneWidget])
     * — "broadcasting AUTONAVI nav frames alone did NOT surface the nav overlay", phải `service call
     * AutoContainer 2 i32 1000 i32 39` thì lớp nav OEM mới hiện ra giữa cụm.
     *
     * HỒI QUY ĐÃ XẢY RA (mức "đã chứng minh", đọc từ git + grep — CLAUDE.md §2): `onNavActive` toàn repo có
     * ĐÚNG MỘT call site, `NavNotificationListener` (:app). Ở HEAD `MAPS_PACKAGES` còn liệt kê
     * `vn.vietmap.live` nên notification VietMap đi vào `handle()` và dựng bề mặt hộ đường ảnh. VIỆC B thu
     * `MAPS_PACKAGES` về `NavApps.NOTIFICATION` = chỉ GMaps ⇒ **VietMap mất luôn lệnh dựng bề mặt**, trong
     * khi mũi tên của nó lại vừa chuyển hẳn sang kênh ảnh (owner chốt B3.42). Kết quả: đúng cái app mà
     * VIỆC B sinh ra để phục vụ là app duy nhất không còn ai bật lớp hiển thị cho nó.
     *
     * ĐÂY LÀ KHÔI PHỤC NGANG BẰNG, KHÔNG PHẢI NĂNG LỰC MỚI (nên không cần tầng-1 §14): cùng một lệnh đã
     * proven on-car, cùng cổng "chỉ nav-only, Cast ON thì nhường" của widget, cùng debounce
     * (`REASSERT_MS` 30 s sau khi thành công / `RETRY_AFTER_FAIL_MS` 4 s khi shell hỏng) — nên tick 4 Hz ở
     * đây KHÔNG đẻ thêm một lệnh shell nào so với nhịp 1 Hz của notification: cả hai đều bị chính hai cổng
     * thời gian đó chặn. Widget tự khai "safe to call at notification rate and from any thread".
     *
     * CHỈ ASSERT, KHÔNG BAO GIỜ IDLE (cố ý): `onNavIdle()` reset `lastOkAtMs` ⇒ nếu owner này gọi idle mỗi
     * lần kênh ảnh hụt 6 s trong lúc GMaps vẫn đang dẫn qua notification, mỗi lần hụt sẽ **xoá debounce 30 s**
     * và ép re-issue op-39 ngay nhịp sau ⇒ hai đường đánh nhau, dadb bị gọi ở nhịp giây. Nhả bề mặt vẫn do
     * ranh giới PHIÊN của listener lo (gỡ noti / tới nơi / rớt binding / stop).
     *
     * GIỚI HẠN ĐÃ BIẾT (nói rõ để không ai đọc nhầm thành bug): một phiên CHỈ-VietMap không có notification
     * nào để gỡ, nên `ClusterNavLaneWidget.status` ở lại `ASSERTED` tới lúc rớt binding / `stop()`. Đó là
     * DÒNG CHẨN ĐOÁN trên thẻ Nav, không phải một lệnh đang giữ: op-39 idempotent và lớp nav OEM sống bằng
     * khung được bơm liên tục, hết khung thì nó tự rút.
     */
    private val assertNavSurface: () -> Unit = {},
) : AutoCloseable {

    /** Prod: bơm ra HAL cụm/HUD in-process qua [BydHal] (đường CONTENT push*, KHÔNG phải session-latch owner). */
    constructor(appContext: Context) : this(
        sink = HalSink(appContext.applicationContext),
        log = { msg -> Log.i(TAG, msg) },
        assertNavSurface = {
            com.byd.clusternav.modules.clustercast.ClusterNavLaneWidget
                .onNavActive(appContext.applicationContext)
        },
    )

    /** Seam đầu ra — cho phép test inject fake, tách owner khỏi Android/HAL. */
    interface Sink {
        fun pushArrow(icon: Int, segMeters: Int)
        fun pushLane(info: LaneInfo)
        fun pushCamera(iconCode: Int, distanceMeters: Int)

        /**
         * B-III: XOÁ TRẮNG ô cự-ly (giữ mũi tên). KHÔNG có default body — cố ý: một `= Unit` mặc định là đúng
         * cái bẫy CLAUDE.md §8 (quên override mà build vẫn xanh, guard mất tác dụng im lặng trên xe).
         */
        fun blankDistance()
        fun clear()
    }

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "nav-output-owner").apply { isDaemon = true } }
    private val lifecycleLock = Any()
    private var task: ScheduledFuture<*>? = null

    private val stateLock = Any()
    private var hasFrame = false
    private var active = false   // log-on-change: có đang hiện frame không (đỡ spam ~4/s)
    private var framePkg: String? = null    // §R-BI: danh tính khung đang bắn (log-on-change)
    private var lastDropSig: String? = null // §R-BI: chữ ký ca DROP gần nhất (log-on-change, không spam 4Hz)

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
        // B3.49 vòng 2 — NHẢ KHUNG khi chính app ĐANG hiện vừa bị cổng nguồn loại (người dùng đổi menu).
        //
        // VÌ SAO KHÔNG ĐỦ nếu chỉ gỡ sample ở `ScreenCaptureSignal.dropDisallowed`: quyết định nhả khung là
        // `clear = !anyFresh` (NavOutputDecision) — TẤT CẢ ba kênh phải hết tươi. Một app KHÁC còn giữ một
        // kênh tươi (VietMap publish làn mỗi ~500 ms) ⇒ khung sống tiếp, mà `pushLane`/`pushCamera` KHÔNG ghi
        // `INSTRUMENT_GUIDE_INFO_SIMPLE_SET` ⇒ **mũi tên của app vừa bị loại nằm lại trên cụm vô hạn**.
        // [ĐO] 2026-08-23 (`PROBE-A`): arrow=Waze + lane=VietMap ⇒ đổi sang PREFER_VIETMAP ⇒ `sink.clear()`
        // không hề được gọi. Chỉ `clearNavFrame` (sink.clear) mới ghi SIMPLE_SET = 0 — đường ĐÃ proven on-car.
        //
        // CHỈ NHẢ ĐÚNG KHUNG CỦA GÓI BỊ LOẠI: so với [framePkg] (danh tính đang bắn). Nhả vô điều kiện là làm
        // chính app vừa được chọn chớp tắt một nhịp — "hiện SAI", đúng thứ B3.49 cấm.
        // NHẢ RỒI DỪNG NHỊP NÀY: nhịp kế (250 ms) dựng lại khung từ các kênh còn được phép. Cố ý KHÔNG bắn
        // tiếp ngay sau `clear` trong cùng một nhịp — `clearNavFrame` ghi cả `SEND_NAVI_STATUS = 4`, và thứ
        // tự clear→push trong cùng tick chưa từng chạy trên xe (CLAUDE.md §6: đường mới xuống cuối).
        val released = ScreenCaptureSignal.consumeFrameRelease()
        if (released.isNotEmpty() && synchronized(stateLock) { framePkg } in released) {
            log("nav output RELEASE (nguon vua bi loai: ${released.joinToString(",")})")
            issueClear()
            return
        }
        // §R-BI — ĐỌC MỘT LẦN MỖI KÊNH: mỗi sample là object bất biến (pkg + giá trị + mốc đi cùng nhau), nên
        // luồng capture publish xen giữa hai lần đọc KHÔNG thể ghép pkg app mới với giá trị app cũ. Mọi giá trị
        // đẩy đi bên dưới lấy TỪ CHÍNH ba local này, không đọc lại global.
        val arrowS = ScreenCaptureSignal.arrow
        val laneS = ScreenCaptureSignal.lane
        val cameraS = ScreenCaptureSignal.camera
        val stale = ScreenCaptureSignal.STALE_MS
        val plan = NavOutputDecision.decide(
            arrow = arrowS.stateAt(now, stale),
            lane = laneS.stateAt(now, stale),
            camera = cameraS.stateAt(now, stale),
        )
        noteIdentity(plan, arrowS?.pkg, laneS?.pkg, cameraS?.pkg)
        if (plan.anyPush) {
            // BỀ MẶT TRƯỚC NỘI DUNG (08-23 vòng 3 — [P1], xem KDoc [assertNavSurface]): op 39 dựng lớp nav OEM
            // giữa cụm; không có nó thì mọi push* dưới đây ghi vào một bề mặt chưa được bật.
            // Gác theo `plan.anyPush` (QUYẾT ĐỊNH) chứ không theo `pushed` (sink-success) — cùng lý do đã ghi ở
            // log-on-change bên dưới: off-car HAL null ⇒ sink no-op, nhưng quyết định "đang dẫn" vẫn đúng.
            // runCatching: một lần ném của đường shell/prefs không được phép nuốt cả tick nội dung.
            runCatching { assertNavSurface() }.onFailure { log("nav surface assert failed: ${it.message}") }
            var pushed = false
            if (plan.pushArrow) arrowIconOrNull(arrowS)?.let { icon ->
                // 08-22: cự ly THẬT nếu đọc được bằng view-id a11y (clone OpenBYD — Waze phơi
                // `com.waze:id/navBarDistance`). Trước đây luôn -1 ⇒ HUD bỏ trống ô cự ly, chỉ có mũi tên trần.
                // §R-BI: cự-ly đi theo DANH TÍNH KHUNG (plan.framePkg — MỘT nguồn sự thật, thay cho guard lẻ
                // đọc arrowPkg), lệch/thiếu ⇒ -1 ⇒ BydHal.kt BỎ GHI ô cự-ly (không bao giờ ghi số của app khác
                // cạnh mũi tên app này).
                // Chấm tươi bằng `now` CỦA TICK, không phải clock(): trộn hai miền đồng hồ trong cùng một
                // quyết định là bug (off-car test tiêm clock={0L} phơi ra ngay).
                // B-III: mẫu view-id còn phải qua [TurnDistancePlausibility] mới được lái ô cự-ly; và vì "bỏ ghi"
                // = GIỮ SỐ CŨ, chính hàm dưới lo phát lệnh XOÁ TRẮNG ở cạnh xuống (xem plausibleSegOrUnknown).
                val seg = plausibleSegOrUnknown(plan.framePkg, now)
                if (NavLog.verbose) log("pushArrow icon=$icon seg=${if (seg >= 0) "${seg}m" else "—"}")
                runCatching { sink.pushArrow(icon, seg) }
                    .onSuccess { pushed = true }
                    .onFailure { log("arrow push failed: ${it.message}") }
            }
            if (plan.pushLane) laneS?.info?.takeIf { !it.isEmpty() }?.let { info ->
                runCatching { sink.pushLane(info) }
                    .onSuccess { pushed = true }
                    .onFailure { log("lane push failed: ${it.message}") }
            }
            if (plan.pushCamera) cameraS?.match?.let { match ->
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
                if (!active) { active = true; log("nav output ACTIVE khung=${plan.framePkg} decided(arrow=${plan.pushArrow} lane=${plan.pushLane} camera=${plan.pushCamera}) sink=$pushed") }
            }
        } else if (plan.clear) {
            issueClear()
        }
    }

    /** Convenience: tick theo đồng hồ hiện tại (T6 có thể gọi mỗi lần nguồn publish tín hiệu mới). */
    fun publish() = tick(clock())

    /** Nhả frame (clear) ĐÚNG một lần — no-op nếu chưa hiện gì (chống clear lặp mỗi tick stale). */
    private fun issueClear() {
        val was = synchronized(stateLock) {
            val w = hasFrame
            hasFrame = false
            framePkg = null
            lastDropSig = null
            if (active) { active = false; log("nav output CLEAR (all channels stale)") }
            w
        }
        // B-III: nhả frame = hết phiên ⇒ phiên sau phải chứng minh lại từ đầu. reset() cũng đặt displaying=false
        // nên sẽ KHÔNG có lệnh blank lạc lõng bắn ra sau khi frame đã được clear.
        guard.reset()
        if (was) runCatching { sink.clear() }.onFailure { log("clear failed: ${it.message}") }
    }

    /**
     * §R-BI — hai dòng log CHẨN ĐOÁN TRÊN XE (CLAUDE.md §11/§15), log-on-change để không spam 4Hz:
     *  • `IDENTITY <cũ> → <mới>` — khung đổi chủ (Waze → VietMap…).
     *  • `DROP …` — có kênh TƯƠI bị loại vì lệch danh tính, in ĐỦ 3 pkg để grep một phát ra bệnh (R4: nếu một
     *    producer lỡ dán nhãn bằng tiền tố resource `com.waze` thay vì package runtime `com.chisadin.wazemod`
     *    thì mọi kênh của WazeMod bị DROP im lặng — không có dòng này thì hiện tượng là "mất làn, không lỗi").
     */
    private fun noteIdentity(plan: NavOutputPlan, arrowPkg: String?, lanePkg: String?, cameraPkg: String?) {
        synchronized(stateLock) {
            if (plan.framePkg != framePkg) {
                log("nav output IDENTITY ${framePkg ?: "—"} → ${plan.framePkg ?: "—"}")
                framePkg = plan.framePkg
            }
            val sig = if (plan.anyDropped) "${plan.droppedArrow}/${plan.droppedLane}/${plan.droppedCamera}" else null
            if (sig != lastDropSig) {
                lastDropSig = sig
                if (sig != null) {
                    log(
                        "nav output DROP arrow=${plan.droppedArrow} lane=${plan.droppedLane} " +
                            "camera=${plan.droppedCamera} (khung=${plan.framePkg} arrowPkg=$arrowPkg " +
                            "lanePkg=$lanePkg camPkg=$cameraPkg)",
                    )
                }
            }
        }
    }

    /**
     * Icon guidance CAN (toHudIcon) cho mũi tên của CHÍNH sample đang xét: ưu tiên [ArrowSample.maneuver], nếu
     * null thì suy từ [ArrowSample.amap] qua [Maneuver.fromAmapIcon]. null ⇒ không có hướng hợp lệ →
     * BỎ bắn mũi tên (degrade-safe: không bắn icon rác/rẽ giả — hợp với prereq B3.11).
     *
     * Nhận sample thay vì đọc lại global: đọc lại là mở đúng cái khe đọc-xé mà §R-BI vừa đóng.
     */
    private fun arrowIconOrNull(sample: ArrowSample?): Int? {
        sample ?: return null
        sample.maneuver?.let { return it.toHudIcon() }
        sample.amap?.let { amap -> Maneuver.fromAmapIcon(amap)?.let { return it.toHudIcon() } }
        return null
    }

    /**
     * B-III — cự ly view-id CHỈ được lái ô cự-ly khi qua [TurnDistancePlausibility].
     *
     * ⚠ Guard này KHÔNG phân biệt được "đúng app, sai tuyến" (không field nào của [NavViewIdSource] mang danh
     * tính tuyến) — nó chỉ chặn chuỗi mẫu TỰ MÂU THUẪN, tức bắt lúc CHUYỂN TIẾP. Xem KDoc lớp guard.
     *
     * Danh tính lấy từ [framePkg] (= `plan.framePkg`, MỘT nguồn sự thật của §R-BI), KHÔNG đọc lại
     * `ScreenCaptureSignal.arrowPkg` — đọc lại là mở đúng cái khe đọc-xé mà B-I vừa đóng.
     *
     * Tốc độ đi qua [speed] (mặc định [SpeedProvider.mpsOrNull]) — TUYỆT ĐỐI không dùng biến thể last-good
     * `mps()` của [SpeedProvider]: nó trả 0.0 khi HAL câm (SpeedProvider.kt:26-35) ⇒ guard tưởng xe đang ĐỖ và
     * luật đóng-băng câm lặng mãi mãi.
     *
     * GIỚI HẠN đã biết: hàm này chỉ chạy trong nhánh mũi tên. Khi mũi tên stale mà làn/camera còn tươi thì tick
     * không đi qua đây ⇒ không có cơ hội blank, số cũ nằm lại tới lúc clear cả frame. Chấp nhận ở vòng này.
     */
    private fun plausibleSegOrUnknown(framePkg: String?, now: Long): Int {
        val r = framePkg?.let { NavViewIdSource.freshReadingFor(it, now) }
        // `speed` truyền dạng LAMBDA (không phải `speed()`): tham số được đánh giá TRƯỚC khi vào hàm, mà hàm
        // này chạy 4 Hz trong khi a11y chỉ publish ~1,25 Hz ⇒ phần lớn tick dừng ở bước chống-đọc-lặp
        // (REPEAT) và không cần tốc độ. Truyền giá trị là đốt ~3 lời gọi reflection HAL/giây vô ích cả chuyến.
        val d = if (r == null) guard.noSample()
        else guard.accept(r.pkg, r.turnMeters, r.road, r.atMs, speed)
        if (d.blankDistance) {
            runCatching { sink.blankDistance() }.onFailure { log("blank distance failed: ${it.message}") }
        }
        // Dòng log để ĐO tần suất thật trên xe trước khi cân nhắc nới ngưỡng (OQ14) — bỏ REPEAT để không spam
        // 4Hz. [TurnDistancePlausibility.debugState] in state nội bộ: chỉ để đọc, không quyết định gì.
        if (NavLog.verbose && d.verdict != TurnDistancePlausibility.Verdict.REPEAT) {
            log(
                "seg guard ${d.verdict} raw=${r?.turnMeters ?: -1} out=${d.meters} " +
                    "road='${r?.road.orEmpty()}' [${guard.debugState()}]",
            )
        }
        return d.meters   // TurnDistancePlausibility.UNKNOWN == -1 == ARROW_DISTANCE_UNKNOWN
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
        override fun blankDistance() { instr()?.let { runCatching { BydHal.blankNavDistance(it) } } }
        override fun clear() { runCatching { BydHal.clearNavFrame(app) } }
    }

    companion object {
        private const val TAG = "NavOutputOwner"
        /** Nguồn ẢNH không mang cự-ly (chỉ mũi tên/làn/camera); -1 ⇒ BydHal bỏ ghi ô cự-ly (không xoá trắng). */
        private const val ARROW_DISTANCE_UNKNOWN = -1
    }
}
