package com.byd.clusternav.speedbadge

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import com.byd.clusternav.contracts.SpeedSignType

/**
 * TYPE_APPLICATION_OVERLAY on display 1 (cluster) showing the speed-limit badge.
 * Gracefully degrades to no-op if display 1 is unavailable (off-car / emulator).
 */
class SpeedBadgeOverlay(private val appContext: Context) : AutoCloseable {

    companion object {
        private const val TAG = "SpeedBadgeOverlay"
        private const val CLUSTER_DISPLAY_ID = 1
        private const val BADGE_SIZE_DP = 120
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
            val sizePx = (BADGE_SIZE_DP * appContext.resources.displayMetrics.density).toInt()
            val lp = WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 24
                y = 24
            }
            runCatching { clusterWm?.addView(view, lp) }
                .onFailure { Log.e(TAG, "addView failed: ${it.message}"); degraded = true; return }
            attached = true
        }
        view.visibility = android.view.View.VISIBLE
    }

    private fun doHide() {
        if (!attached) return
        badgeView?.visibility = android.view.View.INVISIBLE
    }
}
