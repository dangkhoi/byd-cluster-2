package com.byd.clusternav.modules.navaccess

import com.byd.clusternav.navigation.ScreenTextItem
import com.byd.clusternav.navigation.NavScreenReading
import com.byd.clusternav.navigation.NavScreenScan
import com.byd.clusternav.navigation.NavApps
import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.NavViewIdSource
import com.byd.clusternav.navigation.VietMapDescParser
import com.byd.clusternav.navigation.NavAccessHint
import com.byd.clusternav.navigation.NavAccessRow
import com.byd.clusternav.navigation.NavDescJoin
import com.byd.clusternav.navigation.NavSourceDwell
import com.byd.clusternav.navigation.TurnDistanceInterpolator
import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.byd.clusternav.NavAccessLog
import com.byd.clusternav.NavLog
import com.byd.clusternav.Prefs
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.screencapture.CaptureBoundsHeuristic
import com.byd.clusternav.navigation.screencapture.CaptureBoundsSource
import com.byd.clusternav.navigation.screencapture.CaptureForegroundSource
import com.byd.clusternav.navigation.screencapture.LaneBoundsSource
import com.byd.clusternav.navigation.screencapture.CaptureTarget
import com.byd.clusternav.navigation.screencapture.CropRect
import com.byd.clusternav.navigation.screencapture.NavWindowPicker
import com.byd.clusternav.navigation.screencapture.NavWindowPump
import android.os.Build
import android.util.SparseArray
import android.view.accessibility.AccessibilityWindowInfo
import com.byd.clusternav.navigation.screencapture.ForegroundWindowFilter
import com.byd.clusternav.modules.voicekey.AssistantLauncher
import com.byd.clusternav.modules.voicekey.VoiceKeyLearnBus
import com.byd.clusternav.voicekey.VoiceKeyAction
import com.byd.clusternav.voicekey.VoiceKeyConfig
import com.byd.clusternav.voicekey.VoiceKeyMatcher

/**
 * BOOSTER TẦNG 1 — đọc UI dẫn đường GMaps ĐANG HIỆN trên màn để lấy cự ly tới rẽ CHÍNH XÁC, TƯƠI hơn noti
 * (noti bước ~10m, trễ 1-2s), rồi TINH CHỈNH interpolator. GMaps KHÔNG có view-id sạch (xem OpenBYD
 * handleGoogleMapsEvent) -> phải dò theo MẪU CHỮ (cự ly m/km) + TOẠ ĐỘ (thẻ rẽ ở NỬA TRÊN màn, khác
 * thanh đáy = quãng tới đích). Chỉ là booster: KHÔNG tự khởi tạo nav (refine bỏ qua khi chưa có anchor noti),
 * GMaps bị YouTube che -> không có event -> tự câm, nội suy theo tốc độ gánh tiếp. KHÔNG root, chỉ xin quyền hỗ trợ.
 *
 * KEEP/KILL: xoá module = xoá modules/navaccess/ + dòng Registry + <service> trong Manifest + res/xml/nav_accessibility_config.xml.
 */
class NavAccessibilityService : AccessibilityService() {

    private var lastProcessed = 0L
    /** Chỉ GMaps: nhánh quét cự-ly-trên-màn (ground truth) chỉ áp dụng cho GMaps — xem :336. */
    private val maps = NavApps.GMAPS

    // All nav sources we source-tag for capture. GMaps also drives the on-screen distance ground-truth scan
    // below and carries its guidance in event.text. VietMap / Waze / WazeMod post no nav-GUIDANCE notification
    // we can parse (NavNotificationListener nhận cả NavApps.ALL, nhưng khung dùng được thì chỉ GMaps sinh ra),
    // so their same-device nav signal is the accessibility text captured (source-tagged) in [logEventText]. When
    // event.text is EMPTY — VietMap/Waze put the turn dist+road, current speed+limit and ETA+dist+dest on each
    // view's CONTENT DESCRIPTION, not text — the fallback subtree walk in [logEventText] reads those content
    // descriptions instead. packageNames in nav_accessibility_config.xml must list all of these for delivery.
    private val navPackages = NavApps.ALL

    // Per-package last logged voice-guidance text — collapses the window-content redraw flood into distinct
    // rows. Touched only on the accessibility (main) callback thread → no lock needed. Bounded (≤ navPackages).
    private val lastText = HashMap<String, String>(8)

    // Per-package last content-desc SUBTREE walk time (SystemClock.elapsedRealtime, ms). VietMap/Waze fire
    // TYPE_WINDOW_CONTENT_CHANGED densely; the empty-event.text fallback walk in [logEventText] is throttled per
    // package so we don't re-walk the tree on every event. Touched only on the a11y (main) callback thread → no
    // lock. Bounded (≤ navPackages). The event.text fast-path is NOT throttled, so GMaps stays unaffected.
    private val lastDescWalkAt = HashMap<String, Long>(8)

    // B3 (screen-capture nav): per-package throttle for the arrow/camera bounds subtree walk. VietMap/Waze fire
    // TYPE_WINDOW_CONTENT_CHANGED densely; the walk is bounded + throttled so it never stalls the main thread.
    private val lastBoundsWalkAt = HashMap<String, Long>(8)

    // B3.13: throttle window enumeration (flagRetrieveInteractiveWindows có cost) — chỉ enum tối đa ~1/s.
    private val WINDOW_ENUM_THROTTLE_MS = 800L

    // B3.13r: NHỊP ĐỊNH KỲ + throttle DÙNG CHUNG cho vòng enum. Trước bản này vòng enum chỉ chạy trong
    // onAccessibilityEvent. Bệnh KHÔNG phải "hết tươi 3 s là cụm trống" (bản vòng 1 viết vậy là sai — nhịp
    // chụp tự nuôi qua SourceArbiter.shouldFeed(…, IMAGE)); bệnh là VÒNG TRÒN: kênh ảnh im > STALE_MS thì
    // cổng của ScreenCaptureNavSource.tick đóng, mà thứ duy nhất mở lại cổng đó lại nằm SAU nó. Lý lẽ đầy đủ
    // + chi phí thật + hai giới hạn đã biết: KDoc [NavWindowPump]. Ở đây chỉ có Handler + cổng Prefs.
    private val navWindowPump = NavWindowPump(periodMs = WINDOW_ENUM_THROTTLE_MS)

    /**
     * Vòng enum GẦN NHẤT có thấy cửa sổ nav nào không. `null` = lượt đó KHÔNG chạy (bị throttle chung nuốt vì
     * đường event vừa enum xen vào) ⇒ CHƯA có quan sát mới, không được tính là "vắng" — xem
     * [NavWindowPump.onTickDone]. Chỉ chạm trên luồng callback a11y (main) → không cần khoá.
     */
    private var lastEnumSawNavWindow: Boolean? = null

    /** Hàng đợi của nhịp enum. Main looper: vòng enum đọc `windows` nên phải ở đúng luồng callback a11y. */
    private val pumpHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * MỘT thể hiện duy nhất, giữ ở `val` — `Handler.removeCallbacks` so sánh THAM CHIẾU, dựng lambda mới mỗi
     * lần post là gỡ không bao giờ trúng ⇒ rò Runnable + nhân tần suất enum.
     *
     * Ngoại lệ ⇒ TẮT nhịp (không nuốt im lặng, cũng không để `running` kẹt true): `running` kẹt true mà không
     * còn Runnable nào trong hàng đợi thì [NavWindowPump.arm] trả false vĩnh viễn ⇒ nhịp chết luôn,
     * không event nào mồi lại được. Tắt hẳn rồi để event nav kế tiếp mồi lại là đường phục hồi ĐÚNG.
     */
    private val windowPumpRunnable = Runnable {
        runCatching { onWindowPumpTick() }
            .onFailure {
                Log.w(TAG, "nhịp enum cửa sổ hỏng → tắt nhịp, chờ event nav mồi lại", it)
                stopWindowPump()
            }
    }

    // B-II: chống nhảy nguồn giữa hai nhịp enum (challenger phải thắng 3 nhịp LIÊN TIẾP; cấm đổi nguồn khi
    // holder đã cam kết vào khúc rẽ). Chỉ chạm trên luồng callback a11y (main) → không cần khoá, cùng lý do đã
    // ghi cho lastText/lastBoundsWalkAt ở trên. Đồng hồ MONOTONIC (elapsedRealtime) vì wall-clock đầu xe NHẢY
    // khi đồng bộ giờ GPS. tickPeriodMs lấy đúng hằng số nhịp thật để không chép số ra hai nơi.
    private val navDwell = NavSourceDwell(
        clock = { SystemClock.elapsedRealtime() },
        tickPeriodMs = WINDOW_ENUM_THROTTLE_MS,
    )

    // T3: nút vật lý → trợ lý giọng nói. Matcher thuần ở :core; service chỉ map KeyEvent + phóng intent.
    private val voiceKeyMatcher = VoiceKeyMatcher()

