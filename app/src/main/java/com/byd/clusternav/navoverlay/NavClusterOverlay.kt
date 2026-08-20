package com.byd.clusternav.navoverlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.byd.clusternav.Prefs
import com.byd.clusternav.navigation.LaneInfo
import com.byd.clusternav.navigation.LaneStripModel
import com.byd.clusternav.navigation.Maneuver
import com.byd.clusternav.navigation.NavOverlayModel
import com.byd.clusternav.navigation.screencapture.CameraMatch
import com.byd.clusternav.speedbadge.BadgeLayout

/**
 * Cluster NAV OVERLAY (T5, spec `b3-full-nav-capture` R4/R5/R6): a self-drawn overlay on **display 1** that
 * renders, alongside the speed-limit badge:
 *  - a LANE STRIP drawn BELOW the speed badge — each lane's arrow glyph(s), recommended lanes BRIGHT and the
 *    rest DIMMED ([LaneStripView] fed by [NavOverlayModel.laneStrip]);
 *  - a CAMERA chip (icon + distance) to the RIGHT of the speed sign ([CameraChipView]).
 *
 * It REUSES the proven display-1 overlay mechanism via [ClusterOverlayHost] (same TYPE_APPLICATION_OVERLAY
 * approach as `SpeedBadgeOverlay`) rather than standing up a new WindowManager overlay, and it uses SEPARATE
 * windows from the badge so it never disturbs it (R-nf3). Both sub-views are anchored to the SAME persisted
 * badge geometry ([Prefs] + [BadgeLayout]) so the strip/chip track the badge wherever the driver placed it.
 *
 * Drive it with [update] (content) + [show]/[hide] (lifecycle). This class owns NO lifecycle/gating of its own —
 * the orchestrator (T6) wires it to the nav source; simply constructing it changes nothing at runtime. All ops
 * run on the host's main handler and are degrade-safe: off-car (no display 1) everything is a cheap no-op, and
 * the last frame is remembered so a display re-add (Cast off→on) re-shows without waiting for the next emission.
 */
class NavClusterOverlay(private val appContext: Context) : AutoCloseable {

    private val host = ClusterOverlayHost(appContext, onReady = ::onDisplayReady, onLost = ::onDisplayLost)

    private var laneView: LaneStripView? = null
    private var cameraView: CameraChipView? = null

    // Whether the orchestrator wants the overlay shown (toggled by show()/hide()). Host-thread only.
    private var visible = false

    // Remembered last frame, replayed on a display re-add (mirrors SpeedBadgeOverlay's last-value re-show).
    private var lastLane: LaneStripModel = LaneStripModel.EMPTY
    private var lastCameraHasCamera = false
    private var lastCameraLabel = ""
    private var lastArrow: Maneuver? = null

    /** Show the overlay (attach whatever content is currently non-empty). Idempotent; degrade-safe. */
    fun show() = host.post { visible = true; apply() }

    /** Hide the overlay (detach both sub-views). Idempotent; degrade-safe. */
    fun hide() = host.post { visible = false; hideViews() }

    /**
     * Push the latest nav frame. [laneInfo] drives the lane strip (null/empty ⇒ strip hidden), [camera] drives
     * the camera chip (no camera ⇒ chip hidden), and [arrow] is the current turn maneuver — the turn arrow
     * itself is rendered by the centre-nav/HUD path (T4), so the overlay keeps it only as remembered frame
     * state (and for diagnostics), not as a third drawn glyph. The pure mapping runs on the caller thread; the
     * view mutations are posted to the host thread.
     */
    fun update(laneInfo: LaneInfo?, camera: CameraMatch?, arrow: Maneuver?) {
        val strip = NavOverlayModel.laneStrip(laneInfo)
        val hasCam = camera?.hasCamera == true
        val camLabel = if (hasCam) NavOverlayModel.cameraLabel(camera.distanceMeters) else ""
        host.post {
            lastLane = strip
            lastCameraHasCamera = hasCam
            lastCameraLabel = camLabel
            lastArrow = arrow
            if (visible) apply()
        }
    }

    // ─── Host callbacks (main handler) ──────────────────────────────────────────────────────────

    /** Display 1 appeared/returned: (re)build views against the fresh context, then re-show the last frame. */
    private fun onDisplayReady() {
        rebuildViews()
        if (visible) apply()
    }

    /** Display 1 removed: the host already detached the children — drop the stale view refs so they rebuild. */
    private fun onDisplayLost() {
        laneView = null
        cameraView = null
    }

    // ─── Rendering ──────────────────────────────────────────────────────────────────────────────

    private fun rebuildViews() {
        val ctx = host.displayContext() ?: return
        if (laneView == null) laneView = LaneStripView(ctx)
        if (cameraView == null) cameraView = CameraChipView(ctx)
    }

