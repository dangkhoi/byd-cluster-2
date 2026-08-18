package com.byd.clusternav.speedbadge

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.byd.clusternav.Prefs
import com.byd.clusternav.contracts.SpeedSignType
import com.byd.clusternav.navigation.NavParse

/**
 * TYPE_APPLICATION_OVERLAY on display 1 (cluster) showing the speed-limit badge.
 *
 * LIFECYCLE (2026-08-18 fix, owner note "HƯỚNG FIX"): the cluster/cast display 1 can appear LONG after this
 * overlay is constructed (the app opens before Cast projects), and it can come and go while driving. So init
 * is **event-driven + retryable**, NOT one-shot:
 *  - [initOverlay] is IDEMPOTENT (no-op once `clusterWm != null`) and is retried from [doShow] whenever the
 *    display was not yet ready (`clusterWm == null`) — there is NO permanent one-way kill anymore.
 *  - a [DisplayManager.DisplayListener] (re)initializes on `onDisplayAdded(1)` and re-shows the pending value,
 *    and TEARS DOWN on `onDisplayRemoved(1)` (detach + drop the display WM/view) so it cleanly re-attaches
 *    when display 1 returns (e.g. Cast toggled off→on).
 * Off-car (emulator / no display 1) stays a cheap no-op: init finds no display and simply stays uninitialized.
 *
 * All WindowManager ops run on the main handler and are degrade-safe (runCatching, never throw to the caller).
 * Absolute-centre positioning ([BadgeLayout.clampCenter]) is unchanged. The badge is GATED by
 * [Prefs.badgeEnabled] (default ON): when disabled, [show] detaches and never attaches — this gate covers both
 * the real speed-sign pipeline and the debug force-show, since both call [show].
 */
class SpeedBadgeOverlay(private val appContext: Context) : AutoCloseable {

