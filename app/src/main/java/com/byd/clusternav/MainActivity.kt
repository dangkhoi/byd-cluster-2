package com.byd.clusternav

import com.byd.clusternav.modules.clustercast.MainActivityCastController
import com.byd.clusternav.vietmapwidget.VietMapWidgetDiagActivity
import com.byd.clusternav.navigation.NavigationOutputFailureReason
import com.byd.clusternav.navigation.NavigationSourceReason
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.byd.clusternav.navigation.NavigationFreshness
import com.byd.clusternav.navigation.NavigationOutputStatus
import com.byd.clusternav.navigation.NavigationOutputTarget
import com.byd.clusternav.navigation.NavigationPermission
import com.byd.clusternav.navigation.NavReadChannel
import com.byd.clusternav.navigation.NavSourceLabels
import com.byd.clusternav.navigation.SpeedSignOutput

/**
 * Home — MÀN HÌNH DUY NHẤT của app (docs/specs/cast-simplified-active-app-toggle.html): trái là
 * Navigation + HUD (không đổi), phải là toàn bộ Cluster Cast (trước đây là màn riêng
 * `ClusterCastActivity`, đã xoá). Renderer/dispatcher — nó không tự lập kế hoạch gì cho Cast, mọi
 * mutation đi qua [MainActivityCastController].
 */
