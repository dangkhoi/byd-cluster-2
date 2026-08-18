package com.byd.clusternav.speedbadge

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.byd.clusternav.Prefs
import com.byd.clusternav.contracts.SpeedSignType

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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clusterWm: WindowManager? = null
    private var badgeView: SpeedBadgeView? = null
    private var attached = false
    // Last value seen, remembered so a re-attach (display 1 added, or badge re-enabled) can re-show it without
    // waiting for the next pipeline emission. Null = nothing to show yet.
    private var lastSpeedKph: Int? = null
    private var lastSignType: SpeedSignType? = null
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
        val view = badgeView
        if (attached && view != null) {
            runCatching { clusterWm?.removeView(view) }
                .onFailure { Log.w(TAG, "removeView failed: ${it.message}") }
        }
        attached = false
        clusterWm = null
        badgeView = null
    }
}