    override fun onServiceConnected() {
        NavAccessibilitySource.connected = true
        voiceKeyMatcher.reset()
        navDwell.reset()          // B-II: phiên mới bắt đầu SẠCH (cùng khuôn voiceKeyMatcher.reset())
        // B3.13r: dọn hàng đợi TRƯỚC khi quên sổ sách. `removeCallbacks` so THAM CHIẾU nên nó chỉ gỡ được
        // Runnable của CHÍNH thể hiện service này — tức ca rebind-cùng-thể-hiện; Runnable của một thể hiện
        // service TRƯỚC là việc của `onDestroy` (đường bảo vệ thật). Ở đây chủ yếu là để `running`/mốc
        // throttle và hàng đợi khởi hành từ cùng một trạng thái, không mâu thuẫn nhau.
        stopWindowPump()
        navWindowPump.reset()     // …rồi mới xoá mốc throttle (reset sau stop, không đảo).
        lastEnumSawNavWindow = null
        // B3.13r vòng 2 (08-24) — MỒI NGUỘI. Nhịp chỉ có hai đường mồi, và đường event KHÔNG bảo đảm sẽ tới: nếu
        // service (re)connect trong lúc app dẫn ĐÃ hiển thị sẵn và đang im event (chính định nghĩa của bệnh
        // B3.13r) thì không còn ai đánh thức vòng enum. Mồi khống rất rẻ và TỰ GIỚI HẠN: không có cửa sổ nav
        // nào thì `NavWindowPump.onTickDone` tắt nhịp sau IDLE_STOP_TICKS lượt, và thân nhịp vẫn qua cổng
        // Prefs trước khi enum (tắt công tắc ⇒ lượt đầu tiên chết ngay).
        if (navWindowPump.arm()) scheduleWindowPump()
        Log.i(TAG, "accessibility booster connected")
        // 08-22: bỏ gate BuildConfig.DEBUG (probe view-id kiểu OpenBYD phải chạy được trên bản
        // vehicleTest/release TRÊN XE). Cổng an ninh chuyển xuống LÚC NHẬN broadcast — xem KDoc
        // [registerDebugWindowDump]: không bật "Thu thập dữ liệu chẩn đoán" thì dump là no-op.
        registerDebugWindowDump()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        NavAccessibilitySource.connected = false
        stopWindowPump()          // B3.13r: service bị gỡ ⇒ nhịp phải chết theo, không rò Runnable/IPC.
        runCatching { debugWinReceiver?.let { unregisterReceiver(it) } }
        debugWinReceiver = null
        return super.onUnbind(intent)
    }

    /**
     * B3.13r — đường tắt nhịp THỨ HAI. `onUnbind` KHÔNG được bảo đảm gọi trong mọi ca huỷ service (user tắt
     * quyền hỗ trợ / hệ thống thu hồi), nên chốt lại ở `onDestroy`: Handler còn Runnable treo là còn enum
     * `getWindowsOnAllDisplays()` trên một service đã chết.
     */
    override fun onDestroy() {
        stopWindowPump()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /**
     * B3.13r — MỘT lượt nhịp: cổng người dùng → enum → tự quyết có hẹn lượt sau không.
     *
     * CỔNG ĐẶT Ở ĐÂY, KHÔNG Ở CHỖ MỒI (bài học B3.30 — `WazeHudSource` poll 900 ms chạy VÔ ĐIỀU KIỆN, thậm
     * chí TRƯỚC cổng `Prefs.enabled`, sinh ~4000 lệnh/giờ): người dùng tắt app hoặc tắt booster GIỮA CHỪNG
     * thì lượt kế tiếp phải chết ngay, chứ không đợi tới lần (re)connect. Đọc pref mỗi nhịp là rẻ —
     * `SharedPreferences` đã nằm sẵn trong RAM, và đường event vốn đọc dày hơn thế nhiều.
     */
    private fun onWindowPumpTick() {
        val ctx = applicationContext
        if (!Prefs.enabled(ctx) || !Prefs.accBooster(ctx)) { stopWindowPump(); return }
        lastEnumSawNavWindow = null          // chưa có quan sát mới cho lượt này
        runCatching { resolveNavWindowRegardlessOfFocus(SystemClock.elapsedRealtime()) }
            .onFailure { Log.w(TAG, "nhịp: window-enum nav resolve failed", it) }
        // Còn cửa sổ nav ⇒ hẹn lượt sau. Hết ⇒ NavWindowPump tắt nhịp, hàng đợi sạch, chi phí IPC về 0;
        // event nav kế tiếp (hoặc lần (re)connect kế tiếp) sẽ mồi lại qua NavWindowPump.arm — đường độc lập
        // với chính vòng enum này.
        if (navWindowPump.onTickDone(lastEnumSawNavWindow)) scheduleWindowPump()
    }

    /** Hẹn ĐÚNG MỘT lượt nhịp. `removeCallbacks` trước khi post: hàng đợi không bao giờ có hai lượt trùng. */
    private fun scheduleWindowPump() {
        runCatching {
            pumpHandler.removeCallbacks(windowPumpRunnable)
            pumpHandler.postDelayed(windowPumpRunnable, WINDOW_ENUM_THROTTLE_MS)
        }.onFailure {
            Log.w(TAG, "không hẹn được nhịp enum → tắt nhịp", it)
            navWindowPump.stop()
        }
    }

    /** Tắt nhịp: gỡ Runnable khỏi hàng đợi RỒI mới hạ cờ — không đảo, để không có khe post-lại xen giữa. */
    private fun stopWindowPump() {
        runCatching { pumpHandler.removeCallbacks(windowPumpRunnable) }
        navWindowPump.stop()
    }

    // ─── Probe chẩn đoán: dump node text/content-desc của MỌI window (getWindows +
    //     getWindowsOnAllDisplays) qua `am broadcast -a com.byd.clusternav.DEBUG_DUMP_WINDOWS`.
    //     Để KIỂM TRA: overlay VietMap nền có phơi a11y node (text) hay là Canvas (rỗng) — đọc-không-cần-chụp.
    //
    // ⚠ AN NINH — CỔNG BẮT BUỘC (sửa 08-22 vòng 1, [P1]). Bản 08-22 gỡ `if (BuildConfig.DEBUG)` để probe chạy
    //   được trên bản vehicleTest/release TRÊN XE, nhưng receiver đăng ký `RECEIVER_EXPORTED` và action không
    //   đòi permission nào ⇒ BẤT KỲ app nào trên đầu xe cũng bắn được một broadcast để bắt service ĐẶC QUYỀN
    //   HỖ TRỢ này đổ `text` + `contentDescription` của MỌI cửa sổ MỌI app (ngân hàng, tin nhắn…) ra logcat.
    //   Không thể chuyển sang `RECEIVER_NOT_EXPORTED` (thì `am broadcast` từ adb — chính đường probe — cũng
    //   không vào được), nên cổng đặt ở LÚC NHẬN: chỉ chạy khi chủ xe đã BẬT TƯỜNG MINH công tắc "Thu thập dữ
    //   liệu chẩn đoán" ([Prefs.navVerboseLog], mặc định FALSE — xem KDoc [NavLog]). Đọc thẳng pref bền thay
    //   vì gương RAM [NavLog.verbose]: broadcast là đường hiếm nên một lần đọc SharedPreferences không tốn gì,
    //   mà lại đúng kể cả khi tiến trình chưa đi qua điểm gọi `NavLog.init`.
    private var debugWinReceiver: android.content.BroadcastReceiver? = null

    private fun registerDebugWindowDump() {
        if (debugWinReceiver != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                val allowed = runCatching { Prefs.navVerboseLog(applicationContext) }.getOrDefault(false)
                if (!allowed) {
                    Log.i(TAG, "DEBUG window-dump BỎ QUA: chưa bật 'Thu thập dữ liệu chẩn đoán'")
                    return
                }
                runCatching { debugDumpWindows() }.onFailure { Log.w(TAG, "debug win dump failed", it) }
            }
        }
        runCatching {
            val f = android.content.IntentFilter("com.byd.clusternav.DEBUG_DUMP_WINDOWS")
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                registerReceiver(rx, f, android.content.Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(rx, f)
            }
            debugWinReceiver = rx
            Log.i(TAG, "DEBUG window-dump receiver registered")
        }.onFailure { Log.w(TAG, "debug win receiver register failed", it) }
    }

    /**
     * (giữ lại làm probe chẩn đoán) Đọc ĐÚNG bộ view-id mà OpenBYD dùng để đọc nav Waze
     * (`BydAccessibilityService.getRootNodeForPackages` → `findAccessibilityNodeInfosByViewId`).
     *
     * Mục đích: trả lời dứt điểm "bản Waze/WazeMod trên máy này còn phơi các id đó không, hay đã chuyển
     * Compose". `uiautomator dump` KHÔNG trả lời được vì nó chỉ chụp active window; OpenBYD duyệt MỌI window.
     * Cần `flagReportViewIds` (bật lại 2026-08-22) nếu không hàm tra id luôn trả rỗng.
     */
    private fun probeOpenBydViewIds(pkg: String, root: android.view.accessibility.AccessibilityNodeInfo) {
        if (pkg.isEmpty() || pkg == "?") return
        val ids = listOf(
            "navBarDistance", "navBarStreetLine", "lblArrivalTime",
            "lblTimeToDestination", "lblDistanceToDestination", "navBarDirection", "laneGuidanceView",
            // ĐỐI CHỨNG: id chắc chắn tồn tại (thấy trong uiautomator dump). Nếu id này CŨNG không tra được
            // thì lỗi ở dụng cụ đo (cờ flagReportViewIds chưa ăn), KHÔNG phải "app không phơi id".
            "mainContentCompose", "mainActivityRoot", "action_bar_root",
        )
        // WazeMod là Waze ĐÓNG GÓI LẠI: package là com.chisadin.wazemod nhưng resource-id vẫn mang tiền tố
        // `com.waze:id/...`. OpenBYD nối id bằng chính packageName nên với bản mod sẽ TRƯỢT — thử cả hai.
        val prefixes = linkedSetOf(pkg, NavApps.WAZE_RES_PREFIX)
        val found = StringBuilder()
        for (pfx in prefixes) for (id in ids) {
            val hits = runCatching { root.findAccessibilityNodeInfosByViewId("$pfx:id/$id") }.getOrNull()
            if (hits.isNullOrEmpty()) continue
            val n = hits[0]
            val r = android.graphics.Rect(); runCatching { n.getBoundsInScreen(r) }
            found.append(" $pfx:$id='${n.text}'/desc='${n.contentDescription}'@$r")
            hits.forEach { runCatching { it.recycle() } }
        }
        Log.i(TAG, "OPENBYD-VIEWID-PROBE pkg=$pkg →${if (found.isEmpty()) " (KHÔNG có id nào)" else found}")
    }

