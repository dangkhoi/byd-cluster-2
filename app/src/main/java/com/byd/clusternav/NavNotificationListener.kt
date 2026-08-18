package com.byd.clusternav

import com.byd.clusternav.navigation.ManeuverHold
import com.byd.clusternav.navigation.NavArrivalGuard
import com.byd.clusternav.navigation.NavFormat
import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.SegmentShotDecision
import com.byd.clusternav.navigation.SourceArbiter
import com.byd.clusternav.navigation.TurnDistanceInterpolator
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.navigation.NavigationPermission
import com.byd.clusternav.contracts.SpeedLimitSource
import com.byd.clusternav.vietmapwidget.VietMapWidgetBridge
import com.byd.clusternav.vietmapwidget.VietMapWidgetFreshness
import com.byd.clusternav.vietmapwidget.VietMapWidgetOwner
import com.byd.clusternav.modules.wazehud.WazeHudSource
import com.byd.clusternav.modules.wazehud.WazeHudAvailability
import com.byd.clusternav.modules.clustercast.ClusterNavLaneWidget

/**
 * Adapter MỎNG cho notification dẫn đường (Google Maps / ReVanced). Chỉ làm:
 *   gate (đúng app + Prefs.enabled + ongoing) -> rút field thô + bitmap (lazy) -> hỏi SourceArbiter ->
 *   NotificationParser dựng NavState -> fan-out ClusterBroadcaster (làn nav zin) + NavRepository (card).
 */
class NavNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NavListener"
        // TRẠNG THÁI BIND: true khi hệ thống đã bind service (onListenerConnected). Dùng để auto-reconnect lúc mở
        // app: chỉ chạy dadb disallow/allow khi CHƯA bound (tránh ngắt kết nối đang chạy tốt).
        @Volatile var connected = false
        // Token cự ly (m/km/ft/mi) — dấu hiệu noti dẫn đường, dùng khi category không phải navigation.
        private val DIST_TOKEN = Regex("""\b\d+([.,]\d+)?\s?(m|km|ft|mi)\b""", RegexOption.IGNORE_CASE)
        // "Đã đến nơi" — phát hiện KẾT-THÚC-NAV dùng chung ở NavArrivalGuard.isArrivalText (R7/#2).
        val MAPS_PACKAGES = setOf(
            "com.google.android.apps.maps",
            "app.revanced.android.apps.maps",
            // ★ Revive: NotificationParser đã biết đọc field-đảo của VietMap (đường ở title, cự ly ở text —
            // xem NotificationParser.kt) từ trước. Gói xác nhận qua dump thật (WmParseTest: "vn.vietmap.live/.MainActivity").
            "vn.vietmap.live",
            // WazeMod — HUD signal source, dùng song song GMaps
            "com.chisadin.wazemod",
            "com.waze",
        )
    }

    // ★ Revive (2026-08-17): speed-sign owner (VietMap/Waze speed-limit signal). Port ở 1.21 = Noop (chưa chạy) —
    // đây là base research (xem NavigationSpeedSignOwner + docs/specs/waze-vietmap-signal-revival.html).
    private val speedSignOwner by lazy { NavigationSpeedSignOwner.get(applicationContext) }

    /**
     * R7 (#2): per-session arrival + distance-regression guard (pure logic in :core). Reset on
     * STOP / arrival / notification removal so each route starts clean.
     */
    private val arrivalGuard = NavArrivalGuard()

    // Giữ hướng rẽ hợp lệ gần nhất TRONG PHIÊN (chống nháy HUD, owner 2026-08-15): 1 noti GMaps lỡ không đọc
    // được arrow → dùng lại hướng trước thay vì rớt straight. Reset ở ranh giới phiên (đến nơi / gỡ noti).
    @Volatile private var lastManeuverIcon: Int = -1

    // D4 (closeout 1.28): last-logged dist|road|eta for log-on-change on the accepted-notification log — kills
    // per-notification spam while keeping a low-rate signal. Reset at session boundaries (like lastManeuverIcon).
    @Volatile private var lastNavLogKey: String? = null

    // RAW-notif capture collapse (diagnostic only): last raw (title\u0001text\u0001sub\u0001big) per package.
    // Touched ONLY on the listener callback thread (onNotificationPosted + the onListenerConnected
    // activeNotifications scan both run on the main looper), so a plain HashMap with no lock is safe. Skips
    // CONSECUTIVE-IDENTICAL notifs (GMaps redraws the same frame ~1/s) so the raw CSV isn't flooded. Bounded to
    // the five nav packages. NOT reset at session boundaries — it never feeds the cluster, purely a flood guard.
    private val lastRaw = HashMap<String, String>()

    /** Hệ thống THẢ binding (head-unit hay làm lúc chạy) → clear typed sources before teardown. */
    override fun onListenerDisconnected() {
        connected = false
        // TÍN HIỆU DƯƠNG "nguồn dừng" (B1 Lỗ 2, handoff 2026-08-15): binding rớt = KHÔNG còn noti feed nữa →
        // dừng phiên authoritative để hudOwner.stop() HUỶ nhịp keep-alive; nếu thiếu, nhịp tim + frame cũ bị
        // ghim vô hạn tới khi mở lại app (mũi tên sai mà cụm vẫn "tự tin"). Cùng shape với nhánh
        // onNotificationRemoved: stop() + ClusterNavLaneWidget.onNavIdle() + log, bọc runCatching như các
        // nhánh teardown khác trong hàm này.
        runCatching {
            // Ranh giới phiên = "rớt binding" (KDoc ManeuverHold + NavArrivalGuard): reset state per-phiên như
            // nhánh onNotificationRemoved/arrival để tuyến MỚI sau rebind không kế thừa hướng rẽ / mốc cự ly cũ.
            arrivalGuard.reset(); lastManeuverIcon = -1; lastNavLogKey = null
            NavRepository.stop(applicationContext)
            ClusterNavLaneWidget.onNavIdle()
            Log.i(TAG, "binding thả -> stop authoritative session (nguồn dừng)")
        }.onFailure { Log.e(TAG, "stop on disconnect failed", it) }
        // ★ Revive: teardown tín hiệu (speed-sign + VietMap widget bridge + Waze HUD poll) — cô lập trong runCatching
        // để KHÔNG chặn keep-alive stop ở trên (B1 1.30) nếu nguồn tín hiệu ném lỗi.
        runCatching {
            speedSignOwner.onProviderDisconnected(SpeedLimitSource.VIETMAP)
            speedSignOwner.onProviderDisconnected(SpeedLimitSource.WAZE)
            stopWazeHudSource(clearFirst = false)
            val bridge = VietMapWidgetBridge.get(applicationContext)
            bridge.stop(VietMapWidgetOwner.NAVIGATION)
            bridge.removeListener(speedLimitPusher)
        }.onFailure { Log.e(TAG, "signal teardown on disconnect failed", it) }
        runCatching { NavRepository.setPermission(applicationContext, NavigationPermission.UNKNOWN) }
            .onFailure { Log.e(TAG, "permission state update failed", it) }
        runCatching {
            requestRebind(android.content.ComponentName(this, NavNotificationListener::class.java))
        }.onFailure { Log.e(TAG, "requestRebind on disconnect failed", it) }
    }

    /** Khi (re)cấp quyền / service bind lại: clear cờ kẹt + QUÉT noti đang hiện (nav có thể đã chạy trước). */
    override fun onListenerConnected() {
        connected = true
        // D1 (closeout 1.28): entry point that always runs on (re)bind → refresh the in-memory verbose gate
        // (set BEFORE the enabled early-return so the gate is correct even while Nav+HUD is OFF).
        NavLog.init(applicationContext)
        // Storage cap (defensive, always-on): trim the app-external diagnostics dir to the ~150 MB cap at every
        // session start. force=true bypasses the throttle so it always runs on connect. This bounds a long
        // verbose drive AND cleans data a previous verbose session left behind even if verbose is now OFF, so a
        // data-collection build can never fill the car's storage. Off-thread + degrade-safe (never throws).
        runCatching { DiagStorageCap.enforce(applicationContext, force = true) }
        // ★ Revive: khởi động nguồn tín hiệu (speed-sign sync + VietMap widget bridge + Waze HUD logcat poll).
        // GIỮ hành vi 1.21: chạy TRƯỚC cổng Prefs.enabled (nguồn poll độc lập). ⚠️ Waze poll logcat ~4000×/h
        // (hao pin — bản chất 1.21); base research, tối ưu sau (spec revival Q4). Cô lập trong runCatching.
        runCatching {
            speedSignOwner.syncFromPrefs()
            val bridge = VietMapWidgetBridge.get(applicationContext)
            bridge.start(VietMapWidgetOwner.NAVIGATION)
            bridge.addListener(speedLimitPusher)
            startWazeHudSource()
        }.onFailure { Log.e(TAG, "signal source start failed", it) }
        if (!Prefs.enabled(applicationContext)) return
        SourceArbiter.clear()
        runCatching { NavRepository.setPermission(applicationContext, NavigationPermission.GRANTED) }
            .onFailure { Log.e(TAG, "coordinator connect failed", it) }
        Log.i(TAG, "listener connected -> authoritative coordinator ready")
        // QUAN TRỌNG: nav có thể ĐÃ dẫn trước khi listener bind (cài/mở app sau khi đang dẫn, hoặc xe đỗ
        // -> noti đứng yên, onNotificationPosted không kích hoạt). Quét noti hiện tại + bơm ngay.
        runCatching {
            activeNotifications?.forEach { sbn ->
                if (sbn.packageName in MAPS_PACKAGES) handle(sbn)
            }
        }.onFailure { Log.e(TAG, "scan active notifications failed", it) }
    }

    override fun onDestroy() {
        connected = false
        // ★ Revive: teardown tín hiệu (cô lập).
        runCatching {
            speedSignOwner.onSourceStopped(SpeedLimitSource.VIETMAP)
            speedSignOwner.onSourceStopped(SpeedLimitSource.WAZE)
            stopWazeHudSource(clearFirst = false)
            val bridge = VietMapWidgetBridge.get(applicationContext)
            bridge.stop(VietMapWidgetOwner.NAVIGATION)
            bridge.removeListener(speedLimitPusher)
        }.onFailure { Log.e(TAG, "signal teardown on destroy failed", it) }
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MAPS_PACKAGES) return
        if (!Prefs.enabled(applicationContext)) return        // công tắc tổng TẮT -> không đẩy cụm
        // Safety net: ensure the connected flag is set even if onListenerConnected was not re-fired after process restart
        ensureBridgeStarted()
        runCatching { handle(sbn) }.onFailure { Log.e(TAG, "handle failed", it) }
    }

    private fun ensureBridgeStarted() {
        if (connected) return
        connected = true
        // ★ Revive: an toàn khởi động nguồn tín hiệu nếu onListenerConnected chưa (re)fire sau khi process restart.
        runCatching {
            val bridge = VietMapWidgetBridge.get(applicationContext)
            bridge.start(VietMapWidgetOwner.NAVIGATION)
            bridge.addListener(speedLimitPusher)
            startWazeHudSource()
        }.onFailure { Log.e(TAG, "signal source start (safety net) failed", it) }
        Log.i(TAG, "listener connected (safety net from onNotificationPosted)")
    }

    // ─── ★ Revive (2026-08-17): nguồn tín hiệu speed-limit (VietMap widget push + Waze HUD logcat poll) ───
    // Bản chất 1.21: speed ports = Noop (do-nothing), WazeHudSource poll logcat qua dadb-shell ~4000×/giờ (hao pin).
    // Đây là base research — "làm nó chạy thật" (port HAL, bỏ poll) là feature riêng sau (spec revival Q4).
    private val speedLimitPusher: (com.byd.clusternav.vietmapwidget.VietMapWidgetSnapshot) -> Unit = { snapshot ->
        speedSignOwner.onSourceSelected(Prefs.speedLimitSource(applicationContext))
        if (snapshot.speedFreshness == VietMapWidgetFreshness.FRESH) {
            speedSignOwner.onSpeedLimit(
                source = SpeedLimitSource.VIETMAP,
                valueKph = snapshot.speedLimitKph ?: 0,
                observedAtMonotonicMs = snapshot.speedUpdatedAtElapsedMs ?: SystemClock.elapsedRealtime(),
            )
        } else {
            speedSignOwner.onProviderDisconnected(SpeedLimitSource.VIETMAP)
        }
        // ── Upcoming speed-limit badge (spec upcoming-speed-limit-badge, ADDITIVE) ──────────────────────
        // Mirror VietMap's "speed-limit ahead" (ALERT_FULL slot) onto a smaller badge + countdown BELOW the
        // main badge on the cluster. Pure decision in :core (UpcomingBadgeDecision) — OQ2: no own distance
        // threshold, show exactly when VietMap shows a FRESH upcoming limit; hide when null/stale/reached.
        // Gated by the user toggle Prefs.showUpcomingBadge (default ON). Degrade-safe (never throws into the feed).
        runCatching {
            if (Prefs.showUpcomingBadge(applicationContext)) {
                val d = com.byd.clusternav.navigation.UpcomingBadgeDecision.decide(
                    limitKph = snapshot.upcomingLimitKph,
                    distanceMeters = snapshot.upcomingDistanceMeters,
                    fresh = snapshot.alertFullFreshness == VietMapWidgetFreshness.FRESH,
                )
                if (d.show) {
                    speedSignOwner.setUpcomingBadge(d.limitKph, d.distanceMeters, snapshot.upcomingDistanceText)
                } else {
                    speedSignOwner.setUpcomingBadge(null, null, null)
                }
            } else {
                speedSignOwner.setUpcomingBadge(null, null, null)
            }
        }.onFailure { Log.w(TAG, "upcoming badge push failed", it) }
    }

    private var wazeHudSource: WazeHudSource? = null

    private fun startWazeHudSource() {
        if (wazeHudSource != null) return
        // Read logcat via the privileged dadb shell (uid 2000). An app-uid `logcat` cannot see
        // WazeMod's logs without effective READ_LOGS; the shell has full log access (see WazeHudSource).
        val source = WazeHudSource { cmd ->
            runCatching {
                val r = com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                    .coordinator(applicationContext).executeShell(cmd)
                if (r.success) r.stdout else null
            }.getOrNull()
        }
        source.availabilityListener = { availability ->
            when (availability) {
                WazeHudAvailability.AVAILABLE -> Unit
                WazeHudAvailability.UNAVAILABLE ->
                    speedSignOwner.onProviderDisconnected(SpeedLimitSource.WAZE)
                WazeHudAvailability.STOPPED ->
                    speedSignOwner.onSourceStopped(SpeedLimitSource.WAZE)
            }
        }
        source.listener = listener@{ state ->
            val ctx = applicationContext
            val masterEnabled = Prefs.enabled(ctx)
            speedSignOwner.onMasterEnabled(masterEnabled)
            speedSignOwner.onSourceSelected(Prefs.speedLimitSource(ctx))

            // Navigation requires an active route; speed acquisition below remains route-independent.
            if (masterEnabled && state.navigating) {
                val navMode = Prefs.sourceMode(ctx)
                if ((navMode == Prefs.PREFER_WAZE || navMode == Prefs.AUTO) &&
                    SourceArbiter.shouldFeed("com.chisadin.wazemod", navMode, System.currentTimeMillis())) {
                    ClusterBroadcaster.selectSource("com.chisadin.wazemod")
                    val navState = source.toNavState(state)
                    ClusterBroadcaster.emitLane(ctx, navState)
                    ClusterBroadcaster.emitHud(ctx, navState)
                    ClusterNavLaneWidget.onNavActive(ctx)
                }
            }

            // HLP lim=0/missing is a real clear event. Never gate speed on `navigating`.
            speedSignOwner.onSpeedLimit(
                source = SpeedLimitSource.WAZE,
                valueKph = state.speedLimitKmh,
                observedAtMonotonicMs = SystemClock.elapsedRealtime(),
            )
        }
        source.start()
        wazeHudSource = source
        Log.i(TAG, "WazeHudLink logcat source started (dadb-shell poll)")
    }

    private fun stopWazeHudSource(clearFirst: Boolean = true) {
        val source = wazeHudSource ?: return
        if (clearFirst) speedSignOwner.onSourceStopped(SpeedLimitSource.WAZE)
        source.listener = null
        source.availabilityListener = null
        source.stop()
        wazeHudSource = null
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in MAPS_PACKAGES) return
        // R-2: CHỈ xử lý khi noti bị gỡ đúng là noti DẪN ĐƯỜNG (category=navigation HOẶC có token cự ly) — mirror gate
        // ingest (L~100). Bỏ FLAG_ONGOING (B4, nhiều build không đặt) nhưng KHÔNG được tắt nav khi gỡ noti Maps KHÁC
        // (chia sẻ vị trí / commute / Assistant / lưu chỗ đỗ...) — trước đây cờ ongoing lọc hộ, giờ lọc bằng nav-content.
        run {
            val n = sbn.notification
            val ex = n?.extras
            val t = ex?.getCharSequence("android.title")?.toString().orEmpty()
            val x = ex?.getCharSequence("android.text")?.toString().orEmpty()
            val isNav = n?.category == Notification.CATEGORY_NAVIGATION
            val hasDist = DIST_TOKEN.containsMatchIn(t) || DIST_TOKEN.containsMatchIn(x)
            // PHẢI tính cả ARRIVAL: noti "Đã đến" (build ReVanced KHÔNG set category, arrival KHÔNG có m/km) chính là
            // tín hiệu KẾT-THÚC-NAV để idle cụm — nếu chỉ isNav||hasDist thì nó bị nuốt → cụm kẹt icon-đích 3' (STALE_MS).
            val isArrival = NavArrivalGuard.isArrivalText(t, x)
            if (!isNav && !hasDist && !isArrival) return
        }
        // CHỈ tắt cụm nếu app vừa-gỡ chính là nguồn đang giữ khoá — gỡ noti app nền KHÔNG tắt nav app đang dẫn.
        if (SourceArbiter.release(sbn.packageName)) {
            arrivalGuard.reset(); lastManeuverIcon = -1; lastNavLogKey = null
            NavRepository.stop(applicationContext)
            ClusterNavLaneWidget.onNavIdle()
            Log.i(TAG, "nguồn ${sbn.packageName} dừng -> stop authoritative session")
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        val ex = n.extras ?: return
        val title = ex.getCharSequence("android.title")?.toString()?.trim().orEmpty()
        val text = ex.getCharSequence("android.text")?.toString()?.trim().orEmpty()
        val sub = ex.getCharSequence("android.subText")?.toString()?.trim().orEmpty()
        val big = ex.getCharSequence("android.bigText")?.toString()?.trim().orEmpty()
        // RAW notif capture (T2b, diagnostic + ADDITIVE — runs BEFORE the empty-title/text guard, the arrival
        // guard AND the `if (!isNav && !hasDist) return` drop below, so it logs EVERY notification from the five
        // nav packages — even the non-nav ones the parsed NavNotifLog necessarily hides ("Waze is running",
        // VietMap "Ứng dụng đang chạy", WazeMod status) AND ones whose content lives only in subText/bigText
        // (empty title+text). verbose-gated (default OFF) + off-main (NavNotifRawLog owns its own daemon
        // Executor) + degrade-safe. NEVER touches SourceArbiter / cluster feed / nav state. Consecutive-identical
        // per package is collapsed (lastRaw, listener-thread only → no lock) to kill GMaps' ~1/s identical redraws.
        if (NavLog.verbose) {
            val category = n.category ?: ""
            val hasLargeIcon = n.getLargeIcon() != null
            // Collapse key spans the 4 raw text fields PLUS category + large-icon presence, so a status→nav
            // transition (category flips) or an arrow appearing/disappearing with otherwise-identical text is
            // still recorded as DISTINCT — while true per-frame redraws (all fields identical) stay collapsed,
            // killing GMaps' ~1/s identical frames. \u0001 (SOH) separates fields: it never occurs in notif text.
            val rawKey = "$title\u0001$text\u0001$sub\u0001$big\u0001$category\u0001$hasLargeIcon"
            if (lastRaw[sbn.packageName] != rawKey) {
                lastRaw[sbn.packageName] = rawKey
                runCatching {
                    NavNotifRawLog.record(
                        applicationContext, sbn.packageName, category,
                        n.category == Notification.CATEGORY_NAVIGATION,
                        DIST_TOKEN.containsMatchIn(title) || DIST_TOKEN.containsMatchIn(text),
                        hasLargeIcon, title, text, sub, big,
                    )
                }.onFailure { Log.w(TAG, "raw notif log failed", it) }
            }
        }
        if (title.isEmpty() && text.isEmpty()) return
        // ĐÃ ĐẾN NƠI (R7/#2): GMaps/VietMap báo "Arrived/đã đến" → PHÁT STOP/CLEAR cụm (về đồng hồ),
        // KHÔNG cắm frame kẹt heart-beat STALE_MS. Trước đây nhánh này ingest 1 frame icon-đích (15) và
        // GIỮ tới khi noti bị gỡ; nếu noti không bị gỡ (hoặc frame khác đè), cụm kẹt (owner 2026-08:
        // "GMaps đã báo tới nơi mà cụm kẹt 3.5 km đi thẳng"). Giờ đóng đường về gauges ngay.
        if (NavArrivalGuard.isArrivalText(title, text, big)) {
            // R3: "đã đến" cũng phải qua trọng tài — app NỀN báo đến KHÔNG được đè cụm đang do app khác giữ.
            if (!SourceArbiter.shouldFeed(sbn.packageName, Prefs.sourceMode(applicationContext), System.currentTimeMillis())) return
            arrivalGuard.reset(); lastManeuverIcon = -1; lastNavLogKey = null
            runCatching { TurnDistanceInterpolator.reset() }
            runCatching { NavRepository.stop(applicationContext) }
                .onFailure { Log.e(TAG, "arrival stop failed", it) }
            ClusterNavLaneWidget.onNavIdle()
            Log.i(TAG, "đã đến nơi (${sbn.packageName}) → clear cụm (stop)")
            return
        }
        // NHẬN noti dẫn đường: category=navigation HOẶC có TOKEN CỰ LY trong title/text (bản GMaps patched/ReVanced
        // đôi khi không đặt category -> trước đây bị drop sạch = "mất tín hiệu"). Vẫn LOẠI noti không phải dẫn đường
        // (vd VietMap "Ứng dụng đang chạy" — không có cự ly). KHÔNG đòi FLAG_ONGOING nữa (một số build không đặt).
        val isNav = n.category == Notification.CATEGORY_NAVIGATION
        val hasDist = DIST_TOKEN.containsMatchIn(title) || DIST_TOKEN.containsMatchIn(text)
        if (!isNav && !hasDist) return

        // INSTRUMENTATION (chẩn đoán, không đụng feed cụm): ghi nhịp noti + giữ ref thô cho RemoteViews-introspection.
        NavDiag.record(sbn.packageName, title, text, sub, big, n.getLargeIcon() != null)
        NavDiag.lastRaw = n; NavDiag.lastRawPkg = sbn.packageName

        // Trọng tài chọn nguồn (theo chế độ Prefs): nếu không tới lượt thì BỎ QUA frame này.
        if (!SourceArbiter.shouldFeed(sbn.packageName, Prefs.sourceMode(applicationContext), System.currentTimeMillis())) {
            Log.i(TAG, "bỏ qua ${sbn.packageName}: nguồn khác đang giữ cụm")
            return
        }
        ClusterBroadcaster.selectSource(sbn.packageName)
        ClusterNavLaneWidget.onNavActive(applicationContext)

        // HƯỚNG RẼ: thử tên small-icon (ReVanced GMaps luôn logo -> trượt) rồi tới đọc ẢNH large-icon.
        val manIcon = IconResource.resolve(applicationContext, sbn.packageName, n.smallIcon)
        // Large-icon = nguồn hướng rẽ THẬT cho GMaps này -> LUÔN dựng (54×54, rẻ).
        val arrow = loadIconBitmap(n)

        // v1.03: classify maneuver BEFORE creating the immutable frame so it carries the
        // final code through the typed boundary. No global arrow lookup needed later.
        // Phân loại hướng rẽ frame NÀY (null = không đọc được: thiếu large-icon / chữ ký lệch ngưỡng / không verb).
        val freshIcon = manIcon.takeIf { it in 0..28 }
            ?: com.byd.clusternav.navigation.ManeuverSignature.classify(arrow?.asPixelFrame())
            ?: com.byd.clusternav.navigation.NavFormat.maneuverVerbIcon(title.ifBlank { text })
            ?: com.byd.clusternav.navigation.ArrowClassifier.classify(arrow?.asPixelFrame())
        // Chống nháy HUD: frame lỗi đọc → GIỮ hướng rẽ trước (không rớt -1 → straight); fresh hợp lệ → cập nhật mốc.
        val classifiedIcon = ManeuverHold.resolve(freshIcon, lastManeuverIcon)
        // T4 (telemetry): snapshot the maneuver icon BEFORE the in-place hold update so the segment-change
        // decision at the end of handle() can still see an icon change.
        val prevManeuverIcon = lastManeuverIcon
        if (classifiedIcon in 0..28) lastManeuverIcon = classifiedIcon

        // TASK 1 (closeout 1.28): mang MANEUVER CÓ HƯỚNG cho họ vòng xuyến sang NavState.maneuver. Bottleneck cũ:
        // classifiedIcon là AMAP-int nên MỌI vòng xuyến gộp về 11 → NavRepository.ingest fromAmapIcon(11)=ROUNDABOUT
        // generic → HUD toHudIcon()=20 (mất hướng ra). classifyManeuver đọc CHÍNH large-icon frame này và CHỈ trả
        // non-null cho chữ ký vòng xuyến (ROUNDABOUT_LEFT/RIGHT/STRAIGHT/UTURN ± _CW). Mọi frame KHÁC — kể cả frame
        // lỗi đọc / bị ManeuverHold GIỮ (arrow không khớp registry) — rơi về fromAmapIcon(classifiedIcon): hành vi
        // non-roundabout KHÔNG đổi, và vòng xuyến trên frame bị-giữ degrade về generic (chấp nhận được per handoff:
        // generic còn hơn sai hướng). Ưu tiên SỐ-LỐI-RA (24+N) vẫn do NavRepository quyết — KHÔNG đụng ở đây.
        val maneuver = com.byd.clusternav.navigation.ManeuverSignature.classifyManeuver(arrow?.asPixelFrame())
            ?: com.byd.clusternav.navigation.Maneuver.fromAmapIcon(classifiedIcon)

        val state = (NotificationParser.parse(sbn.packageName, title, text, sub, big, arrow, classifiedIcon) ?: return)
            .copy(maneuver = maneuver)

        // T2 (telemetry): persist the RAW notification + the parsed NavState to a pullable CSV so a drive's
        // per-turn data can be pulled and used to improve arrow/road/distance accuracy. verbose-gated (default
        // OFF) + off-main (NavNotifLog writes on its own daemon Executor) + degrade-safe (never affects nav).
        if (NavLog.verbose) runCatching {
            NavNotifLog.record(
                applicationContext, sbn.packageName, title, text, sub, big, n.getLargeIcon() != null,
                state.maneuverIcon, state.distance, state.road, state.eta,
            )
        }

        // R7/#2: route complete (route-remaining collapsed to ~0) → clear cụm thay vì heart-beat frame cũ.
        val routeRemainMeters = NavParse.parseEta(state.eta).first
        if (arrivalGuard.arrivedByRouteRemaining(routeRemainMeters.takeIf { it >= 0 })) {
            arrivalGuard.reset(); lastManeuverIcon = -1; lastNavLogKey = null
            runCatching { TurnDistanceInterpolator.reset() }
            runCatching { NavRepository.stop(applicationContext) }.onFailure { Log.e(TAG, "route-end stop failed", it) }
            ClusterNavLaneWidget.onNavIdle()
            Log.i(TAG, "route-remaining ~0 (${sbn.packageName}) → clear cụm (stop)")
            return
        }

        // R7/#2: chặn cự ly NHẢY LÙI vô lý (đang tới gần mà vọt lên, cùng maneuver, không reroute) — giữ
        // frame cũ trên cụm thay vì để frame lỗi trở thành giá trị heart-beat (owner: 500 m → 3.5 km).
        val distMeters = NavParse.parseMeters(state.distance)
        if (distMeters >= 0) {
            val maneuverKey = NavFormat.cleanRoadName(state.road) + "|" + state.maneuverText
            if (!arrivalGuard.acceptDistance(distMeters, maneuverKey)) {
                Log.i(TAG, "bỏ frame cự ly nhảy vô lý: ${distMeters}m road='${state.road}' (giữ frame cũ)")
                return
            }
        }

        NavRepository.ingest(applicationContext, sbn.packageName, null, state)
        // D4 (closeout 1.28): log-on-change — only Log.i when dist|road|eta changes from the previous emission
        // (kills per-notification spam; W/E + state-change logs above stay unconditional).
        val navKey = "${state.distance}|${state.road}|${state.eta}"
        val prevNavKey = lastNavLogKey
        if (navKey != lastNavLogKey) {
            lastNavLogKey = navKey
            Log.i(TAG, "nav dist='${state.distance}' road='${state.road}' eta='${state.eta}'")
        }
        // T4 (telemetry): on a real segment/maneuver change (NOT the ~4 Hz heartbeat), trigger a debounced
        // (~3 s) screenshot of BOTH displays over the dadb loopback — verbose-gated, OFF-main, degrade-safe.
        // The seg-<n>-<ts>-*.png files correlate with the CSV rows above by timestamp; a screenshot failure
        // never touches nav (SegmentShotCapturer wraps every shell in runCatching).
        if (NavLog.verbose &&
            SegmentShotDecision.segmentChanged(prevNavKey, navKey, prevManeuverIcon, classifiedIcon)) {
            runCatching { SegmentShotCapturer.get(applicationContext).onSegmentChange() }
        }
    }

    private fun loadIconBitmap(n: Notification): Bitmap? {
        // Chỉ largeIcon mới là mũi tên maneuver; smallIcon là logo Maps -> bỏ.
        val icon = n.getLargeIcon() ?: return null
        return runCatching {
            val d: Drawable? = icon.loadDrawable(applicationContext)
            d?.let { BitmapUtil.drawableToBitmap(it) }
        }.getOrNull()
    }
}