class MainActivity : Activity() {
    private lateinit var navEnabled: Switch
    private lateinit var navDot: View
    private lateinit var navStatus: TextView
    private lateinit var laneStatus: TextView
    private lateinit var hudStatus: TextView
    // T3 (b3-full-nav-capture · R2): shows SourceArbiter.activeSource (the nav source currently driving).
    private lateinit var navSourceActive: TextView
    private val cast = MainActivityCastController(this)
    private val navClusterStatus = com.byd.clusternav.modules.clustercast.NavClusterOp39Status(this)
    // Speed-sign owner: nhận giới hạn tốc độ từ widget VietMap → badge cụm + HAL 0x4B40001C.
    // (Comment cũ ghi "Port 1.21 = Noop" đã LỖI THỜI — đường này chạy thật, chính nó vẽ badge trên cụm.)
    private val speedSign by lazy { NavigationSpeedSignOwner.get(applicationContext) }

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); cast.tick(); ui.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // D1 (closeout 1.28): mirror the persisted verbose-log flag into the in-memory NavLog gate so per-frame
        // hot paths read a @Volatile field (no SharedPreferences per frame). Entry point that always runs.
        NavLog.init(this)

        // CLAUDE.md §9: mỗi bản đã báo cho user phải tự hiện số hiệu — không ai phải đoán xe đang chạy bản nào.
        val versionName = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull()
        val titleView = findViewById<TextView>(R.id.txt_app_title)
        titleView.text = "ClusterNav" + (versionName?.let { " · v$it" } ?: "")
        // D5 (closeout 1.28): HIDDEN verbose-log toggle — long-press the version label. It now ROUTES THROUGH
        // the visible "Thu thập dữ liệu chẩn đoán" switch so both stay in sync; the switch's listener persists
        // (Prefs), mirrors the live NavLog gate, trims storage on enable, and Toasts. No BuildConfig.DEBUG (OTA
        // ships a RELEASE apk); default OFF. Fallback flips directly if the switch view is somehow absent.
        titleView.setOnLongClickListener {
            val sw = findViewById<Switch>(R.id.switch_diag_logging)
            if (sw != null) sw.isChecked = !sw.isChecked else setDiagLogging(!NavLog.verbose)
            true
        }

        navEnabled = findViewById(R.id.switch_enabled)
        navDot = findViewById(R.id.dot_status)
        navStatus = findViewById(R.id.txt_status)
        laneStatus = findViewById(R.id.txt_lane_status)
        hudStatus = findViewById(R.id.txt_hud_status)
        // Cluster-lane output follows the master Navigation+HUD switch — the redundant cb_lane
        // checkbox is removed (owner 2026-08-11). Force lane ON once so it always tracks the master
        // (migrates anyone who had unchecked the old lane box).
        Prefs.setLane(this, true)
        cast.onCreate()
        speedSign.syncFromPrefs()

        navEnabled.isChecked = Prefs.enabled(this)
        navEnabled.setOnCheckedChangeListener { _, enabled ->
            Prefs.setEnabled(this, enabled)
            speedSign.onMasterEnabled(enabled)
            if (enabled) {
                // Lane always on when Navigation+HUD is on (no separate lane toggle anymore).
                Prefs.setLane(this, true)
                NavRepository.setOutputEnabled(this, NavigationOutputTarget.CLUSTER_LANE, true)
                speedSign.onOutputEnabled(SpeedSignOutput.CLUSTER, true)
                // Option B (1.13): user chủ động bật → GIỜ mới đụng adb. Thiếu quyền → tự cấp qua dadb;
                // đã có → chỉ ensure bind. (Mặc định TẮT nên onCreate không tự chạy nhánh này.)
                if (notificationAccessGranted()) {
                    NavConnect.ensureConnected(applicationContext)
                } else {
                    Toast.makeText(this, Lang.t("Đang cấp quyền đọc thông báo…", "Granting notification access…"), Toast.LENGTH_SHORT).show()
                    NavConnect.selfGrant(applicationContext) { ok ->
                        if (isFinishing) return@selfGrant
                        if (ok) refresh() else promptNotificationAccessFallback()
                    }
                }
                // Bộ đọc màn GMaps (screenRead ground-truth cho tinh chỉnh nội suy) + nút vật lý → trợ lý
                // cần accessibility service. Đây là quyền ADB (settings secure enabled_accessibility_services)
                // → tự cấp qua dadb như notification. Escalate khi THIẾU setting HOẶC enabled-nhưng-chưa-bound
                // (sau reboot: connected=false dù setting còn) — grantAccessibility verify dumpsys trước khi
                // toggle nên không flicker nếu đã bound. Trước đây chỉ UI voice-key cấp; voice-key gỡ đi →
                // grantAccessibility mồ côi → 2 chuyến screenRead RỖNG. Wire vào công tắc Nav+HUD để tự lành.
                if (!accessibilityBoosterGranted() || !com.byd.clusternav.modules.navaccess.NavAccessibilitySource.connected) NavConnect.grantAccessibility(applicationContext)
            } else {
                NavRepository.stop(this)
            }
            refresh()
        }
        // The master listener above only fires on CHANGE, so apply the current master state to the
        // cluster-lane output at startup too. The notification listener runs in THIS process and, on
        // bind, calls NavRepository.setPermission(GRANTED) → connect(), which may create the
        // coordinator before this Activity opens. A migrated user whose old cb_lane was unchecked has
        // lane=false persisted; the forced Prefs.setLane(true) above fixes the pref, but a coordinator
        // already built from the stale pref keeps CLUSTER_LANE OFF (connect() reads Prefs.lane only at
        // creation) and GMaps/VietMap nav would never reach the cluster. Re-assert it here (idempotent).
        if (Prefs.enabled(this)) {
            NavRepository.setOutputEnabled(this, NavigationOutputTarget.CLUSTER_LANE, true)
            speedSign.onOutputEnabled(SpeedSignOutput.CLUSTER, true)
        }
        // Chốt nguồn biển báo MỘT lần lúc khởi tạo. Trước 08-22 lời gọi này nằm trong listener của spinner
        // chọn nguồn; spinner đã gỡ (chỉ còn một nguồn thật) nên phải khẳng định tường minh ở đây, nếu không
        // owner chỉ được set nguồn qua syncFromPrefs/pusher và MainActivity không còn bảo đảm gì.
        speedSign.onSourceSelected(Prefs.speedLimitSource(this))
        // #6 (R1 · docs/specs/cast-nav-ux-release-v104.html): the independent nav→HUD output is
        // hidden from the UI (cb_hud/txt_hud_status = gone) and force-disabled here exactly once.
        // There is no user-reachable path to re-enable it. Navigation still flows to the cluster
        // lane (unchanged); NavigationOutputTarget.HUD stays in the enum for the isolation contract —
        // it is only kept OFF, not removed.
        Prefs.setHud(this, false)
        NavRepository.setOutputEnabled(this, NavigationOutputTarget.HUD, false)
        speedSign.onOutputEnabled(SpeedSignOutput.HUD, false)

        // ★ 2026-08-12 (owner "1B"): BẬT lại "tự bù theo tốc độ" cho mượt. MỘT cơ chế 2 nửa:
        //   (1) nội suy trừ dần cự ly theo TỐC ĐỘ XE thật giữa 2 notification (TurnDistanceInterpolator + SpeedProvider),
        //   (2) bộ đọc màn Maps (accessibility) kéo mốc về số thật (refine()).
        // Noti GMaps thưa → gửi RAW làm cụm "trễ khi tới ngã rẽ/điểm đến"; nội suy lấp khoảng giữa cho mượt.
        // Ép BẬT ở đây để migrate cả bản cài cũ từng bị ép TẮT (2026-07-13). Không có nút UI (giữ UI gọn);
        // muốn TẮT nếu overlay cụm tự animate rồi đánh nhau (verify trên xe) → đổi 2 dòng dưới thành false.
        Prefs.setInterpolate(this, true)
        Prefs.setAccBooster(this, true)

        // Nav-source selector (Auto/GMaps/Waze Mod/VietMap). Nguồn tốc độ không còn selector (chỉ VietMap).
        // Owner Q1 = revive tất cả. Waze-Mod nav-source chạy song song GMaps; speed-source chọn nguồn tín hiệu biển
        // báo. Xem docs/specs/waze-vietmap-signal-revival.html.
        // Navigation source selector (turn-by-turn direction)
        val navSourceSpinner = findViewById<android.widget.Spinner>(R.id.spinner_nav_source)
        // T3 (b3-full-nav-capture · R2): AUTO / GMaps / Waze / VietMap → Prefs.setSourceMode (SourceArbiter honours it).
        // Nhãn kèm NĂNG LỰC THẬT của từng nguồn (08-22) — ba nguồn KHÔNG ngang nhau, và trước đây menu
        // trình bày như nhau khiến người dùng chọn xong không hiểu vì sao cụm im:
        //   • Google Maps — notification: mũi tên + cự ly + đường, chạy NỀN hẳn (nguồn đầy đủ duy nhất).
        //   • VietMap     — notification cho đường + cự ly ở nền; MŨI TÊN chỉ có khi app hiển thị (capture).
        //   • Waze       — mục này là NHÓM {com.waze, com.chisadin.wazemod}, không phải một gói: hai bản
        //                    dùng chung bộ resource-id nên đọc y hệt nhau (đo aapt2 08-22). Cự ly + tên đường
        //                    + ETA đọc được qua view-id a11y; mũi tên vẫn cần app hiển thị (capture).
        val navSources = arrayOf(
            "Tự động (app dẫn trước)",
            "Google Maps — chạy nền, đủ mũi tên + cự ly",
            "Waze / Waze Mod — cần hiện trên màn chính hoặc cụm",
            // ĐÍNH CHÍNH 2026-08-23 (B3.44 + B3.48): nhãn cũ hứa "nền có cự ly" — SAI kể từ B3.44.
            // `NavApps.NOTIFICATION` nay CHỈ còn GMaps ⇒ VietMap không còn kênh nền nào cấp cự ly; cả mũi tên
            // lẫn cự ly đều đi đường ẢNH, tức app PHẢI hiển thị. Và từ B3.48, chọn đích danh VietMap mà
            // VietMap không dẫn thì cụm IM LẶNG (không nhường cho GMaps nữa) — nhãn phải nói đúng chuyện đó,
            // nếu không người dùng chọn xong sẽ không hiểu vì sao cụm trống.
            "VietMap — cần app hiện (mũi tên + cự ly qua ảnh)",
        )
        val navSourceModes = intArrayOf(Prefs.AUTO, Prefs.PREFER_GMAPS, Prefs.PREFER_WAZE, Prefs.PREFER_VIETMAP)
        navSourceSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, navSources)
        val currentNavMode = Prefs.sourceMode(this)
        navSourceSpinner.setSelection(navSourceModes.indexOf(currentNavMode).coerceAtLeast(0))
        navSourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                // B3.49 — ĐỔI MENU PHẢI CÓ HIỆU LỰC TỨC THÌ. Ghi prefs KHÔNG đủ: `NavOutputOwner.tick` không
                // đọc Prefs/SourceArbiter, nó bắn theo độ tươi của ScreenCaptureSignal (6 s) ⇒ mũi tên app cũ
                // còn nằm trên cụm tới 6 giây sau khi tài xế đã chọn app khác. NavSourceModeSwitch bỏ NGAY các
                // kênh ảnh mà cổng mode mới không cho phép — và CHỈ những kênh đó (xem KDoc: vì sao không
                // SourceArbiter.clear(), vì sao không ScreenCaptureSignal.clear()).
                // So với mode ĐANG LƯU là bắt buộc: Spinner bắn onItemSelected cả lúc setSelection() khi dựng
                // màn hình, chạy vô điều kiện = mỗi lần mở app lại xoá oan kênh của app đang dẫn.
                com.byd.clusternav.navigation.NavSourceModeSwitch.onModeSelected(
                    previousMode = Prefs.sourceMode(this@MainActivity),
                    selectedMode = navSourceModes[pos],
                    persist = { mode -> Prefs.setSourceMode(this@MainActivity, mode) },
                )
                refresh()   // reflect the mode change in the active-source line immediately
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        // Active nav source (SourceArbiter.activeSource) — kept current by refresh().
        navSourceActive = findViewById(R.id.txt_nav_source_active)

        // ── Nguồn tốc độ: BỎ selector (08-22) ─────────────────────────────────────────────────────
        // Chỉ còn MỘT nguồn có thật — widget VietMap (proven: data 50/60/70/80 + đếm lùi cự ly). Lựa chọn
        // "Waze Mod (HLP)" đã gỡ khỏi cả code lẫn UI: đo trên máy không có HUD BLE thì `logcat WazeHudLink`
        // trả 0 dòng khi Waze ĐANG dẫn, tức chọn nó = badge trắng im lặng, không báo gì cho người dùng.
        // Một selector chỉ có một lựa chọn thì không phải lựa chọn — bỏ hẳn cho khỏi hiểu nhầm.

        // Chế độ hiển thị nav trên CỤM — ghi SET_NAVI_SCREEN_STATUS_SET (0x4C10E015) qua NavigationHudOwner
        // (đọc pref mỗi frame → áp dụng LIVE khi đang dẫn). ⚠️ value↔menu OEM chưa map chắc: dò trên xe rồi chốt.
        val clusterModeSpinner = findViewById<android.widget.Spinner>(R.id.spinner_cluster_mode)
        // TASK 4 (R3 · docs/specs/clusternav-closeout-1.28.html): on-car only OFF ever changed anything — the 3
        // layout modes (Đơn giản/Toàn/Nhỏ) hit the no-root wall and all render the same centre. Reduce to ON/OFF
        // so there are no dead buttons. ON = NAV_SCREEN_SIMPLE (centre "Giữa + ETA"); OFF = NAV_SCREEN_OFF. The
        // FULL/SMALL constants stay in Prefs (BydHal.NAV_SCREEN_MODE_ON back-compat) but are no longer selectable.
        val clusterModes = arrayOf("Bật (Giữa + ETA)", "Tắt")
        val clusterModeValues = intArrayOf(Prefs.NAV_SCREEN_FULL, Prefs.NAV_SCREEN_OFF)
        clusterModeSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, clusterModes)
        // Migrate old prefs gracefully: any non-OFF stored value (incl. legacy FULL/SMALL) → index 0 (Bật);
        // OFF → index 1 (Tắt). Prefs.navClusterScreenMode already collapses FULL/SMALL→SIMPLE on read.
        clusterModeSpinner.setSelection(if (Prefs.navClusterScreenMode(this) == Prefs.NAV_SCREEN_OFF) 1 else 0)
        clusterModeSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                Prefs.setNavClusterScreenMode(this@MainActivity, clusterModeValues[pos])
                // I4 (1.14): áp NGAY (re-assert) thay vì chờ reboot / frame kế bị dedup nuốt.
                NavRepository.reapplyClusterMode(applicationContext)
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        // I2 (1.14): toggle marquee (chạy chữ tên đường dài). Mặc định BẬT (Prefs.marquee=true).
        findViewById<android.widget.CheckBox>(R.id.cb_marquee).also { cb ->
            cb.isChecked = Prefs.marquee(this)
            cb.setOnCheckedChangeListener { _, on -> Prefs.setMarquee(this, on) }
        }

        // Data-collection logging + screenshots (owner 2026-08-18) — MẶC ĐỊNH TẮT. Normal use collects NO data.
        // Set the checked state BEFORE attaching the listener so opening the app never fires setDiagLogging
        // (no spurious toast / storage scan). The hidden long-press on the version label routes through this
        // same switch, so the two controls always agree.
        findViewById<Switch>(R.id.switch_diag_logging)?.also { sw ->
            sw.isChecked = Prefs.navVerboseLog(this)
            sw.setOnCheckedChangeListener { _, on -> setDiagLogging(on) }
        }

        // 1.21 Item 1 (owner): "Tự khởi động nền" — nổ máy chỉ chạy setup nền (BootSetupService qua
        // RebindReceiver), KHÔNG bung MainActivity trên màn chính (né size-compat dudu). Mặc định BẬT; tắt →
        // giữ hành vi cũ (tự mở Home lúc nổ máy). Chỉ đổi hành vi lúc boot/OTA — mở app bằng icon vẫn như thường.
        findViewById<android.widget.CheckBox>(R.id.cb_headless_autostart).also { cb ->
            cb.isChecked = Prefs.headlessAutostart(this)
            cb.setOnCheckedChangeListener { _, on -> Prefs.setHeadlessAutostart(this, on) }
        }

        // ── Nút vật lý → Trợ lý giọng nói (switch + nút + cử chỉ + đích + học phím). Owner 2026-08-14:
        // map nút mic vô-lăng (NHẤN-GIỮ = keycode 328) → Kiki (ai.zalo.kiki.car). Chỉ "nuốt" đúng tổ hợp,
        // KHÔNG đổi chức năng gốc của nút. Service Hỗ trợ tự bật qua dadb khi bật công tắc. ──
        setupVoiceKeyControls()

        // Nav trên cụm chỉ còn op 39 "Giữa + ETA" (owner chốt 2026-08-12) — bỏ nút chọn mode + nút test.
        // Chỉ còn dòng trạng thái op39 (ASSERTED / Cast đang bật / chưa gửi được) để chẩn đoán.
        navClusterStatus.bind()

        findViewById<Button>(R.id.btn_reconnect_nav).setOnClickListener {
            if (notificationAccessGranted()) {
                NavConnect.reconnect(applicationContext)
                Toast.makeText(this, Lang.t("Đang kết nối lại nguồn dẫn đường…", "Reconnecting navigation source…"), Toast.LENGTH_SHORT).show()
            } else {
                // Quyền đọc thông báo là quyền ADB (settings secure enabled_notification_listeners) — KHÔNG cần
                // màn Settings (IVI khoá không mở được → toast hệ thống "không hỗ trợ hoạt động này"). Cấp THẲNG
                // qua dadb uid-shell (NavConnect.selfGrant, y như DashCast). Chỉ khi dadb lỗi mới hiện fallback.
                Toast.makeText(this, Lang.t("Đang cấp quyền đọc thông báo…", "Granting notification access…"), Toast.LENGTH_SHORT).show()
                NavConnect.selfGrant(applicationContext) { ok ->
                    if (isFinishing) return@selfGrant
                    if (ok) {
                        Toast.makeText(this, Lang.t("Đã cấp quyền — đã kết nối nguồn dẫn đường.", "Access granted — navigation connected."), Toast.LENGTH_SHORT).show()
                        refresh()
                    } else {
                        promptNotificationAccessFallback()
                    }
                }
            }
        }
        findViewById<Button>(R.id.btn_nav_stop).setOnClickListener {
            NavRepository.stop(applicationContext)
            refresh()
        }
        // ★ Revive: nút "Dữ liệu VietMap" mở VietMapWidgetDiagActivity (chẩn đoán widget speed-limit).
        findViewById<Button>(R.id.btn_vietmap_widget_diag).setOnClickListener {
            startActivity(Intent(this, VietMapWidgetDiagActivity::class.java))
        }
        findViewById<Button?>(R.id.btn_check_update)?.setOnClickListener {
            val btn = it as Button
            UpdateFlow.start(this) { text, _ -> btn.text = text }
        }

        // Option B (1.13): chỉ đụng adb khi Navigation+HUD đang BẬT. Mặc định TẮT → mở app KHÔNG chạy dadb
        // (tránh đua nhiều client dadb + popup Allow khi user chưa cần nav). Bật công tắc mới grant+connect.
        if (Prefs.enabled(this)) {
            NavConnect.ensureConnected(applicationContext)
            // Reboot leaves the accessibility service ENABLED in the setting but NOT BOUND (measured
            // 2026-08-14, docs/diagnostics/oncar-handoff-voicekey-2026-08-14.md §8) → onKeyEvent + screenRead
            // dead. accessibilityBoosterGranted() only reads the ENABLED setting, so it can't see that; the
            // in-process connected flag can. Escalate to the dadb grant when the setting is missing OR the
            // service is enabled-but-not-bound — grantAccessibility confirms via dumpsys before toggling
            // (no flicker if already bound), so a stale connected flag at cold start is harmless.
            if (!accessibilityBoosterGranted() || !com.byd.clusternav.modules.navaccess.NavAccessibilitySource.connected) NavConnect.grantAccessibility(applicationContext)
        }
        runCatching { RebindReceiver.scheduleWatchdog(applicationContext) }
        // Nút nổi + chiếu cụm chỉ khởi động khi master switch "Cluster Cast" đang BẬT (MẶC ĐỊNH TẮT —
        // nav-only là mặc định; cụm giữ native + nav hiện ngay, không projection/cong/đen). Tắt Cast ⇒
        // không start service (service cũng tự đứng xuống nếu bị boot khởi động). startForegroundService idempotent.
        runCatching {
            if (com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                    .coordinator(applicationContext).prefs.castEnabled()
            ) {
                startForegroundService(Intent(this, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java))
            }
        }
        refresh()

        // D5 (closeout 1.28): one-time first-launch disclaimer (no warranty / not affiliated with BYD / install
        // at your own risk). Shown once, guarded by Prefs.disclaimerShown.
        maybeShowDisclaimer()

        // B1 (owner 2026-08-19): "badge bật → VietMap tự chạy để widget có nguồn" — with the speed badge enabled,
        // start VietMap once so its home-widget (the badge's speed-limit source) has a live process. Degrade-safe.
        maybeAutoStartVietMap()
    }

    override fun onResume() {
        super.onResume()
        runCatching { RebindReceiver.rebind(applicationContext) }
        // Quay lại từ màn "Truy cập thông báo": vừa bật quyền nhưng listener chưa bind (firmware BYD
        // bỏ qua requestRebind) → ép bind qua dadb. Chỉ chạy khi ĐÃ có quyền mà CHƯA bound (rẻ, không
        // đụng nav đang chạy tốt).
        if (Prefs.enabled(this) && notificationAccessGranted() && !NavNotificationListener.connected) {
            NavConnect.ensureConnected(applicationContext)
        }
        cast.onResume()
        // Nút nổi hiện NGAY sau khi bật Cast + cấp quyền overlay, không cần mở lại app. onCreate() chỉ
        // start service khi overlay ĐÃ có; nếu user vừa cấp quyền ở màn hệ thống rồi quay lại, luồng về
        // đây qua onResume — start lại service để onStartCommand → showBubble() (idempotent, no-op nếu
        // bubble đã hiện). Service tự đứng xuống nếu Cast tắt hoặc overlay vẫn thiếu. runCatching để một
        // ROM thiếu Settings.canDrawOverlays không làm văng Home.
        runCatching {
            if (com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                    .coordinator(applicationContext).prefs.castEnabled() &&
                Settings.canDrawOverlays(this)
            ) {
                startForegroundService(Intent(this, com.byd.clusternav.modules.clustercast.FloatingBubbleService::class.java))
            }
        }
        ui.post(refresher)
    }

    override fun onPause() {
        ui.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onDestroy() {
        // Tránh rò Activity: VoiceKeyLearnBus là singleton (app-scoped) giữ lambda bắt `this`. Chỉ gỡ khi
        // FINISH thật (đóng app) — KHÔNG gỡ lúc config-change (isFinishing=false) để listener mà onCreate
        // của Activity mới vừa set không bị null oan. Genuine-finish thì không có Activity kế → gỡ = hết rò.
        if (isFinishing) com.byd.clusternav.modules.voicekey.VoiceKeyLearnBus.setListener(null)
        cast.onDestroy()
        super.onDestroy()
    }

    private fun refresh() {
        val permission = if (notificationAccessGranted()) NavigationPermission.GRANTED else NavigationPermission.MISSING
        NavRepository.setPermission(applicationContext, permission)
        val navigation = NavRepository.snapshot(applicationContext)
        val source = navigation.source
        val sourceText = when (val freshness = source.freshness) {
            // B3.57 — SOURCE-AWARE: đặt tên thương hiệu + KÊNH ĐỌC (GMaps = thông báo; VietMap/Waze = đọc màn
            // hình) thay cho package thô. Nhãn + kênh là logic THUẦN ở :core ([NavSourceLabels], test off-car);
            // ở đây chỉ dịch enum kênh sang câu cho người dùng.
            is NavigationFreshness.Fresh -> {
                val pkg = source.identity?.packageName
                val brand = source.identity?.displayName
                    ?: NavSourceLabels.sourceLabel(pkg).ifEmpty { Lang.t("Đang dẫn đường", "Navigating") }
                val channel = NavSourceLabels.readChannel(pkg).readable()
                if (channel.isEmpty()) brand else "$brand · $channel"
            }
            is NavigationFreshness.Stale -> Lang.t("Nguồn đã cũ", "Source stale") + " · ${freshness.reason.readable()}"
            is NavigationFreshness.Unknown -> when (permission) {
                NavigationPermission.MISSING -> getString(R.string.status_need_perm)
                else -> freshness.reason.readable()
            }
        }
        navStatus.text = sourceText
        navDot.tint(if (source.freshness is NavigationFreshness.Fresh) R.color.ok_green else if (permission == NavigationPermission.MISSING) R.color.err_red else R.color.warn_amber)
        laneStatus.text = "${Lang.t("Cụm", "Cluster")}: ${navigation.clusterLane.status.label()}"
        hudStatus.text = "HUD: ${navigation.hud.status.label()}"
        findViewById<Button>(R.id.btn_reconnect_nav).visibility =
            if (permission != NavigationPermission.GRANTED) View.VISIBLE else View.GONE
        // T3 (b3-full-nav-capture · R2): show the nav source currently driving (SourceArbiter.activeSource). The
        // arbiter is fed the WALL clock (System.currentTimeMillis()) by the notification/screen-capture sources,
        // so freshness is judged on the same clock. Null → "—"; a source past STALE_MS is marked "(cũ)/(stale)".
        val nowWall = System.currentTimeMillis()
        val activePkg = com.byd.clusternav.navigation.SourceArbiter.activeSource
        navSourceActive.text = if (activePkg == null) {
            Lang.t("Đang dẫn: —", "Active: —")
        } else {
            val label = NavSourceLabels.sourceLabel(activePkg)
            // B3.57 — kèm KÊNH ĐỌC (đọc màn hình / thông báo) để dòng "đang dẫn" phản ánh đúng nguồn ảnh/a11y.
            val channel = NavSourceLabels.readChannel(activePkg).readable()
            val stale = !com.byd.clusternav.navigation.SourceArbiter.isFresh(nowWall)
            Lang.t("Đang dẫn: ", "Active: ") + label +
                (if (channel.isNotEmpty()) " ($channel)" else "") +
                if (stale) Lang.t(" (cũ)", " (stale)") else ""
        }
        navClusterStatus.refresh()
        updateVoiceKeyLabel()
        // F3: quay lại màn hình phải thấy đúng danh sách gán đang lưu (vd vừa cài/gỡ app đích, hoặc màn
        // hình bị huỷ-dựng lại). Vẽ lại từ Prefs — không giữ bản sao trên UI.
        rebuildVoiceKeyBindingList()
    }

    private fun View.tint(color: Int) {
        backgroundTintList = ColorStateList.valueOf(getColor(color))
    }

    private fun NavigationOutputStatus.label(): String = when (this) {
        NavigationOutputStatus.OFF -> Lang.t("tắt", "off")
        NavigationOutputStatus.STARTING -> Lang.t("đang khởi động", "starting")
        NavigationOutputStatus.EMITTING -> Lang.t("đang gửi", "emitting")
        // Trạng thái này KHÔNG BAO GIỜ xảy ra khi chạy thật: `markDisplayVerified` chỉ được gọi từ test,
        // không có producer nào trong `:app`. Giữ nhánh cho `when` vét cạn, nhưng nói đúng cơ sở — theo Q1
        // (đóng ngày 2026-07-27) không có tín hiệu nào của Android xác nhận cụm đang hiện gì, nên chữ
        // "đã xác minh" ở đây sẽ là tuyên bố không ai đặt được.
        NavigationOutputStatus.DISPLAY_VERIFIED -> Lang.t("cụm báo đã nhận", "cluster acknowledged")
        NavigationOutputStatus.STALE -> Lang.t("đã cũ", "stale")
        is NavigationOutputStatus.FAULT -> Lang.t("lỗi: ${reason.readable()}", "error: ${reason.readable()}")
    }

    /**
     * Lý do nguồn dẫn đường, viết cho người đọc.
     *
     * Trước 2026-07-27 chỗ này in `reason.name.replace('_',' ').lowercase()`, nên trên màn tiếng Việt hiện
     * ra "no active session". Cùng lỗi đã sửa ở màn Cast trong ngày: tên hằng trong mã không phải câu cho
     * người dùng. `when` vét cạn nên thêm giá trị mới là trình dịch bắt ngay, không lặng lẽ rơi về tên thô.
     */
    private fun NavigationSourceReason.readable(): String = when (this) {
        // B3.57 — "quyền truy cập thông báo" là GRANT app cần để kết nối phễu đọc dẫn đường; NÓ gate cả đường
        // notification (GMaps) LẪN đường ảnh/a11y (VietMap/Waze qua `ingestContent`). Viết là "để đọc dẫn
        // đường" thay vì ngầm định notification là NGUỒN dữ liệu duy nhất (VietMap/Waze đọc màn hình).
        NavigationSourceReason.PERMISSION_UNKNOWN -> Lang.t("Chưa rõ quyền truy cập thông báo", "Notification access unknown")
        NavigationSourceReason.PERMISSION_MISSING -> Lang.t("Cần quyền truy cập thông báo để đọc dẫn đường", "Grant notification access to read navigation")
        NavigationSourceReason.NO_ACTIVE_SESSION -> Lang.t("Chưa có phiên dẫn đường", "No active navigation session")
        NavigationSourceReason.WAITING_FOR_FRAME -> Lang.t("Đang chờ dữ liệu đầu tiên", "Waiting for first data frame")
        NavigationSourceReason.PROCESS_REHYDRATED_UNVERIFIED -> Lang.t("App vừa khởi động lại, chưa xác nhận nguồn", "App just restarted, source unverified")
        NavigationSourceReason.FRAME_EXPIRED -> Lang.t("Dữ liệu quá hạn", "Data expired")
        NavigationSourceReason.SOURCE_DISCONNECTED -> Lang.t("Mất kết nối với app dẫn đường", "Navigation app disconnected")
        NavigationSourceReason.SOURCE_CHANGED -> Lang.t("Nguồn dẫn đường vừa đổi", "Navigation source changed")
    }

    /**
     * B3.57 — KÊNH ĐỌC của nguồn, viết cho người dùng. Nói rõ nguồn đang dẫn được đọc bằng THÔNG BÁO (GMaps)
     * hay ĐỌC MÀN HÌNH (VietMap/Waze qua a11y + chụp), để dòng trạng thái không còn ngầm định notification là
     * đường duy nhất. Phân loại thuần ở :core ([NavSourceLabels.readChannel]); [NavReadChannel.UNKNOWN] → "".
     */
    private fun NavReadChannel.readable(): String = when (this) {
        NavReadChannel.NOTIFICATION -> Lang.t("thông báo", "notification")
        NavReadChannel.SCREEN_READ -> Lang.t("đọc màn hình", "screen-read")
        NavReadChannel.UNKNOWN -> ""
    }

    /** Lý do đầu ra lỗi, viết cho người đọc — cùng lý do như trên. */
    private fun NavigationOutputFailureReason.readable(): String = when (this) {
        NavigationOutputFailureReason.DELIVERY_THROWN -> Lang.t("gửi thất bại", "delivery failed")
        NavigationOutputFailureReason.DEADLINE_EXCEEDED -> Lang.t("quá thời gian chờ", "deadline exceeded")
        NavigationOutputFailureReason.QUEUE_SATURATED -> Lang.t("hàng chờ đã đầy", "queue saturated")
        NavigationOutputFailureReason.EXECUTOR_REJECTED -> Lang.t("luồng gửi đã dừng", "executor rejected")
        NavigationOutputFailureReason.DISPLAY_ACK_REJECTED -> Lang.t("cụm từ chối xác nhận", "cluster acknowledgement rejected")
        NavigationOutputFailureReason.INTERNAL_CONTRACT_ERROR -> Lang.t("sai hợp đồng nội bộ", "internal contract error")
    }

    /**
     * Apply the diagnostic data-collection state (verbose logging + PNG dumps + screenshots): persist it,
     * mirror the live in-memory [NavLog.verbose] gate, and — when turning ON — trim the diagnostics dir to the
     * storage cap ([DiagStorageCap]) BEFORE a collection drive begins (so leftover data from a prior session
     * doesn't count against the budget). Single source of truth shared by the visible switch and the hidden
     * long-press. Default is OFF: normal use collects no data.
     */
    private fun setDiagLogging(on: Boolean) {
        Prefs.setNavVerboseLog(this, on)
        NavLog.verbose = on
        if (on) DiagStorageCap.enforce(this, force = true)
        Toast.makeText(
            this,
            Lang.t("Thu thập dữ liệu chẩn đoán: ", "Diagnostic data collection: ") +
                if (on) Lang.t("BẬT", "ON") else Lang.t("TẮT", "OFF"),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * D5 (closeout 1.28): one-time first-launch disclaimer — no warranty, not affiliated with BYD, install at
     * your own risk (bilingual VI+EN, concise). Guarded by [Prefs.disclaimerShown] so it shows exactly once; the
     * flag is set BEFORE show() so a config-change/dismiss can't re-trigger it. Reuses the AlertDialog pattern
     * already used for [promptNotificationAccessFallback] / [showLearnNameDialog].
     */
    private fun maybeShowDisclaimer() {
        if (isFinishing || Prefs.disclaimerShown(this)) return
        Prefs.setDisclaimerShown(this, true)
        android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("Miễn trừ trách nhiệm", "Disclaimer"))
            .setMessage(
                Lang.t(
                    "ClusterNav là thử nghiệm cá nhân, KHÔNG liên kết với BYD. Không có bảo đảm về an toàn lái xe, " +
                        "tương thích hay độ ổn định. Cài và dùng với rủi ro của riêng bạn.",
                    "ClusterNav is a personal experiment, NOT affiliated with BYD. It comes with no warranty of " +
                        "driving safety, compatibility, or reliability. Install and use at your own risk.",
                ),
            )
            .setPositiveButton(Lang.t("Tôi hiểu", "I understand"), null)
            .show()
    }

    /**
     * FALLBACK khi tự cấp quyền qua dadb THẤT BẠI (thường vì chưa bấm "Allow USB debugging" trên xe lần
     * đầu). Cho THỬ LẠI selfGrant, hoặc mở màn Settings hệ thống để bật tay (một số máy IVI không có màn này
     * → openNotificationAccessSettings tự toast hướng dẫn). Đây KHÔNG còn là đường chính: đường chính là
     * NavConnect.selfGrant (cấp qua dadb), gọi khi bấm nút / bật công tắc lúc thiếu quyền.
     */
    private fun promptNotificationAccessFallback() {
        if (isFinishing) return
        android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("Chưa cấp được quyền", "Couldn't grant access"))
            .setMessage(
                Lang.t(
                    "Chưa tự cấp được quyền đọc thông báo qua ADB nội bộ. Lần đầu cần bật gỡ lỗi USB: khi màn " +
                        "xe hiện hộp thoại “Allow USB debugging?”, bấm Allow rồi Thử lại.\n\nHoặc mở cài đặt hệ " +
                        "thống để bật ClusterNav thủ công (một số máy không có màn này).",
                    "Couldn't self-grant notification access over local ADB. First time, allow USB debugging: " +
                        "when the car screen shows “Allow USB debugging?”, tap Allow, then Retry.\n\nOr open " +
                        "system settings to enable ClusterNav manually (some units lack this screen).",
                ),
            )
            .setPositiveButton(Lang.t("Thử lại", "Retry")) { _, _ ->
                Toast.makeText(this, Lang.t("Đang cấp quyền…", "Granting…"), Toast.LENGTH_SHORT).show()
                NavConnect.selfGrant(applicationContext) { ok ->
                    if (isFinishing) return@selfGrant
                    if (ok) refresh()
                    else Toast.makeText(this, Lang.t("Vẫn chưa được — kiểm tra hộp thoại Allow trên xe.", "Still failed — check the Allow dialog on the car."), Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton(Lang.t("Mở cài đặt", "Open settings")) { _, _ -> openNotificationAccessSettings() }
            .setNegativeButton(Lang.t("Đóng", "Close"), null)
            .show()
    }

    /**
     * Điều hướng tới màn Notification-access theo thứ tự ưu tiên: deep-link thẳng entry ClusterNav
     * (API 30+) → màn danh sách → trang chi tiết app. Mọi bước bọc try để không văng nếu ROM thiếu
     * activity nào; hết đường thì toast hướng dẫn tay.
     */
    private fun openNotificationAccessSettings() {
        val comp = ComponentName(this, NavNotificationListener::class.java).flattenToString()
        if (android.os.Build.VERSION.SDK_INT >= 30 && tryStartActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, comp),
            )
        ) {
            return
        }
        if (tryStartActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))) return
        if (tryStartActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
        ) {
            return
        }
        Toast.makeText(
            this,
            Lang.t(
                "Không mở được cài đặt. Vào Cài đặt → Ứng dụng → Truy cập đặc biệt → Truy cập thông báo → bật ClusterNav.",
                "Couldn't open settings. Go to Settings → Apps → Special access → Notification access → enable ClusterNav.",
            ),
            Toast.LENGTH_LONG,
        ).show()
    }

    // ── Nút vật lý → Trợ lý giọng nói ───────────────────────────────────────────────────────────
    // Ứng viên keycode cho nút voice/steering. "Học phím" = sentinel -1: bấm nút THẬT trên xe để gán
    // keycode chưa biết. Nút mic vô-lăng trên xe này NHẤN-GIỮ = 328 (đo on-car 2026-08-13) → để sẵn làm
    // ứng viên đầu; nhấn ngắn phát mã KHÁC nên trợ lý gốc (小迪) giữ nguyên.
    // Preset keycode ứng viên (nút vô-lăng/táp-lô). Nút tự học thêm từ Prefs.voiceKeyCustomButtons.
    // Nút mic vô-lăng xe này giữ = 328 (đo on-car 2026-08-13); nhấn ngắn ra mã KHÁC nên native (小迪) giữ nguyên.
    private val voiceKeyPresets: List<Pair<String, Int>> = listOf(
        "Nút mic vô-lăng — giữ (328)" to 328,
        "Trợ lý giọng nói (VOICE_ASSIST · 231)" to 231,
        "Trợ lý (ASSIST · 219)" to 219,
        "Play/Pause (85)" to 85,
        "Bài trước (PREVIOUS · 88)" to 88,
        "Bài sau (NEXT · 87)" to 87,
        "Headset hook (79)" to 79,
        "Gọi (CALL · 5)" to 5,
        "Tìm kiếm (SEARCH · 84)" to 84,
    )
    /** Dropdown nút = preset + nút tự học (persist). */
    private fun voiceKeyButtonList(): List<Pair<String, Int>> = voiceKeyPresets + Prefs.voiceKeyCustomButtons(this)

    /**
     * Đích chọn được = 3 mục đặc biệt (ghim đầu) + mọi app có launcher. Dựng MỘT lần rồi dùng lại cho cả
     * dropdown lẫn nhãn từng dòng đã gán — hai nơi phải đọc CÙNG một bảng, nếu không dòng đã gán có thể
     * hiện tên khác với lúc chọn.
     */
    private val voiceKeyTargetSpecs: List<Pair<String, String>> by lazy {
        listOf(
            Lang.t("Trợ lý mặc định hệ thống", "System default assistant") to Prefs.VK_TARGET_ASSIST,
            Lang.t("Trợ lý qua phím cứng (Gemini · 231)", "System assistant via hard key (Gemini · 231)") to Prefs.VK_TARGET_GEMINI_KEY,
            Lang.t("Nhận dạng giọng nói", "Speech recognizer") to Prefs.VK_TARGET_RECOGNIZER,
        ) + com.byd.clusternav.modules.clustercast.ClusterCast.listInstalledApps(this).map { it.label to it.pkg }
    }

    /** Nhãn nút: ưu tiên tên trong dropdown (preset/tự học), không có thì dựng từ mã phím. */
    private fun voiceKeyButtonLabel(code: Int): String =
        voiceKeyButtonList().firstOrNull { it.second == code }?.first
            ?: (android.view.KeyEvent.keyCodeToString(code) + " ($code)")

    /** Nhãn đích: tên app trong bảng; app đã gỡ cài ⇒ hiện chính chuỗi spec để owner còn nhận ra mà xoá. */
    private fun voiceKeyTargetLabel(spec: String): String =
        voiceKeyTargetSpecs.firstOrNull { it.second == spec }?.first ?: spec

    /** Nhãn "nút ĐANG CHỌN trong dropdown" — F3: không còn khái niệm "nút hiện tại" vì gán được nhiều nút. */
    private fun updateVoiceKeyLabel() {
        val current = findViewById<TextView>(R.id.txt_voicekey_current) ?: return
        val kc = selectedVoiceKeyCode()
        current.text =
            if (kc == null) Lang.t("Nút đang chọn: —", "Selected button: —")
            else Lang.t("Nút đang chọn: ", "Selected button: ") + android.view.KeyEvent.keyCodeToString(kc) + " ($kc)"
    }

    private fun selectedVoiceKeyCode(): Int? {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_button) ?: return null
        return voiceKeyButtonList().getOrNull(spinner.selectedItemPosition)?.second
    }

    /**
     * (Re)nạp dropdown nút. [selectCode] = mã phím cần chọn sẵn (vd vừa học xong một nút mới).
     * Lựa chọn dropdown là trạng thái TẠM của màn hình — cấu hình thật nằm ở danh sách đã gán.
     */
    private fun rebuildVoiceKeyButtonSpinner(selectCode: Int? = null) {
        val spinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_button) ?: return
        // Đọc mã đang chọn TRƯỚC khi thay adapter: gán adapter mới reset lựa chọn về 0, nên nếu đọc sau thì
        // "giữ nguyên lựa chọn" luôn ra mục đầu — owner vừa xoá một nút tự học là nút đang chọn nhảy mất.
        val keep = selectCode ?: selectedVoiceKeyCode()
        val list = voiceKeyButtonList()
        spinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.first })
        spinner.setSelection(list.indexOfFirst { it.second == keep }.coerceAtLeast(0))
        updateVoiceKeyLabel()
    }

    /**
     * F3 — vẽ lại DANH SÁCH ĐÃ GÁN từ `Prefs.voiceKeyBindings` (đúng danh sách mà
     * `NavAccessibilityService.onKeyEvent` nghe theo, không phải một bản sao khác trên UI).
     * Rỗng ⇒ hiện dòng nhắc "chưa gán nút nào ⇒ không có gì chạy" (yêu cầu của owner).
     */
    private fun rebuildVoiceKeyBindingList() {
        val container = findViewById<android.widget.LinearLayout>(R.id.list_voicekey_bindings) ?: return
        val items = Prefs.voiceKeyBindings(this)
        findViewById<TextView>(R.id.txt_voicekey_empty)?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        container.removeAllViews()
        for (b in items) {
            val row = layoutInflater.inflate(R.layout.row_voicekey_binding, container, false)
            row.findViewById<TextView>(R.id.txt_binding_label).text =
                voiceKeyButtonLabel(b.keyCode) + "  →  " + voiceKeyTargetLabel(b.targetSpec)
            // Nhãn nút đặt LÚC CHẠY, không lấy chữ cứng trong XML — KDoc của row_voicekey_binding.xml nói rõ
            // chuỗi không vào strings.xml (file đó nằm trong danh sách canh chống-sửa-lén T11) nên song ngữ
            // phải do đây lo. Bản F3 đầu tiên quên, nên máy đặt tiếng Anh vẫn thấy nút "Xoá".
            row.findViewById<Button>(R.id.btn_binding_remove).apply {
                text = Lang.t("Xoá", "Remove")
                contentDescription = Lang.t("Xoá dòng gán này", "Remove this binding")
                setOnClickListener {
                    Prefs.removeVoiceKeyBinding(this@MainActivity, b.keyCode)
                    rebuildVoiceKeyBindingList()
                    Toast.makeText(this@MainActivity, Lang.t("Đã xoá gán", "Binding removed"), Toast.LENGTH_SHORT).show()
                }
            }
            container.addView(row)
        }
    }

    /** Sau khi service bắt keycode mới: hỏi tên → lưu nút custom → nạp lại dropdown + chọn. */
    private fun showLearnNameDialog(code: Int) {
        if (isFinishing || isDestroyed) return
        val default = android.view.KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").replace('_', ' ')
        val input = android.widget.EditText(this).apply { setText(default); setSelection(text.length) }
        android.app.AlertDialog.Builder(this)
            .setTitle(Lang.t("Đặt tên nút (mã $code)", "Name this button (code $code)"))
            .setView(input)
            .setPositiveButton(Lang.t("Lưu", "Save")) { _, _ ->
                // Học phím = thêm nút vào dropdown + chọn sẵn. CHƯA gán gì cả — owner còn phải chọn app rồi
                // bấm "Thêm gán" (F3). Trước F3 bước này ghi thẳng `voicekey_keycode`, tức học xong là đổi
                // luôn nút đang chạy; giờ cấu hình thật chỉ đổi khi owner bấm Thêm.
                val name = input.text.toString().ifBlank { default }
                Prefs.addVoiceKeyCustomButton(this, "$name (mã $code)", code)
                rebuildVoiceKeyButtonSpinner(code)
                Toast.makeText(
                    this,
                    Lang.t("Đã lưu nút. Chọn app rồi bấm “Thêm gán”.", "Button saved. Pick an app, then tap “Add binding”."),
                    Toast.LENGTH_LONG,
                ).show()
            }
            .setNegativeButton(Lang.t("Huỷ", "Cancel"), null)
            .show()
    }

    private fun setupVoiceKeyControls() {
        val vkSwitch = findViewById<Switch>(R.id.switch_voicekey_enabled)
        val targetSpinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_target)

        vkSwitch.isChecked = Prefs.voiceKeyEnabled(this)
        vkSwitch.setOnCheckedChangeListener { _, on ->
            Prefs.setVoiceKeyEnabled(this, on)
            // TASK 3 (R2 · docs/specs/clusternav-closeout-1.28.html): toggle OFF→ON RESETS the grant state +
            // re-requests the key bind (fresh grant + force-rebind) so the voice-key recovers after a reboot
            // WITHOUT an app restart. Do NOT gate on accessibilityBoosterGranted(): after a reboot the service
            // stays ENABLED-in-the-setting but NOT BOUND, so an enabled-only check would skip the heal. reset=true
            // clears a hung single-flight (grantingAcc) before attempting; grantAccessibility still force-rebinds
            // only when actually enabled-but-not-bound (no flicker if already bound). No auto-loop/backoff.
            if (on) {
                Toast.makeText(this, Lang.t("Đang bật dịch vụ Hỗ trợ…", "Enabling accessibility service…"), Toast.LENGTH_SHORT).show()
                NavConnect.grantAccessibility(applicationContext, reset = true) { ok ->
                    if (isFinishing) return@grantAccessibility
                    Toast.makeText(
                        this,
                        if (ok) Lang.t("Đã bật. Bấm nút đã gán để mở app.", "Enabled. Press the mapped button to open the app.")
                        else Lang.t("Chưa bật được Hỗ trợ — bấm Allow USB debugging trên xe rồi thử lại, hoặc bật tay ở Cài đặt > Hỗ trợ.", "Couldn't enable accessibility — tap Allow USB debugging on the car and retry, or enable it in Settings > Accessibility."),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }

        // Dropdown nút (preset + custom). F3: chọn nút KHÔNG còn ghi cấu hình — nó chỉ là bước 1 của
        // "chọn nút → chọn app → Thêm gán". Cấu hình thật chỉ đổi khi bấm Thêm/Xoá.
        rebuildVoiceKeyButtonSpinner()
        val btnSpinner = findViewById<android.widget.Spinner>(R.id.spinner_voicekey_button)
        btnSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) =
                updateVoiceKeyLabel()
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        // Nhấn-giữ 1 mục để XOÁ nút tự học (preset không xoá).
        //
        // ⚠️ [ĐO 2026-08-24] KHỐI NÀY LÀ CODE CHẾT — đã đọc source AOSP `android-10.0.0_r47`, không phải trí nhớ:
        //   • `Spinner.java` KHÔNG có một chỗ nào gọi `performItemLongClick` / `performLongClick` / đọc
        //     `mOnItemLongClickListener`; `onTouchEvent` chỉ chuyển cho `mForwardingListener` rồi `super`,
        //     và `performClick()` chỉ MỞ POPUP.
        //   • `AdapterView.java` chỉ CẤT listener (`setOnItemLongClickListener` set field + `setLongClickable(true)`)
        //     và KHÔNG override `performLongClick()` để phát tới nó. Bên phát thật là `AbsListView.performLongPress`,
        //     mà `Spinner` không kế thừa `AbsListView`.
        //   ⇒ Nhấn-giữ dropdown chỉ mở popup; lambda dưới CHƯA TỪNG chạy kể từ 1.19.
        //
        // Hệ quả: owner hiện KHÔNG có đường xoá một nút tự học (F3 làm nó lộ rõ hơn — nhãn dòng đã gán rơi về
        // `KEYCODE_x (mã)`). Đây là lỗi CÓ TỪ 1.19, KHÔNG phải hồi quy của F3, và cách chữa (đổi sang dialog
        // chọn-để-xoá, hay nút "Xoá nút này" cạnh dropdown) là THÊM giao diện ⇒ quyết định của owner, không
        // được tự ý làm trong phạm vi F3. Đã ghi backlog F4. Giữ nguyên khối này để không im lặng đổi hành vi;
        // KHÔNG được tin nó đang chạy.
        btnSpinner.onItemLongClickListener = android.widget.AdapterView.OnItemLongClickListener { _, _, pos, _ ->
            val item = voiceKeyButtonList().getOrNull(pos)
            if (item != null && Prefs.voiceKeyCustomButtons(this).any { it.second == item.second }) {
                Prefs.removeVoiceKeyCustomButton(this, item.second)
                rebuildVoiceKeyButtonSpinner()
                Toast.makeText(this, Lang.t("Đã xoá nút", "Button removed"), Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        // Nút "Học phím mới" — NGOÀI dropdown: bấm → chờ bấm nút vật lý → hiện ô đặt tên.
        findViewById<Button>(R.id.btn_voicekey_learn).setOnClickListener {
            Prefs.setVoiceKeyLearn(this, true)
            if (!accessibilityBoosterGranted()) NavConnect.grantAccessibility(applicationContext)
            Toast.makeText(this, Lang.t("Giữ màn hình này mở rồi bấm nút vật lý muốn dùng…", "Keep this screen open, then press the physical button…"), Toast.LENGTH_LONG).show()
        }

        // Đích = 3 mục đặc biệt (ghim đầu) + toàn bộ app có launcher (reuse ClusterCast.listInstalledApps).
        // F3: chọn app cũng KHÔNG ghi cấu hình — chỉ là bước 2. Không listener nào ở đây nữa.
        targetSpinner.adapter =
            android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voiceKeyTargetSpecs.map { it.first })

        // F3 — "3 · Thêm gán": ghi cặp (nút đang chọn → app đang chọn) vào danh sách. Đây là NƠI DUY NHẤT
        // ghi cấu hình gán, nên cũng là nơi chạy công thức "đặt trợ lý hệ thống = Google/Gemini" (trước F3
        // nằm ở listener của dropdown app — chạy cả khi owner chỉ lướt qua mục đó mà không gán gì).
        findViewById<Button>(R.id.btn_voicekey_add).setOnClickListener {
            val kc = selectedVoiceKeyCode()
            val target = voiceKeyTargetSpecs.getOrNull(targetSpinner.selectedItemPosition)
            if (kc == null || target == null) {
                Toast.makeText(this, Lang.t("Chọn nút và app trước đã.", "Pick a button and an app first."), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val replaced = Prefs.addVoiceKeyBinding(this, kc, target.second)
            rebuildVoiceKeyBindingList()
            val msg = when {
                replaced == null ->
                    Lang.t("Đã thêm: ", "Added: ") + voiceKeyButtonLabel(kc) + " → " + target.first
                replaced == target.second ->
                    Lang.t("Đã có sẵn: ", "Already set: ") + voiceKeyButtonLabel(kc) + " → " + target.first
                // GHI ĐÈ — báo rõ thay cái gì, cấm im lặng (một mã phím chỉ gán một app).
                else -> Lang.t("Nút này đã gán ", "This button was bound to ") + voiceKeyTargetLabel(replaced) +
                    Lang.t(" → đã THAY bằng ", " → REPLACED with ") + target.first
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

            // Chọn Gemini (sentinel 231 HOẶC lỡ chọn thẳng app Gemini/Google) → đặt luôn trợ lý hệ thống = Google/Gemini
            // (full recipe 8hare, một lần) để keyevent 231 mở Gemini dạng ASSISTANT (voice), không phải app home.
            // `replaced != target.second` ⇒ CHỈ chạy khi cấu hình thật sự đổi. Bấm Thêm lại đúng cặp đang có
            // (nhánh "Đã có sẵn") thì không đổi gì cả, mà công thức này bung một thread + một phiên dadb +
            // 2 Toast — đúng kiểu tác dụng phụ chạy oan mà F3 vừa dời khỏi listener dropdown để tránh.
            if (replaced != target.second &&
                com.byd.clusternav.modules.voicekey.AssistantLauncher.isGeminiVoiceSpec(target.second)
            ) {
                Toast.makeText(this, Lang.t("Đang đặt Gemini làm trợ lý hệ thống…", "Setting Gemini as system assistant…"), Toast.LENGTH_SHORT).show()
                Thread {
                    val err = com.byd.clusternav.modules.voicekey.AssistantLauncher.setSystemAssistant(this@MainActivity)
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        Toast.makeText(this@MainActivity,
                            if (err.isEmpty()) Lang.t("Đã đặt trợ lý = Google/Gemini. Giữ nút mic để NÓI (không mở app).", "Assistant set to Google/Gemini. Long-press mic to TALK (not open app).")
                            else err,
                            Toast.LENGTH_LONG).show()
                    }
                }.start()
            }
        }

        rebuildVoiceKeyBindingList()

        // F4e (bug owner 08-25): hold-mic → Gemini KHÔNG work lúc mở app, phải xoá+add lại mới chạy. Vì đích
        // Gemini đi `keyevent 231` (route tới TRỢ LÝ HỆ THỐNG) nên cần `setSystemAssistant` chạy TRƯỚC — mà
        // từ F3, recipe đó CHỈ chạy ở nút "Thêm". Mở app / sau reboot (ROM đặt lại trợ lý về 小迪) thì trợ lý
        // chưa phải Gemini ⇒ 231 route sai. (Kiki mở app THẲNG nên không dính — owner đo: Kiki OK ngay.)
        maybeReapplyGeminiAssistant()

        // Cầu học-phím: service bắt keycode → hiện dialog đặt tên (Activity foreground).
        com.byd.clusternav.modules.voicekey.VoiceKeyLearnBus.setListener { code -> runOnUiThread { showLearnNameDialog(code) } }
    }

    /**
     * F4e — đặt lại **trợ lý hệ thống = Google/Gemini** lúc mở app NẾU có ít nhất một binding trỏ Gemini
     * (sentinel 231 / bard / GSA). Để hold-mic → Gemini work NGAY, không phải xoá+add lại (recipe của nút
     * "Thêm" chỉ chạy khi cấu hình ĐỔI). Giống accessibility-booster self-grant (v1.18): idempotent, chạy
     * NỀN (dadb ~1-2s, degrade-safe), CHỈ khi thật sự có binding Gemini (owner chỉ dùng Kiki thì không đụng).
     */
    private fun maybeReapplyGeminiAssistant() {
        if (!com.byd.clusternav.modules.voicekey.AssistantLauncher.hasGeminiBinding(this)) return
        Thread {
            // App-open: owner đang nhìn màn hình ⇒ setSystemAssistant dùng retry mặc định AWAIT_ADB_APPROVAL.
            val err = runCatching {
                com.byd.clusternav.modules.voicekey.AssistantLauncher.setSystemAssistant(this@MainActivity)
            }.getOrElse { "" }
            if (err.isNotEmpty()) android.util.Log.w("MainActivity", "re-apply Gemini assistant lúc mở app: $err")
        }.start()
    }

    private fun tryStartActivity(intent: Intent): Boolean =
        runCatching { startActivity(intent); true }.getOrDefault(false)

    private fun notificationAccessGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        val expected = ComponentName(this, NavNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }

    /**
     * Accessibility booster (đọc màn GMaps → screenRead ground-truth) đã được bật chưa. Đọc THẲNG secure
     * setting (mọi app đọc được — KHÔNG cần dadb), y như [notificationAccessGranted]. Chỉ khi thiếu mới gọi
     * [NavConnect.grantAccessibility] (dadb) để append → tránh mở phiên dadb thừa mỗi lần bật Nav+HUD / mở app.
     */
    private fun accessibilityBoosterGranted(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_accessibility_services") ?: return false
        val expected = ComponentName(this, com.byd.clusternav.modules.navaccess.NavAccessibilityService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it.trim()) == expected }
    }

    /**
     * B1 (owner 2026-08-19, SỬA 2026-08-21 sau test on-car): "badge bật → VietMap tự chạy để widget có nguồn speed-limit".
     * Sửa 2 bug: (1) guard cũ `isAppForeground` dùng `runningAppProcesses` — Android 10+ chỉ thấy process của CHÍNH mình
     * → luôn trả false → LUÔN relaunch VietMap dù đã chạy; (2) `startActivity(VietMap)` → VietMap ĐÈ lên app mình.
     * Nay: kiểm VietMap chạy chưa bằng `pidof` qua dadb (uid shell = tin cậy cross-app); CHỈ start khi CHƯA chạy;
     * sau khi start thì đưa ClusterNav lại foreground (relaunch launcher qua shell — không BAL-block, không recreate)
     * để VietMap KHÔNG đè. Chạy nền, degrade-safe. Gọi ở CUỐI [onCreate] (chỉ khi tạo mới).
     */
    private fun maybeAutoStartVietMap() {
        // Case MỞ APP: start VietMap nếu chưa chạy, rồi đưa ClusterNav lại trước. (Boot headless → BootSetupService.)
        VietMapAutostart.ensureRunning(this, returnToSelfPkg = packageName)
    }
}