    /**
     * Đọc nav bằng **view-id** (clone OpenBYD). Trả kết quả nếu đọc được ít nhất cự ly hoặc tên đường; null =
     * không phải app đang dẫn.
     *
     * ⚠ B-II — DÒ CÂM: hàm này TUYỆT ĐỐI KHÔNG publish vào [NavViewIdSource]/[CaptureBoundsSource]. Vòng enum
     * phải dò NHIỀU ứng viên trong một nhịp rồi mới bầu ra một nguồn; publish ngay lúc dò là ứng viên bị
     * [NavSourceDwell] loại vẫn ghi đè cự ly của nguồn đang giữ (holder MỘT-Ô) — chính cái bug đang chữa.
     * Việc ghi holder nằm ở [publishViewIdReading], chỉ gọi cho nguồn ĐÃ ĐƯỢC BẦU.
     *
     * Vì sao thử CẢ `root.packageName` LẪN `com.waze`: tiền tố của `viewIdResourceName` là tên package ghi
     * trong `resources.arsc`, KHÔNG phải applicationId. Đo tĩnh bằng aapt2 trên
     * `apk-ref/WazeMod_V9_Stable_05082026_Android10_DUAL.apk`: manifest `com.chisadin.wazemod` nhưng
     * `Package name=com.waze id=7f` ⇒ id là `com.waze:id/...`.
     *
     * ⚠ ĐÍNH CHÍNH (08-22, sau khi đọc lại OpenBYD): OpenBYD KHÔNG trượt vì tiền tố. Cổng của họ là
     * `Collections.singleton(AppConstants.WAZE_PACKAGE)` (`BydAccessibilityService.java:240`) — root nào
     * không đúng `com.waze` bị loại TRƯỚC khi tra id, nên tiền tố của họ luôn đúng theo cấu tạo. Ta phải thử
     * hai tiền tố vì roster của TA có thêm bản mod, chứ không phải vì code họ sai.
     */
    private data class ViewIdReading(
        val turnMeters: Int,
        val road: String,
        val arrival: String,
        val routeSeconds: Int,
        val routeMeters: Int,
        val arrowBounds: android.graphics.Rect?,
    )

    private fun probeNavByViewId(pkg: String, root: android.view.accessibility.AccessibilityNodeInfo): ViewIdReading? {
        fun textOf(id: String): String? {
            for (pfx in linkedSetOf(pkg, WAZE_RES_PREFIX)) {
                val hits = runCatching { root.findAccessibilityNodeInfosByViewId("$pfx:id/$id") }.getOrNull()
                if (hits.isNullOrEmpty()) continue
                val t = hits[0].text?.toString()
                hits.forEach { runCatching { it.recycle() } }
                if (!t.isNullOrBlank()) return t
            }
            return null
        }
        fun boundsOf(id: String): android.graphics.Rect? {
            for (pfx in linkedSetOf(pkg, WAZE_RES_PREFIX)) {
                val hits = runCatching { root.findAccessibilityNodeInfosByViewId("$pfx:id/$id") }.getOrNull()
                if (hits.isNullOrEmpty()) continue
                val r = android.graphics.Rect()
                runCatching { hits[0].getBoundsInScreen(r) }
                hits.forEach { runCatching { it.recycle() } }
                if (r.width() > 0 && r.height() > 0) return r
            }
            return null
        }

        val distTxt = textOf("navBarDistance")
        val road = textOf("navBarStreetLine").orEmpty()
        if (distTxt == null && road.isEmpty()) return null

        return ViewIdReading(
            turnMeters = distTxt?.let { NavParse.parseMeters(it) } ?: -1,
            road = road,
            // CHUẨN HOÁ TẠI NƠI SẢN XUẤT (08-23 vòng 3): Waze phơi 12 h ('5:50 PM' — chuỗi ĐÃ ĐO), VietMap
            // phơi 24 h ⇒ ghi thô là để MỘT ô holder mang HAI miền. Đo hạ nguồn: NavigationFrame.init `require`
            // NÉM với "5:50 PM", còn BydHal §ETA_H split(":") ra giờ 5 (đúng 17) + phút RỤNG. Không đọc được
            // ⇒ "" (im lặng, không đoán). Xem [NavParse.extractArrivalClock24] để biết vì sao là hàm RIÊNG.
            arrival = textOf("lblArrivalTime")?.let { NavParse.extractArrivalClock24(it) }.orEmpty(),
            routeSeconds = textOf("lblTimeToDestination")?.let { parseMinutesToSeconds(it) } ?: -1,
            routeMeters = textOf("lblDistanceToDestination")?.let { NavParse.parseMeters(it) } ?: -1,
            // KHUNG VẼ mũi tên: đây chính là view mà 38 template ManeuverRegistry sinh ra từ đó, nên crop theo nó
            // rồi khớp registry OpenBYD là ĐÚNG quy ước (khác quy ước bbox-mực của WazeArrowRegistry).
            arrowBounds = boundsOf("navBarDirection"),
        )
    }

    /**
     * B-II — ghi kết quả của nguồn ĐÃ ĐƯỢC BẦU vào holder dùng chung.
     *
     * VÌ SAO tách khỏi bước dò: [NavViewIdSource] / [CaptureBoundsSource] là holder MỘT-Ô; dò mà publish luôn
     * thì ứng viên bị dwell loại vẫn ghi đè cự ly của nguồn đang giữ → đúng triệu chứng "cự ly nhảy giữa hai app"
     * (`NavOutputOwner` chỉ nhận cự ly khi pkg khớp `ScreenCaptureSignal.arrowPkg` ⇒ số → gạch → số).
     *
     * §R-BI: rect mũi tên đi kèm CHỦ (package runtime của app vừa đọc được view-id) — consumer chốt sameFrame
     * trước khi crop, nếu không rect của app khác crop ra pixel hợp lệ ⇒ SAI HƯỚNG.
     */
    private fun publishViewIdReading(pkg: String, r: ViewIdReading, now: Long) {
        NavViewIdSource.publish(pkg, r.turnMeters, r.road, r.arrival, r.routeSeconds, r.routeMeters, now)
        // §R-BI + B3.53-review: rect đi kèm CHỦ và MỤC TIÊU. Đây là `navBarDirection` = KHUNG VẼ mũi tên
        // (view-id đích danh) ⇒ target ARROW; router chỉ áp nó cho plan ARROW (xem `CaptureBounds.target`).
        r.arrowBounds?.let {
            CaptureBoundsSource.publish(pkg, CaptureTarget.ARROW, it.left, it.top, it.right, it.bottom, now)
        }
        if (NavLog.verbose) {
            Log.i(TAG, "viewid-nav pkg=$pkg dist=${r.turnMeters}m road='${r.road}' eta='${r.arrival}' " +
                "left=${r.routeMeters}m/${r.routeSeconds}s")
        }
    }

    /**
     * "6 min" / "6 phút" / "1 hr 20 min" → giây. -1 nếu không đọc được.
     *
     * DÙNG LẠI [NavParse.parseEta] (:core, đã test off-car) thay vì regex riêng — CLAUDE.md §4.1 (DRY).
     * Bản 08-22 tự viết `Regex("(\\d+)")` lấy SỐ ĐẦU TIÊN nên `"1 hr 20 min"` ra 60 s thay vì 4800 s;
     * [NavParse] đã có sẵn `RE_ETA_HR` + `RE_ETA_MIN` xử lý đúng ca có giờ.
     */
    private fun parseMinutesToSeconds(s: String): Int = NavParse.parseEta(s).second

    private fun debugDumpWindows() {
        val all = ArrayList<android.view.accessibility.AccessibilityWindowInfo>()
        runCatching { windows?.let { all.addAll(it) } }
        runCatching {
            val m = AccessibilityService::class.java.getMethod("getWindowsOnAllDisplays")
            @Suppress("UNCHECKED_CAST")
            val sp = m.invoke(this) as? android.util.SparseArray<List<android.view.accessibility.AccessibilityWindowInfo>>
            if (sp != null) for (i in 0 until sp.size()) sp.valueAt(i)?.let { all.addAll(it) }
        }
        Log.i(TAG, "DEBUG-WIN-DUMP windows=${all.size}")
        for (w in all) {
            val root = runCatching { w.root }.getOrNull()
            val pkg = root?.packageName?.toString() ?: "?"
            if (root != null) probeOpenBydViewIds(pkg, root)
            val texts = ArrayList<String>()
            if (root != null) collectNodeTexts(root, texts, 0)
            val type = runCatching { w.type }.getOrDefault(-1)
            Log.i(TAG, "DEBUG-WIN pkg=$pkg type=$type nodes=${texts.size} texts=[${texts.take(14).joinToString(" | ")}]")
        }
    }

