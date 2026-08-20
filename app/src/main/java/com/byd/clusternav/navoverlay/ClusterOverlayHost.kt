package com.byd.clusternav.navoverlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Shared attach mechanism for TYPE_APPLICATION_OVERLAY windows on **display 1 (the instrument cluster)** —
 * factored from the proven `speedbadge.SpeedBadgeOverlay` approach (create a display context for display 1, get
 * its [WindowManager], and (re)attach via a [DisplayManager.DisplayListener] because the cast display comes and
 * goes while driving). This host lets NavClusterOverlay (T5) REUSE that exact mechanism instead of standing up a
 * new overlay technique, and is ready for future cluster overlays.
 *
 * NOTE: `SpeedBadgeOverlay` predates this helper and is deliberately NOT migrated onto it here — it is read-only
 * for this change and must not be disturbed (R-nf3: don't break the speed badge). This host runs entirely on
 * the main handler and is fully degrade-safe: off-car (no display 1) it stays uninitialized and every op is a
 * cheap no-op; on display removal it detaches every child and drops the WM so a fresh [onReady] rebuilds cleanly.
 *
 * Lifecycle contract for the owner (NavClusterOverlay):
 *  - [onReady] fires (on the main handler) when display 1 is present/returns — REBUILD views against the fresh
 *    [displayContext] and re-attach them.
 *  - [onLost] fires when display 1 is removed — the host has already detached the children; drop the view refs.
 */
class ClusterOverlayHost(
    private val appContext: Context,
    private val onReady: () -> Unit,
    private val onLost: () -> Unit,
) : AutoCloseable {

    companion object {
        private const val TAG = "ClusterOverlayHost"
        private const val CLUSTER_DISPLAY_ID = 1
        // Fallback cluster size (Seal 1920×720) used only until the real display-1 extent is read.
        private const val DEFAULT_W = 1920
        private const val DEFAULT_H = 720
    }

    private val handler = Handler(Looper.getMainLooper())
    private var clusterWm: WindowManager? = null
    private var displayCtx: Context? = null
    // Identity set of views currently attached to display 1 (so removal/close can detach them all).
    private val attached: MutableSet<View> = Collections.newSetFromMap(IdentityHashMap())

    /** Real display-1 pixel width (falls back to [DEFAULT_W] until the display is read). Main-thread only. */
    var width = DEFAULT_W
        private set

    /** Real display-1 pixel height (falls back to [DEFAULT_H] until the display is read). Main-thread only. */
    var height = DEFAULT_H
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (displayId != CLUSTER_DISPLAY_ID) return
            handler.post {
                initOverlay()
                if (isReady) runCatching { onReady() }.onFailure { Log.w(TAG, "onReady failed: ${it.message}") }
            }
        }

        override fun onDisplayRemoved(displayId: Int) {
            if (displayId != CLUSTER_DISPLAY_ID) return
            handler.post {
                teardown()
                runCatching { onLost() }.onFailure { Log.w(TAG, "onLost failed: ${it.message}") }
            }
        }

        override fun onDisplayChanged(displayId: Int) { /* size/rotation re-read at next initOverlay */ }
    }

    init {
        handler.post {
            initOverlay()
            runCatching {
                (appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                    ?.registerDisplayListener(displayListener, handler)
            }.onFailure { Log.w(TAG, "registerDisplayListener failed: ${it.message}") }
            // If display 1 was already present at construction, drive the initial (re)build once.
            if (isReady) runCatching { onReady() }.onFailure { Log.w(TAG, "onReady failed: ${it.message}") }
        }
    }

    /** Post [block] onto the overlay's serialization thread (main handler). */
    fun post(block: () -> Unit) {
        handler.post { block() }
    }

    /** True once display 1 is available and a WindowManager was obtained. Main-thread only. */
    val isReady: Boolean get() = clusterWm != null

    /** Display context for building views bound to display 1; null off-car. Main-thread only. */
    fun displayContext(): Context? = displayCtx

    /**
     * Attach [view] with [lp] if not yet attached, else update its layout in place. Degrade-safe (never throws
     * to the caller) and a cheap no-op off-car. MUST be called on the host thread (inside [post] or a callback).
     */
    fun addOrUpdate(view: View, lp: WindowManager.LayoutParams) {
        val wm = clusterWm ?: return
        if (view in attached) {
            runCatching { wm.updateViewLayout(view, lp) }
                .onFailure { Log.w(TAG, "updateViewLayout failed: ${it.message}") }
        } else {
            runCatching { wm.addView(view, lp); attached.add(view) }
                .onFailure { Log.w(TAG, "addView failed (will retry): ${it.message}") }
        }
    }

    /** Detach [view] from display 1 if attached. Degrade-safe. Host-thread only. */
    fun remove(view: View) {
        val wm = clusterWm
        if (attached.remove(view) && wm != null) {
            runCatching { wm.removeView(view) }.onFailure { Log.w(TAG, "removeView failed: ${it.message}") }
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

    /**
     * IDEMPOTENT + retryable init (mirrors SpeedBadgeOverlay): no-op once [clusterWm] is set. If display 1 is
     * absent (off-car, or Cast not yet projecting) it stays uninitialized and returns — the DisplayListener /
     * next [onReady] retries. Never a permanent degrade. Degrade-safe (runCatching).
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
                width = size.x
                height = size.y
            }
            val ctx = appContext.createDisplayContext(display)
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (wm == null) {
                Log.d(TAG, "WindowManager null for display $CLUSTER_DISPLAY_ID — will retry")
                return
            }
            displayCtx = ctx
            clusterWm = wm
            Log.i(TAG, "cluster overlay host ready for display $CLUSTER_DISPLAY_ID (${width}x$height)")
        }.onFailure { Log.w(TAG, "initOverlay failed: ${it.message}") }
    }

    /**
     * Detach every child and DROP the display WM/context so a fresh [initOverlay] rebuilds them against the
     * display that comes back. Used on `onDisplayRemoved(1)` and [close]. Degrade-safe and idempotent.
     */
    private fun teardown() {
        val wm = clusterWm
        for (v in attached.toList()) {
            runCatching { wm?.removeView(v) }.onFailure { Log.w(TAG, "removeView(teardown) failed: ${it.message}") }
        }
        attached.clear()
        clusterWm = null
        displayCtx = null
    }
}