    companion object {
        private const val TAG = "SpeedBadgeOverlay"
        private const val CLUSTER_DISPLAY_ID = 1
        // ── Upcoming "speed-limit ahead" badge (spec upcoming-speed-limit-badge) ──
        private const val UPCOMING_SCALE = 0.7f          // R4: upcoming badge is ~70% of the main badge
        private const val UPCOMING_GAP_FRAC = 0.10f      // vertical gap below the main badge (× main size)
        private const val UPCOMING_CONTAINER_W_FRAC = 1.8f // window width (× main size) so the distance text never clips
        private const val UPCOMING_LABEL_FRAC = 0.42f    // distance label text size (× upcoming badge size)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clusterWm: WindowManager? = null
    private var badgeView: SpeedBadgeView? = null
    private var attached = false
    // Last value seen, remembered so a re-attach (display 1 added, or badge re-enabled) can re-show it without
    // waiting for the next pipeline emission. Null = nothing to show yet.
    private var lastSpeedKph: Int? = null
    private var lastSignType: SpeedSignType? = null
    // ── Upcoming "speed-limit ahead" badge: a SECOND SpeedBadgeView (~70%) + a countdown distance label in a
    // vertical container, anchored directly BELOW the main badge. Its own window (additive) so the current-limit
    // badge window is never touched. Last values remembered so a re-attach (display 1 added / re-enabled) can
    // re-show without waiting for the next VietMap emission.
    private var upcomingContainer: LinearLayout? = null
    private var upcomingBadgeView: SpeedBadgeView? = null
    private var upcomingDistLabel: TextView? = null
    private var upcomingAttached = false
    private var lastUpcomingLimit: Int? = null
    private var lastUpcomingDist: Int? = null
    private var lastUpcomingText: String? = null
    // Real display-1 size in px for on-screen clamping (BadgeLayout.clampCenter). Falls back to the Seal
    // cluster 1920×720 when the real size can't be read, so placement math never divides by a bogus extent.
    private var clusterW = 1920
    private var clusterH = 720

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId != CLUSTER_DISPLAY_ID) return
            handler.post {
                initOverlay()
                // If a value is pending, re-show it now that display 1 is back (respects the enabled gate).
                lastSpeedKph?.let { doShow(it, lastSignType) }
                lastUpcomingLimit?.let { doSetUpcoming(it, lastUpcomingDist, lastUpcomingText) }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != CLUSTER_DISPLAY_ID) return
            handler.post { teardown() }
        }

        override fun onDisplayChanged(displayId: Int) { /* size/rotation handled at next show via initOverlay */ }
    }

    init {
        handler.post {
            initOverlay()
            runCatching {
                (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.registerDisplayListener(displayListener, handler)
            }.onFailure { Log.w(TAG, "registerDisplayListener failed: ${it.message}") }
        }
    }

    /**
     * IDEMPOTENT + retryable init. No-op if already initialized (`clusterWm != null`). If display 1 is absent
     * (off-car, or Cast not yet projecting) it stays UN-initialized and returns — the next [doShow] /
     * onDisplayAdded retries. Never sets a permanent degrade. Degrade-safe (runCatching).
     */
    private fun initOverlay() {
        if (clusterWm != null) return
        runCatching {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val display = dm?.getDisplay(CLUSTER_DISPLAY_ID)
            if (display == null) {
                Log.d(TAG, "display $CLUSTER_DISPLAY_ID not ready — staying uninitialized, will retry")
                return
            }
            val size = android.graphics.Point()
            @Suppress("DEPRECATION") display.getRealSize(size)
            if (size.x > 0 && size.y > 0) {
                clusterW = size.x
                clusterH = size.y
            }
            val clusterCtx = appContext.createDisplayContext(display)
            val wm = clusterCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.d(TAG, "WindowManager null for display $CLUSTER_DISPLAY_ID — will retry")
                return
            }
            // Build the view BEFORE publishing either field: if SpeedBadgeView construction throws, clusterWm
            // stays null so the next doShow() / onDisplayAdded retries — never a half-initialized state
            // (clusterWm set, badgeView null) that the `clusterWm == null` retry guard could not recover from.
            val view = SpeedBadgeView(clusterCtx)
            clusterWm = wm
            badgeView = view
            Log.i(TAG, "overlay initialized for display $CLUSTER_DISPLAY_ID (${clusterW}x$clusterH)")
        }.onFailure { Log.w(TAG, "initOverlay failed: ${it.message}") }
    }

    fun show(speedKph: Int, signType: SpeedSignType?) {
        handler.post { doShow(speedKph, signType) }
    }

    fun hide() {
        handler.post { doHide() }
    }

    /**
     * Re-evaluate the [Prefs.badgeEnabled] gate after the toggle changes: detach when disabled, or re-show the
     * last known value when re-enabled. Posted to the main handler; degrade-safe.
     */
    fun applyEnabled() {
        handler.post {
            if (!Prefs.badgeEnabled(appContext)) {
                teardown()
            } else {
                lastSpeedKph?.let { doShow(it, lastSignType) }
                lastUpcomingLimit?.let { doSetUpcoming(it, lastUpcomingDist, lastUpcomingText) }
            }
        }
    }

    /**
     * Public API — set (or clear) the "upcoming speed-limit ahead" badge shown BELOW the main badge. Passing a
     * null/<=0 [limitKph] hides it. Posted to the main handler; degrade-safe. [distanceText] (VietMap's raw
     * "300 m" / "1,2 km") is preferred for the countdown label, falling back to formatting [distanceMeters].
     */
    fun setUpcoming(limitKph: Int?, distanceMeters: Int?, distanceText: String? = null) {
        handler.post { doSetUpcoming(limitKph, distanceMeters, distanceText) }
    }

    /**
     * Re-evaluate the upcoming-badge gate (master [Prefs.badgeEnabled] AND [Prefs.showUpcomingBadge]) after the
     * "Hiện giới hạn sắp tới" toggle changes: detach when off, or re-show the last value when on. Degrade-safe.
     */
    fun applyUpcomingEnabled() {
        handler.post {
            if (!Prefs.badgeEnabled(appContext) || !Prefs.showUpcomingBadge(appContext)) {
                teardownUpcoming()
            } else {
                lastUpcomingLimit?.let { doSetUpcoming(it, lastUpcomingDist, lastUpcomingText) }
            }
        }
    }

    override fun close() {
        handler.post {
            runCatching {
                (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.unregisterDisplayListener(displayListener)
            }
            teardown()
        }
    }

    private fun doShow(speedKph: Int, signType: SpeedSignType?) {
        lastSpeedKph = speedKph
        lastSignType = signType
        // Gate: badge disabled → make sure nothing is on the cluster and never attach.
        if (!Prefs.badgeEnabled(appContext)) {
            teardown()
            return
        }
        // Retry init if display 1 was not ready when we were constructed (or after a teardown).
        if (clusterWm == null) initOverlay()
        val view = badgeView ?: return   // still no display 1 (off-car) → cheap no-op
        view.speedValue = speedKph
        view.signType = signType
        if (!attached) {
            val lp = buildLayoutParams()
            runCatching { clusterWm?.addView(view, lp) }
                .onFailure { Log.w(TAG, "addView failed (will retry next show): ${it.message}"); return }
            attached = true
        }
        view.visibility = android.view.View.VISIBLE
    }

    /**
     * Build the overlay LayoutParams from the persisted badge prefs (absolute centre + size). Read on the
     * main handler (doShow / doRefreshLayout both run there); SharedPreferences is memory-cached so this is
     * cheap and never hits the notification thread. Uses `gravity = TOP|LEFT` and sets `x`/`y` to the badge's
     * top-left, computed from the persisted CENTRE via the tested pure [BadgeLayout] in :core: the centre is
     * first clamped on-screen ([BadgeLayout.clampCenter]) against the real display-1 size, then converted to a
     * top-left ([BadgeLayout.topLeftFromCenter]). Size clamps to 60..240 dp inside [Prefs].
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val density = appContext.resources.displayMetrics.density
        val sizePx = (Prefs.badgeSizeDp(appContext) * density).toInt().coerceAtLeast(1)
        val (cx, cy) = BadgeLayout.clampCenter(
            Prefs.badgeCenterX(appContext), Prefs.badgeCenterY(appContext), sizePx, clusterW, clusterH,
        )
        val (left, top) = BadgeLayout.topLeftFromCenter(cx, cy, sizePx)
        return WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = left
            y = top
        }
    }

    /**
     * Re-read the badge prefs and, if the badge is currently attached, apply the new position/size LIVE via
     * [WindowManager.updateViewLayout] on the main handler. Degrade-safe (runCatching, never throws to the
     * caller) and a no-op before attach — the next [show] then picks up the fresh prefs.
     * Called by DiagActivity / the placement UI so the driver can force-show the badge and watch it move.
     */
    fun refreshLayout() {
        handler.post { doRefreshLayout() }
    }

    private fun doRefreshLayout() {
        if (upcomingAttached) {
            applyUpcomingMetrics()
            runCatching { clusterWm?.updateViewLayout(upcomingContainer, buildUpcomingLayoutParams()) }
                .onFailure { Log.w(TAG, "updateViewLayout(upcoming) failed: ${it.message}") }
        }
        if (!attached) return
        val view = badgeView ?: return
        runCatching { clusterWm?.updateViewLayout(view, buildLayoutParams()) }
            .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.message}") }
    }

    private fun doHide() {
        if (!attached) return
        badgeView?.visibility = android.view.View.INVISIBLE
    }

    /**
     * Full teardown: detach the view from display 1 and DROP the display WindowManager + view so a fresh
     * [initOverlay] rebuilds them against the display that comes back. Used on `onDisplayRemoved(1)`, on the
     * disabled gate, and on [close]. Degrade-safe and idempotent (safe when nothing is attached).
     */
    private fun teardown() {
        teardownUpcoming()
        val view = badgeView
        if (attached && view != null) {
            runCatching { clusterWm?.removeView(view) }
                .onFailure { Log.w(TAG, "removeView failed: ${it.message}") }
        }
        attached = false
        clusterWm = null
        badgeView = null
    }

    // ─── Upcoming "speed-limit ahead" badge (spec upcoming-speed-limit-badge) ──────────────────────────────
    // A SECOND window on display 1 holding a vertical container [SpeedBadgeView ~70%] + [distance label]. It is
    // ADDITIVE — the current-limit badge window above is never touched — and fully degrade-safe (runCatching,
    // never throws to the caller). Gated by BOTH the master badge gate AND the "Hiện giới hạn sắp tới" toggle.

    private fun doSetUpcoming(limitKph: Int?, distanceMeters: Int?, distanceText: String?) {
        lastUpcomingLimit = limitKph
        lastUpcomingDist = distanceMeters
        lastUpcomingText = distanceText
        // Gate: master badge OFF or the upcoming toggle OFF → fully detach and never attach.
        if (!Prefs.badgeEnabled(appContext) || !Prefs.showUpcomingBadge(appContext)) {
            teardownUpcoming()
            return
        }
        // Nothing upcoming right now → cheap hide (keep the window for a fast re-show on the next emission).
        if (limitKph == null || limitKph <= 0) {
            hideUpcoming()
            return
        }
        if (clusterWm == null) initOverlay()
        val wm = clusterWm ?: return                 // still no display 1 (off-car) → cheap no-op
        val ctx = badgeView?.context ?: return       // cluster display context (badgeView built with it)
        val container = ensureUpcomingContainer(ctx) ?: return
        upcomingBadgeView?.speedValue = limitKph
        upcomingBadgeView?.signType = SpeedSignType.REGULATORY
        applyUpcomingMetrics()
        upcomingDistLabel?.let { label ->
            val text = upcomingLabelText(distanceText, distanceMeters)
            label.text = text
            label.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        }
        val lp = buildUpcomingLayoutParams()
        if (!upcomingAttached) {
            runCatching { wm.addView(container, lp) }
                .onFailure { Log.w(TAG, "addView(upcoming) failed (will retry next set): ${it.message}"); return }
            upcomingAttached = true
        } else {
            runCatching { wm.updateViewLayout(container, lp) }
                .onFailure { Log.w(TAG, "updateViewLayout(upcoming) failed: ${it.message}") }
        }
        container.visibility = View.VISIBLE
    }

    /** Build the upcoming container (badge + label) ONCE against the cluster display context. Degrade-safe. */
    private fun ensureUpcomingContainer(ctx: Context): LinearLayout? {
        upcomingContainer?.let { return it }
        return runCatching {
            val badge = SpeedBadgeView(ctx)
            val label = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setSingleLine(true)
            }
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
            container.addView(badge, LinearLayout.LayoutParams(1, 1))
            container.addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            upcomingContainer = container
            upcomingBadgeView = badge
            upcomingDistLabel = label
            container
        }.getOrElse { Log.w(TAG, "ensureUpcomingContainer failed: ${it.message}"); null }
    }

    /** Size the upcoming badge (~70% of the main) + the label text from the CURRENT badge-size pref (live). */
    private fun applyUpcomingMetrics() {
        val density = appContext.resources.displayMetrics.density
        val mainSizePx = (Prefs.badgeSizeDp(appContext) * density).toInt().coerceAtLeast(1)
        val badgeSizePx = (mainSizePx * UPCOMING_SCALE).toInt().coerceAtLeast(1)
        upcomingBadgeView?.let { b ->
            val lp = b.layoutParams
            if (lp != null && (lp.width != badgeSizePx || lp.height != badgeSizePx)) {
                lp.width = badgeSizePx
                lp.height = badgeSizePx
                b.layoutParams = lp
            }
        }
        upcomingDistLabel?.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, badgeSizePx * UPCOMING_LABEL_FRAC)
            setShadowLayer(badgeSizePx * 0.10f, 0f, 0f, Color.BLACK)
        }
    }

    /**
     * Position the upcoming window directly BELOW the main badge (anchored under its persisted centre). The
     * window is wider than the main badge ([UPCOMING_CONTAINER_W_FRAC]) so the distance text never clips, and
     * is centred on the main badge's centre; the vertical LinearLayout centres the ~70% badge + label within it.
     */
    private fun buildUpcomingLayoutParams(): WindowManager.LayoutParams {
        val density = appContext.resources.displayMetrics.density
        val mainSizePx = (Prefs.badgeSizeDp(appContext) * density).toInt().coerceAtLeast(1)
        val (cx, cy) = BadgeLayout.clampCenter(
            Prefs.badgeCenterX(appContext), Prefs.badgeCenterY(appContext), mainSizePx, clusterW, clusterH,
        )
        val (left, top) = BadgeLayout.topLeftFromCenter(cx, cy, mainSizePx)
        val containerW = (mainSizePx * UPCOMING_CONTAINER_W_FRAC).toInt().coerceAtLeast(mainSizePx)
        val gapPx = (mainSizePx * UPCOMING_GAP_FRAC).toInt().coerceAtLeast(2)
        return WindowManager.LayoutParams(
            containerW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = left - (containerW - mainSizePx) / 2   // keep the container centred on the main badge centre
            y = top + mainSizePx + gapPx               // directly below the main badge
        }
    }

    /** Countdown label text: prefer VietMap's raw text ("300 m" / "1,2 km"); else format the metres; else "". */
    private fun upcomingLabelText(distanceText: String?, distanceMeters: Int?): String {
        val raw = distanceText?.trim()
        if (!raw.isNullOrEmpty()) return raw
        if (distanceMeters != null && distanceMeters > 0) return NavParse.formatMeters(distanceMeters)
        return ""
    }

    private fun hideUpcoming() {
        if (!upcomingAttached) return
        upcomingContainer?.visibility = View.INVISIBLE
    }

    /** Detach + drop the upcoming window/views so a fresh [ensureUpcomingContainer] rebuilds cleanly. */
    private fun teardownUpcoming() {
        val container = upcomingContainer
        if (upcomingAttached && container != null) {
            runCatching { clusterWm?.removeView(container) }
                .onFailure { Log.w(TAG, "removeView(upcoming) failed: ${it.message}") }
        }
        upcomingAttached = false
        upcomingContainer = null
        upcomingBadgeView = null
        upcomingDistLabel = null
    }
}