    private fun collectNodeTexts(n: android.view.accessibility.AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 40 || out.size > 60) return
        val t = n.text?.toString()?.trim()
        val d = n.contentDescription?.toString()?.trim()
        if (!t.isNullOrEmpty()) out.add("T:$t")
        if (!d.isNullOrEmpty()) out.add("D:$d")
        for (i in 0 until n.childCount) {
            runCatching { n.getChild(i) }.getOrNull()?.let { collectNodeTexts(it, out, depth + 1) }
        }
    }

    /**
     * T3 — nút vật lý → trợ lý giọng nói. Chỉ chạy khi service được cấp quyền hỗ trợ + config
     * `canRequestFilterKeyEvents` + flag `flagRequestFilterKeyEvents` (xem nav_accessibility_config.xml).
     *
     * KHÔNG thay chức năng gốc: chỉ trả true (nuốt phím) cho mã phím CÓ TRONG danh sách gán của người dùng
     * — quyết định ở [VoiceKeyMatcher] (:core). Phím khác → super (pass-through).
     * "Học phím": nếu bật, ghi lại keycode nút vừa bấm (trên DOWN) rồi tự tắt cờ.
     */
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return super.onKeyEvent(event)
        val app = applicationContext

        if (Prefs.voiceKeyLearn(app)) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                Prefs.setVoiceKeyLearn(app, false)
                Log.i(TAG, "learned voice keycode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
                VoiceKeyLearnBus.publish(event.keyCode)   // Activity (đang mở màn) hiện dialog đặt tên
            }
            return true   // nuốt trong lúc học để không kích hoạt gì khác
        }

        if (!Prefs.voiceKeyEnabled(app)) return super.onKeyEvent(event)

        // F3 (owner 2026-08-24): tra DANH SÁCH gán, không so với một mã nữa. Danh sách rỗng ⇒ mọi phím
        // pass-through (matcher trả IGNORE) ⇒ không nuốt nhầm phím nào của xe.
        val cfg = VoiceKeyConfig(enabled = true, bindings = Prefs.voiceKeyBindings(app))
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> VoiceKeyAction.DOWN
            KeyEvent.ACTION_UP -> VoiceKeyAction.UP
            else -> VoiceKeyAction.OTHER
        }
        val decision = voiceKeyMatcher.onKey(cfg, action, event.keyCode, event.downTime)
        // Đích lấy TỪ quyết định (bất biến: fire ⟺ targetSpec != null) — KHÔNG tra lại prefs, tra hai lần
        // có thể ra hai kết quả nếu owner vừa sửa danh sách giữa DOWN và lúc phóng intent.
        val spec = decision.targetSpec
        if (decision.fire && spec != null) {
            Log.i(TAG, "voice-key fire → target=$spec key=${event.keyCode}")
            runCatching { AssistantLauncher.launch(app, spec) }
                .onFailure { Log.e(TAG, "assistant launch failed", it) }
        }
        return if (decision.consume) true else super.onKeyEvent(event)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in navPackages) return
        if (!Prefs.enabled(applicationContext) || !Prefs.accBooster(applicationContext)) return

        // B3 (screen-capture nav, spec waze-vietmap-screen-capture §4.4): for ANY nav package publish (a) the
        // foreground package for ScreenCaptureNavSource's gate, and (b) a best-effort arrow/camera node bounds
        // for CaptureRouter's a11y-dynamic tier. ADDITIVE + degrade-safe — runs BEFORE the GMaps-only
        // distance-scan below and never touches it. ⚠ VERIFY-ON-CAR (OQ2): correct node identification +
        // bounds stability on-car is unproven; off-car only the publish/pick logic is locked (pure tests).
        val b3Now = SystemClock.elapsedRealtime()
        // B3.7 (multi-app overlay contamination): only a REAL foreground-app window may (re)define the
        // foreground nav package. A floating/overlay window — e.g. WazeMod's HUD drawn over VietMap — must
        // NOT hijack the signal, or B3 routes VietMap to the ARROW target instead of CAMERA. The pure
        // decision lives in [ForegroundWindowFilter]; here we only read the (often unavailable) window info.
        if (isEventFromForegroundApp(event, pkg)) {
            CaptureForegroundSource.publish(pkg, b3Now)
            runCatching { maybePublishCaptureBounds(event, pkg, b3Now) }
                .onFailure { Log.w(TAG, "capture bounds publish failed", it) }
            // B3 T1b: bounds view lane-guidance (viewId) → LaneBoundsSource → ScreenCaptureNavSource crop → làn.
            runCatching { maybePublishLaneBounds(pkg, b3Now) }
                .onFailure { Log.w(TAG, "lane bounds publish failed", it) }
        }
        // B3.13: NGOÀI event-path (chỉ bắt app đang focus), duyệt TẤT CẢ window (getWindows /
        // getWindowsOnAllDisplays) tìm nav app BẤT KỂ foreground (như OpenBYD `rootNodeForPackages`) → mở gate
        // + đặt pkg khi Waze/VietMap CÒN HIỂN THỊ nhưng KHÔNG phải app trên cùng. Throttled (~1/s) để hạn chế
        // cost của flagRetrieveInteractiveWindows. Degrade-safe (mọi lỗi → bỏ qua).
        runCatching { resolveNavWindowRegardlessOfFocus(b3Now) }
            .onFailure { Log.w(TAG, "window-enum nav resolve failed", it) }
        // B3.13r: MỒI nhịp định kỳ. Event chỉ là cái mồi — sau đó nhịp tự chạy ĐỘC LẬP với event, nên ca "app
        // dẫn còn hiển thị nhưng im event" (bản đồ Skia/SurfaceView, người dùng không chạm) vẫn được enum và
        // CaptureForegroundSource vẫn tươi. Chỉ post khi nhịp đang TẮT: post mỗi event sẽ vừa nhân tần suất
        // vừa đẩy hạn nhịp ra xa mãi (event dày ⇒ nhịp không bao giờ nổ). Nằm SAU vòng enum cũ (CLAUDE.md §6:
        // đường mới xuống cuối) và SAU cổng Prefs.enabled + Prefs.accBooster ở đầu handler.
        if (navWindowPump.arm()) scheduleWindowPump()

        // MULTI-SOURCE capture (telemetry, verbose-gated in NavAccessLog): log the announced / window-content
        // voice-guidance text tagged by SOURCE package, so GMaps / VietMap / Waze / WazeMod rows are
        // distinguishable off-car. This is the ONLY same-device nav signal for VietMap/Waze/WazeMod. Gated on
        // NavLog.verbose HERE (not only inside NavAccessLog.record) so the DEFAULT telemetry-off path skips the
        // per-event text extraction entirely — GMaps fires TYPE_WINDOW_CONTENT_CHANGED densely on the UI thread
        // and this runs BEFORE the 200ms scan throttle below, so unguarded it would join/trim on every event.
        if (NavLog.verbose) {
            runCatching { logEventText(event, pkg) }.onFailure { Log.w(TAG, "logEventText failed", it) }
        }

        // GMaps on-screen distance GROUND-TRUTH path — UNCHANGED behaviour. Only GMaps lays out the readable
        // distance token (+ coords) that NavScreenScan parses to refine the interpolator; VietMap/Waze nav is
        // captured via the content-desc fallback in [logEventText] above, not this scan.
        if (pkg !in maps) return
        val now = SystemClock.elapsedRealtime()
        NavAccessibilitySource.lastEventAt = now
        if (now - lastProcessed < THROTTLE_MS) return         // GMaps bắn event dày -> tiết lưu 200ms
        lastProcessed = now

        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        runCatching { scan(root, now, pkg) }.onFailure { Log.e(TAG, "scan failed", it) }
        runCatching { root.recycle() }
    }

    /**
     * MULTI-SOURCE telemetry: capture the announced voice-guidance / window-content text from ANY nav source,
     * tagged by [pkg]. Only TYPE_ANNOUNCEMENT (spoken guidance) + TYPE_WINDOW_CONTENT_CHANGED (text that
     * carries guidance) are recorded; other event types and empty text are skipped. Consecutive identical
     * text per package is collapsed so the redraw flood doesn't spam the CSV. Verbose-gated + off-thread in
     * [NavAccessLog]; a wrong/empty capture never reaches the cluster (diagnostics only).
     */
    @Suppress("DEPRECATION") // TYPE_ANNOUNCEMENT was deprecated in API 36 only for SENDERS
    // (View.announceForAccessibility). A RECEIVING accessibility service has no replacement — the constant
    // stays the only way to detect other apps' spoken voice-guidance. GMaps fills event.text; VietMap/Waze
    // leave event.text EMPTY and render the nav (turn dist+road, speed+limit, ETA+dist+dest) on each view's
    // contentDescription instead, so an empty event.text falls back to a throttled walk of the source subtree.
    private fun logEventText(event: AccessibilityEvent, pkg: String) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_ANNOUNCEMENT,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> Unit
            else -> return
        }
        val eventText = event.text
            .joinToString(" ") { it?.toString().orEmpty() }
            .trim()
        // GMaps (and any source that fills event.text) uses this fast-path UNCHANGED. VietMap/Waze leave
        // event.text empty → fall back to the source-subtree contentDescription walk, THROTTLED per package
        // because TYPE_WINDOW_CONTENT_CHANGED fires densely (the event.text fast-path above is never throttled).
        val text = if (eventText.isNotEmpty()) {
            eventText
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - (lastDescWalkAt[pkg] ?: 0L) < DESC_WALK_THROTTLE_MS) return
            lastDescWalkAt[pkg] = now
            collectContentDescriptions(event.source)
        }
        if (text.isEmpty()) return
        if (lastText[pkg] == text) return
        lastText[pkg] = text
        NavAccessLog.record(applicationContext, pkg, NavAccessRow.NO_METERS, "", "", text)
    }

    /**
     * VietMap/Waze fallback: walk the [source] subtree, gather each node's contentDescription, and join them
     * into one telemetry string via the pure [NavDescJoin] (:core). Bounded (MAX_NODES / MAX_DEPTH) and recycles
     * child nodes exactly like [collect]; [source] itself is recycled here (the obtaining site, mirroring how
     * the GMaps scan recycles rootInActiveWindow). Degrade-safe — any failure yields "" — and null-safe
     * ([source] may be null → "").
     */
    private fun collectContentDescriptions(source: AccessibilityNodeInfo?): String {
        source ?: return ""
        val joined = runCatching {
            val descs = ArrayList<String>(32)
            gatherDescriptions(source, descs, 0)
            NavDescJoin.join(descs)
        }.getOrDefault("")
        runCatching { source.recycle() }
        return joined
    }

    /**
     * Bounded recursive collection of non-empty contentDescriptions (trimmed, ≤ 120 chars) from [node]'s
     * subtree — the VietMap/Waze equivalent of the text/coords gathered by [collect]. Recycles each obtained
     * child node with runCatching exactly like [collect] does.
     *
     * ── BA TRẦN, và vì sao [visited] là trần THẬT (thêm 08-23 vòng 2) ────────────────────────────────────
     * `out.size >= MAX_NODES` chặn **số chuỗi THU ĐƯỢC**, KHÔNG chặn **số node ĐÃ DUYỆT**: một cây Flutter
     * 3000 node mà chỉ 20 node có contentDescription vẫn bị duyệt TRỌN VẸN, và mỗi `getChild` là một lượt
     * binder sang tiến trình app kia. Với call site cũ ([logEventText]) điều đó chấp nhận được — nó gate
     * bằng `NavLog.verbose` (mặc định TẮT), throttle 150 ms/pkg và chỉ đi `event.source` (một nhánh). Call
     * site MỚI ([probeNavByContentDesc]) thì KHÁC HẲN HẠNG: không gate, chạy trên bản release, mỗi 800 ms,
     * từ **root cả cửa sổ** của một app vẽ bản đồ liên tục — trên luồng callback a11y (main) của đầu xe.
     * [visited] đóng đúng khe đó: đếm node ĐÃ CHẠM, không đếm output.
     *
     * @param visited bộ đếm DÙNG CHUNG cho cả cây (ô 0). Mỗi lần gọi từ ngoài truyền mảng MỚI ⇒ mỗi lượt đi
     *   cây có ngân sách riêng; các lời gọi đệ quy PHẢI chuyền tiếp đúng mảng đó (mặc định chỉ dành cho caller).
     */
    private fun gatherDescriptions(
        node: AccessibilityNodeInfo?,
        out: ArrayList<String>,
        depth: Int,
        visited: IntArray = IntArray(1),
    ) {
        node ?: return
        if (out.size >= MAX_NODES || depth > MAX_DEPTH || visited[0] >= MAX_VISIT_NODES) return
        visited[0]++
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrEmpty() && d.length <= 120) out.add(d)
        for (i in 0 until node.childCount) {
            // DỪNG HẲN vòng anh-em khi hết ngân sách (sửa 08-23 vòng 2b). Trần ở ĐẦU hàm chỉ chặn lượt đệ quy
            // KẾ TIẾP — nó quay ra ngay, nhưng `node.getChild(i)` ở dòng dưới thì ĐÃ CHẠY: mà getChild mới
            // chính là lượt binder sang tiến trình app kia, tức phần đắt. Không có `break` này thì một node
            // rộng (Flutter list ảo) vẫn tốn đúng `childCount` lượt binder ở MỌI mức của ngăn xếp sau khi
            // trần đã chạm ⇒ trần "node ĐÃ DUYỆT" bọc output chứ không bọc chi phí, đúng thứ nó sinh ra để chặn.
            if (out.size >= MAX_NODES || visited[0] >= MAX_VISIT_NODES) break
            val c = node.getChild(i) ?: continue
            gatherDescriptions(c, out, depth + 1, visited)
            runCatching { c.recycle() }
        }
    }

    /**
     * B3.7 — decide whether [event] comes from the REAL foreground-app window (so it may update the foreground
     * nav package) or from a floating/overlay window that must not hijack it. The DECISION is pure
     * ([ForegroundWindowFilter], unit-tested off-car); here we only read the AccessibilityWindowInfo.
     *
     * ⚠ Window info (type / isActive / isFocused) needs `flagRetrieveInteractiveWindows`, deliberately OFF for
     * perf (see nav_accessibility_config.xml → it makes system_server track every window on every display,
     * doubled while casting). So on-car `event.source?.window` is usually null → [ForegroundWindowFilter.TYPE_UNKNOWN]
     * → the pure filter degrades to event semantics (WINDOW_STATE_CHANGED, or same-pkg keep-alive). Degrade-safe.
     */
    private fun isEventFromForegroundApp(event: AccessibilityEvent, pkg: String): Boolean {
        val isStateChange = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val sameAsCurrent = CaptureForegroundSource.pkg == pkg
        var winType = ForegroundWindowFilter.TYPE_UNKNOWN
        var active = false
        var focused = false
        val node = runCatching { event.source }.getOrNull()
        val win = runCatching { node?.window }.getOrNull()
        if (win != null) {
            winType = win.type
            active = win.isActive
            focused = win.isFocused
        }
        runCatching { node?.recycle() }
        // B3.7 fix: `rootInActiveWindow` = cửa sổ ACTIVE thật (app foreground); overlay nổi KHÔNG phải active
        // window. So khớp package → nhận diện foreground THẬT ở đường UNKNOWN (interactive-windows tắt), nên
        // BOOTSTRAP đúng ngay cả khi ClusterNav khởi động lúc nav app đã mở sẵn (không cần chờ WINDOW_STATE_CHANGED),
        // mà overlay của package KHÁC vẫn bị loại (nó không phải active window).
        val fromActiveWin = runCatching {
            val r = rootInActiveWindow
            val match = r?.packageName?.toString() == pkg
            runCatching { r?.recycle() }
            match
        }.getOrDefault(false)
        return ForegroundWindowFilter.shouldPublishForeground(
            winType, active, focused, isStateChange, sameAsCurrent, fromActiveWin,
        )
    }

    /**
     * B3.13 — duyệt MỌI window (`getWindows()` + `getWindowsOnAllDisplays()` API30) tìm nav app BẤT KỂ
     * foreground → [CaptureForegroundSource].publish(pkg) → mở gate B3 + đặt pkg khi nav app CÒN hiển thị
     * nhưng KHÔNG phải app trên cùng (đây là cách OpenBYD đọc Waze lúc không active). Quyết định (pick) THUẦN ở
     * [NavWindowPicker]; ở đây chỉ gom window → WinInfo. Throttled + degrade-safe.
     */
    private fun resolveNavWindowRegardlessOfFocus(now: Long) {
        // B3.13r: throttle chuyển vào [NavWindowPump.tryEnum] — CÙNG một cổng cho CẢ đường event LẪN đường
        // nhịp, giữ NGUYÊN phép so cũ (`now - mốc < 800ms`). Đây là trần chi phí: nhịp mới KHÔNG nâng số lần
        // enum/giây, nó chỉ lấp chỗ trống lúc event im.
        if (!navWindowPump.tryEnum(now)) return
        val raw = ArrayList<AccessibilityWindowInfo>(16)
        runCatching { windows?.let { raw.addAll(it) } }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val m = AccessibilityService::class.java.getMethod("getWindowsOnAllDisplays")
                @Suppress("UNCHECKED_CAST")
                (m.invoke(this) as? SparseArray<List<AccessibilityWindowInfo>>)?.let { sp ->
                    for (i in 0 until sp.size()) sp.valueAt(i)?.let { raw.addAll(it) }
                }
            }
        }
        val wins = ArrayList<NavWindowPicker.WinInfo>(raw.size)
        raw.forEach { addWinInfo(wins, it) }
        val ranked = NavWindowPicker.rank(wins, navPackages)
        // B3.13r: QUAN SÁT của lượt này — có cửa sổ nav nào trên máy không. Nhịp đọc cờ này để tự tắt khi
        // không còn app dẫn nào hiển thị (chi phí IPC về 0). Đặt Ở ĐÂY, không đặt trong nhánh rỗng bên dưới,
        // để cả hai kết quả (có/không) đều được ghi đúng MỘT chỗ.
        // ⚠ Đây là "CÓ CỬA SỔ", KHÔNG phải "ĐANG DẪN" — app dẫn chỉ hiển thị mà không dẫn vẫn giữ nhịp sống.
        // Hệ quả chi phí + vì sao chưa siết: KDoc [NavWindowPump.onTickDone] (backlog B3.13r-cost).
        lastEnumSawNavWindow = ranked.isNotEmpty()
        if (NavLog.verbose) Log.i(TAG, "nav-window-enum nWin=${wins.size} [${wins.joinToString { "${it.pkg}:t${it.type}:${it.bounds.width}x${it.bounds.height}:${if (it.focused) "F" else "-"}" }}] ranked=${ranked.map { it.pkg }}")
        if (ranked.isEmpty()) {
            // B-II: KHÔNG return trắng — vẫn phải chấm nhịp để holder LÃO HOÁ (R0). Không có cửa sổ nav nào thì
            // không bầu ai, nhưng sau holderGraceMs phải NHẢ, đừng giữ vĩnh viễn một nguồn đã tắt.
            // ⚠ Đường NHỊP không đủ dài để hoàn thành R0 (3,2 s < grace 6 s) — xem KDoc NavWindowPump.onTickDone.
            navDwell.onTick(candidate = null, holderAllowed = false)
            return
        }

        // GATE theo lựa chọn nguồn của người dùng — thiếu chốt này thì kênh view-id chạy bất kể user chọn gì.
        val mode = Prefs.sourceMode(applicationContext)
        val nowWall = System.currentTimeMillis()

        // Thử LẦN LƯỢT các ứng viên: ai ĐỌC ĐƯỢC dữ liệu dẫn đường thì người đó mới là app đang dẫn.
        // Ca thật (owner 08-22): cài cả Waze zin lẫn WazeMod, chỉ MỘT bản dẫn — bản đứng im có thể có cửa sổ
        // to hơn nên thắng ở bước xếp hạng, nhưng nó không có `navBarDistance` nên sẽ bị bỏ qua ở đây.
        //
        // B-II: dò CÂM theo thứ tự HOLDER-TRƯỚC. Holder còn đọc được dữ liệu ⇒ nó thắng nhịp này (bằng chứng
        // "đang dẫn" > phỏng đoán "cửa sổ to hơn"), đồng thời cho guard cự ly TƯƠI để chấm cam-kết-rẽ.
        // HỎI cổng bằng allows() (thuần) chứ KHÔNG bằng shouldFeed(): shouldFeed có tác dụng phụ chiếm khoá
        // nguồn, nên hỏi cho một ứng viên rồi loại nó vẫn để lại activeSource = ứng viên bị loại.
        val order = navDwell.probeOrder(ranked.map { it.pkg })
        var winner: String? = null
        var reading: ViewIdReading? = null
        for (pkg in order) {
            // CHỈ HỎI, KHÔNG GHI SỔ. Vòng enum quan sát CỬA SỔ, nó KHÔNG phải một kênh dữ liệu dẫn đường
            // (lý do đầy đủ + hai hỏng hóc của `shouldFeed` ở khối comment cuối hàm, khoá bằng
            // `NavSourceDwellWiringContractTest`).
            //
            // LỊCH SỬ (2026-08-22 → 08-23): bản 08-22 gọi `SourceArbiter.noteSeen(pkg, nowWall)` ở đây và đó
            // là HỒI QUY — hồi ấy `PREFER_X` còn cửa thoát `|| !isGroupFresh(X)` đọc sổ `lastSeenByPkg`, nên
            // ghi sổ cho MỌI ứng viên biến "app CÓ CỬA SỔ" thành "app CÒN DẪN" ⇒ ở PREFER_WAZE, mở Waze rồi
            // để đó không dẫn cũng khoá chết mọi app khác. Cả cửa thoát lẫn `lastSeenByPkg`/`noteSeen`/
            // `isGroupFresh` đã bị XOÁ khỏi `SourceArbiter` ngày 2026-08-23 (owner chốt: PREFER_X chỉ là
            // `pkg in X`, X không dẫn thì cụm im lặng — backlog B3.48), nên hồi quy đó nay bất khả thi theo
            // cấu tạo. Giữ nguyên nguyên tắc CHỈ-HỎI: `shouldFeed` vẫn có tác dụng phụ chiếm khoá nguồn +
            // đóng mốc kênh DATA, cả hai đều chết người ở đây.
            if (!SourceArbiter.allows(pkg, mode, nowWall)) continue     // user chọn nguồn khác
            val r = runCatching { probeRanked(raw, pkg) }
                .onFailure { Log.w(TAG, "viewid nav read failed", it) }.getOrNull() ?: continue
            winner = pkg; reading = r; break
        }

        // Không ai đọc được view-id (VietMap/GMaps không phơi bộ id này) → giữ hành vi cũ: lấy ứng viên hạng
        // nhất còn qua được cổng nguồn, để đường screen-capture vẫn có app để crop.
        val candidate = winner ?: ranked.firstOrNull { SourceArbiter.allows(it.pkg, mode, nowWall) }?.pkg

        val hold = navDwell.holder
        // Đọc cự ly holder ĐÚNG MỘT LẦN: publishViewIdReading bên dưới có thể ghi đè NavViewIdSource, nên đọc
        // lại lúc log sẽ in ra con số KHÁC con số mà quyết định đã dùng → log nói dối, không trace được.
        val holderM = hold?.let { holderTurnMeters(it, now) } ?: NavSourceDwell.NO_METERS
        val d = navDwell.onTick(
            candidate = candidate,
            // CỔNG MODE THUẦN, KHÔNG phải `allows()` (sửa 08-22 vòng 3). R3 dùng cờ này để BỎ QUA cả dwell
            // lẫn guard cam-kết-rẽ, nên nó chỉ được mang đúng một nghĩa: "user đổi PREFER_* ⇒ nhường NGAY".
            // `allows()` ở AUTO là khoá-giữ theo activeSource, không phải cổng mode: truyền nó vào đây làm R3
            // bắn khi một app KHÁC đang nuôi khung ⇒ đổi nguồn tức thì, vượt mặt R5 đúng giây vào cua. Lập
            // luận đầy đủ + ca hỏng ở KDoc SourceArbiter.allowedByMode.
            holderAllowed = hold != null && SourceArbiter.allowedByMode(hold, mode, nowWall),
            // Holder CÒN CỬA SỔ trên máy hay không — đo bằng chính danh sách đã xếp hạng nhịp này. Thiếu tham
            // số này thì `holderSeenAt` chỉ đo "lần cuối holder THẮNG bầu cử" ⇒ R4 đuổi một holder vẫn đang
            // dẫn (xem KDoc NavSourceDwell.onTick).
            holderPresent = hold != null && ranked.any { it.pkg == hold },
            holderTurnMeters = holderM,
            closingRateMps = TurnDistanceInterpolator.closingRate(),
        )
        val elected = d.source ?: return
        // CHỈ nguồn ĐÃ ĐƯỢC BẦU mới ghi holder một-ô. Ứng viên bị dwell/guard loại đi ra tay trắng.
        if (elected == winner && reading != null) publishViewIdReading(elected, reading, now)
        // ⚠ CỔNG THUẦN, TUYỆT ĐỐI KHÔNG `shouldFeed` (hồi quy 08-22, đã gỡ). Vòng enum chỉ quan sát CỬA SỔ,
        // nó KHÔNG phải một kênh dữ liệu dẫn đường. Gọi `shouldFeed(elected, mode, nowWall)` ở đây (channel
        // mặc định = DATA) gây HAI hỏng hóc, cả hai đều tất định từ source:
        //  1. [P0] đóng mốc `lastDataByPkg[elected]` mỗi 800 ms ⇒ `isDataFresh` LUÔN true ⇒ cả 4 lối publish
        //     kênh ẢNH trong ScreenCaptureNavSource (`shouldFeed(…, NavChannel.IMAGE)` → `return false` khi
        //     data tươi, SourceArbiter.kt:41) CHẾT ⇒ ScreenCaptureSignal rỗng ⇒ NavOutputOwner `plan.clear`
        //     ⇒ mũi tên/làn/camera KHÔNG BAO GIỜ lên cụm. Toàn bộ B3 thành code chết trên xe.
        //  2. [P1] đặt `activeSource = elected; activeSeen = nowWall` mỗi 800 ms — nhanh hơn STALE_MS (6 s) ⇒
        //     ở AUTO, `allows(gmaps)` = false VĨNH VIỄN chừng nào còn một cửa sổ nav khác hiện trên máy ⇒
        //     NavNotificationListener.kt:383/404 bỏ MỌI khung GMaps. Bỏ đói ĐÚNG đường đang chạy ngoài hiện
        //     trường (CLAUDE.md §6 cấm đổi hành vi đường đã proven).
        // `allows()` giữ nguyên ý đồ ban đầu (gate theo lựa chọn nguồn của user) mà không có tác dụng phụ nào.
        if (!SourceArbiter.allows(elected, mode, nowWall)) return
        CaptureForegroundSource.publish(elected, now)
        if (NavLog.verbose && (d.blocked != null || d.switched)) {
            Log.i(TAG, "dwell src=${d.source} ${d.reason} chặn=${d.blocked} streak=${d.streak}/${NavSourceDwell.DWELL_TICKS} " +
                "holder=${holderM}m ngưỡng=${d.commitMeters}m")
        }
        if (NavLog.verbose && winner == null) Log.i(TAG, "viewid-nav: không ứng viên nào đọc được → foreground=$elected")
    }

    /**
     * Dò CÂM một ứng viên trên toàn bộ window đã gom: tìm root có đúng [pkg] rồi đọc dữ liệu dẫn đường.
     * null = không đọc được (không phải app đang dẫn). Giữ nguyên vòng `for (w in raw)` + `root.recycle()`.
     *
     * HAI NHÁNH ĐỌC chạy SONG SONG trên CÙNG một `root` (không thêm vòng quét nào — CLAUDE.md §6: đường mới
     * xuống cuối, không đảo đường cũ):
     *  1. **view-id** ([probeNavByViewId]) — họ Waze, clone OpenBYD. Đường cũ, thử TRƯỚC, không đổi một dòng.
     *  2. **content-desc** ([probeNavByContentDesc]) — app Flutter không có resource-id ([NavApps.DESC_ONLY]).
     *     Chỉ chạy khi nhánh 1 KHÔNG đọc được gì ⇒ với Waze nó không bao giờ được hỏi tới.
     */
    private fun probeRanked(raw: List<AccessibilityWindowInfo>, pkg: String): ViewIdReading? {
        for (w in raw) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            val rp = runCatching { root.packageName?.toString() }.getOrNull()
            val r = if (rp == pkg) probeNavByViewId(rp, root) ?: probeNavByContentDesc(rp, root) else null
            runCatching { root.recycle() }
            if (r != null) return r
        }
        return null
    }

    /**
     * Đọc nav bằng **contentDescription** — đường của VietMap Live (Flutter ⇒ KHÔNG có resource-id; probe bộ
     * view-id OpenBYD trả rỗng, đo 2026-08-22). Quyết định/parse THUẦN ở [VietMapDescParser] (:core, test
     * off-car); ở đây chỉ đi cây a11y rồi gom chuỗi.
     *
     * ⚠ **DÒ CÂM** như [probeNavByViewId]: TUYỆT ĐỐI không publish vào [NavViewIdSource]/[CaptureBoundsSource].
     * Vòng enum dò nhiều ứng viên trong một nhịp rồi mới bầu; publish ngay lúc dò thì ứng viên bị
     * [NavSourceDwell] loại vẫn ghi đè cự ly của nguồn đang giữ (holder MỘT-Ô).
     *
     * `arrowBounds = null` là CỐ Ý: VietMap không phơi view khung-vẽ mũi tên nào để crop theo. Mũi tên của nó
     * đi đường ẢNH (`NavGlyphLocator` dò bbox mực + template riêng trong [WazeArrowRegistry]) — đúng quyết
     * định "VietMap đi HẲN đường screen-capture" của owner.
     *
     * Dùng lại [gatherDescriptions] (bounded MAX_NODES/MAX_DEPTH + recycle từng con) thay vì viết vòng đi cây
     * thứ hai — CLAUDE.md §4.1 (DRY). KHÔNG dùng `NavDescJoin`: nó dẹp `\n` thành khoảng trắng, mà `\n` chính
     * là thứ tách "Sau đó (195m)" khỏi "0m Lý Thường Kiệt".
     */
    private fun probeNavByContentDesc(pkg: String, root: AccessibilityNodeInfo): ViewIdReading? {
        // Cổng KHẢ NĂNG (không phải cổng tên gói): chỉ app đã ĐO ĐƯỢC là "không có resource-id" mới đi đường
        // này. Vì sao không mở cho mọi app: xem KDoc [NavApps.DESC_ONLY] (bảo vệ đường GMaps đã proven).
        if (pkg !in NavApps.DESC_ONLY) return null
        val descs = ArrayList<String>(32)
        val visited = IntArray(1)
        runCatching { gatherDescriptions(root, descs, 0, visited) }
            .onFailure { Log.w(TAG, "content-desc walk failed", it); return null }
        // Chạm trần = ta có thể đã CẮT MẤT node banner ⇒ parse trả null, mà "null" ở đây không phân biệt được
        // với "app không đang dẫn". Log để trên xe còn tách được hai ca đó (CLAUDE.md §8/§11) — không đổi
        // hành vi, chỉ thêm dấu vết.
        if (NavLog.verbose && (visited[0] >= MAX_VISIT_NODES || descs.size >= MAX_NODES)) {
            Log.i(TAG, "content-desc CHẠM TRẦN pkg=$pkg node=${visited[0]}/$MAX_VISIT_NODES desc=${descs.size}/$MAX_NODES")
        }
        val r = VietMapDescParser.parse(descs) ?: return null
        return ViewIdReading(
            turnMeters = r.turnMeters,
            road = r.road,
            arrival = r.arrivalClock,
            routeSeconds = r.routeSeconds,
            routeMeters = r.routeMeters,
            arrowBounds = null,
        )
    }

    /**
     * B-II — cự ly tới rẽ TƯƠI của nguồn đang giữ (m). [NavSourceDwell.NO_METERS] (-1) = KHÔNG BIẾT ⇒ guard
     * cam-kết-rẽ KHÔNG chặn: thà đổi nguồn còn hơn khoá cứng bằng một mẫu chết (CLAUDE.md §3 — không gate một
     * đường phục hồi bằng dữ liệu mà chỉ chính đường đó mới làm mới được).
     *
     *  • Waze/WazeMod: [NavViewIdSource] (tự hết tươi sau `FRESH_MS` = 4 s), đã gắn ĐÚNG pkg.
     *  • GMaps: mẫu ĐỌC-MÀN — nhánh scan chỉ chạy cho [maps] nên mẫu này LUÔN thuộc GMaps; chấm bằng đúng bộ
     *    phân định đã có ở :core ([TurnDistanceInterpolator.freshScreenRead], ngưỡng `SCREEN_READ_STALE_MS`
     *    2,5 s), cùng miền đồng hồ monotonic vì `lastReadAt` đóng mốc bằng `elapsedRealtime`.
     *  • VietMap: từ 08-23 CÓ kênh cự ly gắn pkg — [probeNavByContentDesc] đọc content-desc rồi
     *    [publishViewIdReading] ghi vào CÙNG holder [NavViewIdSource] (xem KDoc holder: nó khoá theo pkg nên
     *    hai nguồn sản xuất dùng chung là an toàn). Hạn chế cũ "VietMap ⇒ -1" đã hết.
     *
     * ⚠ CẤM "tiện tay" đọc [TurnDistanceInterpolator.lastProjected]: đường notification nhận cả
     * [NavApps.ALL] nên anchor của interpolator KHÔNG chắc thuộc GMaps — dùng nó là quy kết sai chủ.
     */
    private fun holderTurnMeters(holder: String, now: Long): Int {
        val viaViewId = NavViewIdSource.freshTurnMetersFor(holder, now)
        if (viaViewId >= 0) return viaViewId
        if (holder !in maps) return NavSourceDwell.NO_METERS
        val age = if (NavAccessibilitySource.lastReadAt > 0L) now - NavAccessibilitySource.lastReadAt else -1L
        return TurnDistanceInterpolator.freshScreenRead(
            NavAccessibilitySource.turnMeters, age, NavAccessibilitySource.road,
        )
    }

    /** Map 1 [AccessibilityWindowInfo] → [NavWindowPicker.WinInfo] (degrade-safe; bỏ nếu thiếu pkg). */
    private fun addWinInfo(out: MutableList<NavWindowPicker.WinInfo>, w: AccessibilityWindowInfo?) {
        w ?: return
        val root = runCatching { w.root }.getOrNull() ?: return
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        runCatching { root.recycle() }
        pkg ?: return
        val r = Rect(); runCatching { w.getBoundsInScreen(r) }
        val disp = if (Build.VERSION.SDK_INT >= 30) runCatching { w.displayId }.getOrDefault(0) else 0
        val focused = runCatching { w.isFocused }.getOrDefault(false)
        out.add(NavWindowPicker.WinInfo(pkg, w.type, CropRect(r.left, r.top, r.right, r.bottom), disp, focused))
    }

    /** B3 T1b: viewId của view lane-guidance. Waze/WazeMod (repackaged thường GIỮ id gốc `com.waze`). VietMap/khác = OQ. */
    /**
     * WazeMod = Waze ĐÓNG GÓI LẠI: package đổi (`com.chisadin.wazemod`) nhưng resource-id giữ nguyên tiền tố
     * gốc `com.waze`. OpenBYD nối id bằng chính `root.packageName` nên code của họ TRƯỢT trên bản mod —
     * ta phải thử cả hai tiền tố (đo 2026-08-22).
     */
    private val WAZE_RES_PREFIX = NavApps.WAZE_RES_PREFIX

    private val laneViewIds = listOf(
        "${NavApps.WAZE_RES_PREFIX}:id/laneGuidanceView",
        "${NavApps.WAZE_RES_PREFIX}:id/laneGuidanceContainer",
    )

    /**
     * B3 T1b: tìm view lane-guidance qua viewId → publish bounds (không gian màn) + số làn (childCount) →
     * [LaneBoundsSource] để `ScreenCaptureNavSource` crop vùng làn → LaneSignature. Degrade-safe; không thấy → bỏ.
     */
    private fun maybePublishLaneBounds(eventPkg: String, now: Long) {
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        // §R-BI: CHỦ của node mới là CHỦ của rect. Nhãn phải là package RUNTIME (`root.packageName`) — tuyệt
        // đối KHÔNG phải tiền tố resource `NavApps.WAZE_RES_PREFIX` (= "com.waze", cũng là tiền tố của
        // WazeMod): dán nhãn lệch thì consumer DROP im lặng, mất làn mà không có lỗi nào. Ở ĐÂY chỉ dán nhãn
        // TRUNG THỰC — việc drop là của consumer, một chỗ duy nhất.
        //
        // ⚠ KHÔNG rớt về [eventPkg] (sửa 08-22 vòng 2). `rootInActiveWindow` là cửa sổ ĐANG FOCUS, KHÔNG nhất
        // thiết là cửa sổ đã bắn event: node không khai chủ thì ta KHÔNG BIẾT rect này của ai, và đoán bằng
        // eventPkg là DỰNG một danh tính. [NavFrameIdentity] ghi rõ "rỗng/null KHÔNG bao giờ là danh tính hợp
        // lệ ⇒ im lặng"; đoán sai ở đây cho consumer `sameFrame` = true rồi crop rect của app A ra khỏi ảnh
        // app B — pixel HỢP LỆ, nhãn SAI, không tầng nào phát hiện được. Không biết chủ ⇒ bỏ nhịp này.
        val ownerPkg = runCatching { root.packageName?.toString() }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (ownerPkg == null) {
            // Dòng chẩn đoán: bỏ làn ở đây là IM LẶNG (không lỗi, không crash) — đúng kiểu bệnh CLAUDE.md §8
            // cảnh báo. Có dòng này thì trên xe grep một phát ra "vì sao mất dải làn".
            if (NavLog.verbose) Log.i(TAG, "lane-bounds BỎ: cửa sổ focus không khai chủ (event=$eventPkg)")
            runCatching { root.recycle() }
            return
        }
        try {
            for (id in laneViewIds) {
                val nodes = runCatching { root.findAccessibilityNodeInfosByViewId(id) }.getOrNull()
                val node = nodes?.firstOrNull() ?: continue
                val r = android.graphics.Rect()
                runCatching { node.getBoundsInScreen(r) }
                val lanes = runCatching { node.childCount }.getOrDefault(0)
                runCatching { nodes.forEach { it.recycle() } }
                if (r.width() > 0 && r.height() > 0) {
                    LaneBoundsSource.publish(ownerPkg, r.left, r.top, r.right, r.bottom, lanes, now)
                    return
                }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * B3 (screen-capture nav): best-effort walk of the nav app's subtree to find the arrow (Waze) / camera
     * (VietMap) node and publish its `getBoundsInScreen` to [CaptureBoundsSource] (CaptureRouter tier-1). The
     * NODE-PICK decision is pure ([CaptureBoundsHeuristic], unit-tested off-car); here we only gather bounded
     * candidates + recycle nodes exactly like [collect]/[gatherDescriptions]. Throttled per package, degrade-safe
     * (any failure → nothing published → router falls back to the fixed-calibrated tier). ⚠ VERIFY-ON-CAR (OQ2).
     */
    private fun maybePublishCaptureBounds(event: AccessibilityEvent, pkg: String, now: Long) {
        if (now - (lastBoundsWalkAt[pkg] ?: 0L) < BOUNDS_WALK_THROTTLE_MS) return
        lastBoundsWalkAt[pkg] = now
        val source = event.source ?: runCatching { rootInActiveWindow }.getOrNull() ?: return
        val target = CaptureTarget.forPackage(pkg)
        val candidates = ArrayList<CaptureBoundsHeuristic.Candidate>(64)
        gatherCaptureCandidates(source, candidates, 0)
        runCatching { source.recycle() }
        if (candidates.isEmpty()) return
        // region = union of observed node bounds (this app's window); the router additionally clamps the picked
        // rect to the app's half when split, so a loose union here is safe.
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
        for (c in candidates) {
            if (c.rect.left < l) l = c.rect.left
            if (c.rect.top < t) t = c.rect.top
            if (c.rect.right > r) r = c.rect.right
            if (c.rect.bottom > b) b = c.rect.bottom
        }
        val region = CropRect(l, t, r, b)
        if (region.isEmpty()) return
        val pick = CaptureBoundsHeuristic.pick(target, candidates, region) ?: return
        // §R-BI: rect luôn đi kèm CHỦ; B3.53-review: và đi kèm MỤC TIÊU nó được chọn cho — `target` ở đây là
        // `CaptureTarget.forPackage(pkg)`, tức VietMap ra CAMERA. Không dán nhãn thì router áp rect icon
        // camera cho cả plan ARROW ⇒ khớp MỀM trên crop sai chỗ ⇒ SAI HƯỚNG (xem `CaptureBounds.target`).
        CaptureBoundsSource.publish(pkg, target, pick.left, pick.top, pick.right, pick.bottom, now)
    }

    /** Bounded recursive collection of (className, contentDescription, screen-bounds) for [CaptureBoundsHeuristic]. */
    private fun gatherCaptureCandidates(
        node: AccessibilityNodeInfo?,
        out: ArrayList<CaptureBoundsHeuristic.Candidate>,
        depth: Int,
    ) {
        node ?: return
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val cls = node.className?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        if (cls.isNotEmpty() || desc.isNotEmpty()) {
            val r = Rect(); node.getBoundsInScreen(r)
            // B3.5: skip obvious noise (sub-icon slivers) here so the bounded candidate list + the region union
            // aren't polluted by 1–11px nodes; the real size gate (MIN_ICON_*) still lives in the pure heuristic.
            if (r.width() >= GATHER_MIN_PX && r.height() >= GATHER_MIN_PX) {
                out.add(CaptureBoundsHeuristic.Candidate(cls, desc, CropRect(r.left, r.top, r.right, r.bottom)))
            }
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            gatherCaptureCandidates(c, out, depth + 1)
            runCatching { c.recycle() }
        }
    }

    /**
     * Gom mọi node có text + toạ độ rồi giao phần QUYẾT ĐỊNH cho [NavScreenScan] trong `:core`.
     *
     * Trước 2026-07-27 heuristic chia dải trên/đáy, chọn token cự ly và chọn tên đường nằm ngay tại đây,
     * nên đúng đoạn quyết định con số tài xế thấy trên cụm lại không có bài kiểm nào. Ở đây giờ chỉ còn
     * việc đi cây `AccessibilityNodeInfo` và ghi kết quả — hai thứ thật sự cần Android.
     */
    private fun scan(root: AccessibilityNodeInfo, now: Long, pkg: String) {
        val items = ArrayList<Triple<String, Int, Int>>(64)
        // T3 (telemetry): also gather content descriptions (the arrow/maneuver hint GMaps hides there), but
        // ONLY when verbose and into a SEPARATE list that is NEVER fed to NavScreenScan — so refine() below is
        // completely unchanged.
        val descs = if (NavLog.verbose) ArrayList<String>(32) else null
        val screen = Rect(); root.getBoundsInScreen(screen)
        collect(root, items, descs, 0)
        if (items.isEmpty()) return

        val reading = NavScreenScan.scan(
            items.map { ScreenTextItem(it.first, it.second, it.third) },
            screen.height(),
        )

        if (reading.road.isNotEmpty()) NavAccessibilitySource.road = reading.road
        if (reading.bottomInfo.isNotEmpty()) NavAccessibilitySource.bottomInfo = reading.bottomInfo

        if (reading.turnMeters != NavScreenReading.UNKNOWN_METERS) {
            NavAccessibilitySource.turnMeters = reading.turnMeters
            NavAccessibilitySource.lastReadAt = now
            // Ghi đè anchor bằng cự ly đọc trên màn; refine tự bỏ qua nếu noti chưa mở nav.
            TurnDistanceInterpolator.refine(reading.turnMeters, now)
            NavAccessibilitySource.refines++
        }

        // T3 (telemetry): log screen-read metres + road + best-effort maneuver hint (verbose-gated; NavAccessLog
        // writes off-main). ADD-ONLY diagnostics — the refine() decision above is untouched.
        if (descs != null) {
            val hint = NavAccessHint.maneuverHint(descs, items.map { it.first })
            NavAccessibilitySource.maneuverHint = hint
            NavAccessLog.record(applicationContext, pkg, reading.turnMeters, reading.road, hint, "")
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: ArrayList<Triple<String, Int, Int>>,
        descOut: ArrayList<String>?,
        depth: Int,
    ) {
        node ?: return
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty() && t.length <= 80) {
            val r = Rect(); node.getBoundsInScreen(r)
            out.add(Triple(t, r.top, r.left))
        }
        // T3 (telemetry, verbose only): the directional cue usually lives on a contentDescription, not text.
        if (descOut != null && descOut.size < MAX_NODES) {
            val d = node.contentDescription?.toString()?.trim()
            if (!d.isNullOrEmpty() && d.length <= 120) descOut.add(d)
        }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collect(c, out, descOut, depth + 1)
            runCatching { c.recycle() }
        }
    }

    companion object {
        private const val TAG = "NavAccess"
        private const val THROTTLE_MS = 200L
        // Min gap between content-desc SUBTREE walks per package (VietMap/Waze fallback only). The event.text
        // fast-path is never throttled, so GMaps is unaffected.
        private const val DESC_WALK_THROTTLE_MS = 150L
        // B3: min gap between arrow/camera bounds subtree walks per package (dense TYPE_WINDOW_CONTENT_CHANGED).
        private const val BOUNDS_WALK_THROTTLE_MS = 250L
        // B3.5: gather floor — nodes smaller than this in either dimension are sub-icon noise (not the arrow
        // banner / camera icon) and are dropped before scoring; the authoritative size gate is in the heuristic.
        private const val GATHER_MIN_PX = 12
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 40

        /**
         * Trần số node ĐÃ DUYỆT cho MỘT lượt [gatherDescriptions] — xem KDoc hàm đó. [MAX_NODES] chặn output
         * chứ không chặn công đi cây, nên không có trần này thì chi phí một nhịp tỉ lệ với KÍCH THƯỚC CÂY của
         * app kia (ta không kiểm soát), chứ không tỉ lệ với thứ ta cần. THAM SỐ CHỌN, chưa đo trên xe: đủ rộng
         * để phủ bố cục VietMap đã đo (banner nằm nông), đủ hẹp để một cây bệnh lý không nuốt luồng main.
         */
        private const val MAX_VISIT_NODES = 1500
    }
}
