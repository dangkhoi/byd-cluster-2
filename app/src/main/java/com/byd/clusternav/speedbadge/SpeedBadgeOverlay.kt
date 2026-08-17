package com.byd.clusternav.speedbadge

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import com.byd.clusternav.Prefs
import com.byd.clusternav.contracts.SpeedSignType

/**
 * TYPE_APPLICATION_OVERLAY on display 1 (cluster) showing the speed-limit badge.
 * Gracefully degrades to no-op if display 1 is unavailable (off-car / emulator).
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
    private var degraded = false

    init {
        handler.post { initOverlay() }
    }

    private fun initOverlay() {
        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = dm?.getDisplay(CLUSTER_DISPLAY_ID)
        if (display == null) {
            Log.w(TAG, "display $CLUSTER_DISPLAY_ID not found — degrading to no-op")
            degraded = true
            return
        }
        val clusterCtx = appContext.createDisplayContext(display)
        clusterWm = clusterCtx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        if (clusterWm == null) {
            Log.w(TAG, "WindowManager null for display $CLUSTER_DISPLAY_ID — degrading")
            degraded = true
            return
        }
        badgeView = SpeedBadgeView(clusterCtx)
        Log.i(TAG, "overlay initialized for display $CLUSTER_DISPLAY_ID")
    }

    fun show(speedKph: Int, signType: SpeedSignType?) {
        handler.post { doShow(speedKph, signType) }
    }

    fun hide() {
        handler.post { doHide() }
    }

    override fun close() {
        handler.post { doHide() }
    }

    private fun doShow(speedKph: Int, signType: SpeedSignType?) {
        if (degraded) return
        val view = badgeView ?: return
        view.speedValue = speedKph
        view.signType = signType
        if (!attached) {
            val lp = buildLayoutParams()
            runCatching { clusterWm?.addView(view, lp) }
                .onFailure { Log.e(TAG, "addView failed: ${it.message}"); degraded = true; return }
            attached = true
        }
        view.visibility = android.view.View.VISIBLE
    }

    /**
     * Build the overlay LayoutParams from the persisted badge prefs (corner / size / nudge). Read on the
     * main handler (doShow / doRefreshLayout both run there); SharedPreferences is memory-cached so this is
     * cheap and never hits the notification thread. The corner→gravity map is the tested pure
     * [com.byd.clusternav.speedbadge.BadgeLayout] in :core; size clamps to 60..240 dp inside [Prefs].
     */
    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val density = appContext.resources.displayMetrics.density
        val sizePx = (Prefs.badgeSizeDp(appContext) * density).toInt()
        val dxPx = (Prefs.badgeDx(appContext) * density).toInt()
        val dyPx = (Prefs.badgeDy(appContext) * density).toInt()
        return WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = BadgeLayout.gravityForCorner(Prefs.badgeCorner(appContext))
            x = dxPx
            y = dyPx
        }
    }

    /**
     * Re-read the badge prefs and, if the badge is currently attached, apply the new position/size LIVE via
     * [WindowManager.updateViewLayout] on the main handler. Degrade-safe (runCatching, never throws to the
     * caller) and a no-op before attach or when degraded — the next [show] then picks up the fresh prefs.
     * Called by DiagActivity so the driver can force-show the badge and watch it move/resize on the cluster.
     */
    fun refreshLayout() {
        handler.post { doRefreshLayout() }
    }

    private fun doRefreshLayout() {
        if (degraded || !attached) return
        val view = badgeView ?: return
        runCatching { clusterWm?.updateViewLayout(view, buildLayoutParams()) }
            .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.message}") }
    }

    private fun doHide() {
        if (!attached) return
        badgeView?.visibility = android.view.View.INVISIBLE
    }
}