    /** Apply the remembered frame: attach/update the non-empty sub-views, detach the empty ones. */
    private fun apply() {
        if (!host.isReady) return   // off-car / display not ready → no-op; onDisplayReady() will re-apply
        rebuildViews()
        val lane = laneView ?: return
        val cam = cameraView ?: return
        val anchor = badgeAnchor()

        // (a) LANE STRIP — below the badge.
        if (lastLane.isEmpty) {
            host.remove(lane)
        } else {
            lane.model = lastLane
            host.addOrUpdate(lane, laneLayout(anchor, lastLane.count))
            lane.visibility = View.VISIBLE
        }

        // (b) CAMERA chip — right of the speed sign.
        if (!lastCameraHasCamera) {
            host.remove(cam)
        } else {
            cam.hasCamera = true
            cam.label = lastCameraLabel
            host.addOrUpdate(cam, cameraLayout(anchor))
            cam.visibility = View.VISIBLE
        }

        if (NAV_OVERLAY_DEBUG) {
            Log.d(TAG, "apply lanes=${lastLane.count} cam=$lastCameraHasCamera arrow=$lastArrow")
        }
    }

    private fun hideViews() {
        laneView?.let { host.remove(it) }
        cameraView?.let { host.remove(it) }
    }

    override fun close() = host.close()

    // ─── Layout math (anchored to the persisted badge geometry) ──────────────────────────────────

    private data class BadgeAnchor(val left: Int, val top: Int, val size: Int, val cx: Int, val cy: Int)

    /** The badge's clamped, on-screen rect on display 1, from the same prefs the badge overlay uses. */
    private fun badgeAnchor(): BadgeAnchor {
        val density = appContext.resources.displayMetrics.density
        val sizePx = (Prefs.badgeSizeDp(appContext) * density).toInt().coerceAtLeast(1)
        val (cx, cy) = BadgeLayout.clampCenter(
            Prefs.badgeCenterX(appContext), Prefs.badgeCenterY(appContext), sizePx, host.width, host.height,
        )
        val (left, top) = BadgeLayout.topLeftFromCenter(cx, cy, sizePx)
        return BadgeAnchor(left, top, sizePx, cx, cy)
    }

    /** Lane strip window: a horizontal strip centred under the badge, one column per lane. */
    private fun laneLayout(a: BadgeAnchor, laneCount: Int): WindowManager.LayoutParams {
        val cell = (a.size * LANE_CELL_FRAC).toInt().coerceAtLeast(1)
        val stripW = (cell * laneCount).coerceAtLeast(cell)
        val stripH = (cell * LANE_HEIGHT_FRAC).toInt().coerceAtLeast(1)
        val gap = (a.size * VERTICAL_GAP_FRAC).toInt()
        val x = (a.cx - stripW / 2).coerceIn(0, (host.width - stripW).coerceAtLeast(0))
        val y = (a.top + a.size + gap).coerceIn(0, (host.height - stripH).coerceAtLeast(0))
        return overlayParams(stripW, stripH, x, y)
    }

    /** Camera chip window: a square chip immediately right of the badge, vertically centred on it. */
    private fun cameraLayout(a: BadgeAnchor): WindowManager.LayoutParams {
        val chip = (a.size * CAMERA_CHIP_FRAC).toInt().coerceAtLeast(1)
        val gap = (a.size * HORIZONTAL_GAP_FRAC).toInt()
        val x = (a.left + a.size + gap).coerceIn(0, (host.width - chip).coerceAtLeast(0))
        val y = (a.cy - chip / 2).coerceIn(0, (host.height - chip).coerceAtLeast(0))
        return overlayParams(chip, chip, x, y)
    }

    /** Shared TYPE_APPLICATION_OVERLAY params (same flags as the badge: non-focusable, non-touchable). */
    private fun overlayParams(w: Int, h: Int, px: Int, py: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = px
            y = py
        }

    private companion object {
        private const val TAG = "NavClusterOverlay"
        private const val NAV_OVERLAY_DEBUG = false
        // Sizing/placement fractions relative to the badge size (tune on-car — R6 layout).
        private const val LANE_CELL_FRAC = 0.5f       // each lane column ≈ half the badge width
        private const val LANE_HEIGHT_FRAC = 0.72f    // strip height relative to a cell
        private const val CAMERA_CHIP_FRAC = 0.72f    // camera chip side relative to the badge
        private const val VERTICAL_GAP_FRAC = 0.14f   // gap below the badge before the lane strip
        private const val HORIZONTAL_GAP_FRAC = 0.12f // gap right of the badge before the camera chip
    }
}
