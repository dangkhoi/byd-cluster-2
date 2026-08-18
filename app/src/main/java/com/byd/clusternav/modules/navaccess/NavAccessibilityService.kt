package com.byd.clusternav.modules.navaccess

import com.byd.clusternav.navigation.ScreenTextItem
import com.byd.clusternav.navigation.NavScreenReading
import com.byd.clusternav.navigation.NavScreenScan
import com.byd.clusternav.navigation.NavParse
import com.byd.clusternav.navigation.NavAccessHint
import com.byd.clusternav.navigation.NavAccessRow
import com.byd.clusternav.navigation.NavDescJoin
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
    private val maps = setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")

    // All nav sources we source-tag for capture. GMaps also drives the on-screen distance ground-truth scan
    // below and carries its guidance in event.text; VietMap / Waze / WazeMod post NO nav notifications, so
    // their same-device nav signal is the accessibility text captured (source-tagged) in [logEventText]. When
    // event.text is EMPTY — VietMap/Waze put the turn dist+road, current speed+limit and ETA+dist+dest on each
    // view's CONTENT DESCRIPTION, not text — the fallback subtree walk in [logEventText] reads those content
    // descriptions instead. packageNames in nav_accessibility_config.xml must list all of these for delivery.
    private val navPackages = maps + setOf("vn.vietmap.live", "com.waze", "com.chisadin.wazemod")

    // Per-package last logged voice-guidance text — collapses the window-content redraw flood into distinct
    // rows. Touched only on the accessibility (main) callback thread → no lock needed. Bounded (≤ navPackages).
    private val lastText = HashMap<String, String>(8)

    // Per-package last content-desc SUBTREE walk time (SystemClock.elapsedRealtime, ms). VietMap/Waze fire
    // TYPE_WINDOW_CONTENT_CHANGED densely; the empty-event.text fallback walk in [logEventText] is throttled per
    // package so we don't re-walk the tree on every event. Touched only on the a11y (main) callback thread → no
    // lock. Bounded (≤ navPackages). The event.text fast-path is NOT throttled, so GMaps stays unaffected.
    private val lastDescWalkAt = HashMap<String, Long>(8)

    // T3: nút vật lý → trợ lý giọng nói. Matcher thuần ở :core; service chỉ map KeyEvent + phóng intent.
    private val voiceKeyMatcher = VoiceKeyMatcher()

    override fun onServiceConnected() {
        NavAccessibilitySource.connected = true
        voiceKeyMatcher.reset()
        Log.i(TAG, "accessibility booster connected")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        NavAccessibilitySource.connected = false
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    /**
     * T3 — nút vật lý → trợ lý giọng nói. Chỉ chạy khi service được cấp quyền hỗ trợ + config
     * `canRequestFilterKeyEvents` + flag `flagRequestFilterKeyEvents` (xem nav_accessibility_config.xml).
     *
     * KHÔNG thay chức năng gốc: chỉ trả true (nuốt phím) cho đúng tổ hợp (keycode + cử chỉ) người dùng cấu
     * hình — quyết định ở [VoiceKeyMatcher] (:core). Phím/khác → super (pass-through).
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

        val cfg = VoiceKeyConfig(enabled = true, keyCode = Prefs.voiceKeyCode(app))
        val action = when (event.action) {
            KeyEvent.ACTION_DOWN -> VoiceKeyAction.DOWN
            KeyEvent.ACTION_UP -> VoiceKeyAction.UP
            else -> VoiceKeyAction.OTHER
        }
        val decision = voiceKeyMatcher.onKey(cfg, action, event.keyCode, event.downTime)
        if (decision.fire) {
            val spec = Prefs.voiceKeyTargetSpec(app)
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
     * subtree — the VietMap/Waze equivalent of the text/coords gathered by [collect]. Caps on the desc-list
     * size and depth so a pathological tree can't stall the main thread, and recycles each obtained child node
     * with runCatching exactly like [collect] does.
     */
    private fun gatherDescriptions(node: AccessibilityNodeInfo?, out: ArrayList<String>, depth: Int) {
        node ?: return
        if (out.size >= MAX_NODES || depth > MAX_DEPTH) return
        val d = node.contentDescription?.toString()?.trim()
        if (!d.isNullOrEmpty() && d.length <= 120) out.add(d)
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            gatherDescriptions(c, out, depth + 1)
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
        private const val MAX_NODES = 250
        private const val MAX_DEPTH = 40
    }
}
