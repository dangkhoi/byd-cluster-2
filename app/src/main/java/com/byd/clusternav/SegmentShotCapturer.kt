package com.byd.clusternav

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import com.byd.clusternav.navigation.SegmentShotDecision
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * T4 telemetry — on each per-turn (segment) change, capture the two displays to
 * `getExternalFilesDir(null)/diag/seg-<n>-<ts>-{main,cluster,cluster-overlay}.png` over the SAME on-device dadb
 * loopback the app already uses for shell ([SimpleCastRuntime] /
 * [com.byd.clusternav.modules.clustercast.ClusterDiag]) — no ADB/laptop needed while driving. The
 * `seg-<n>-<ts>` timestamp correlates the images with the NavNotifLog / NavAccessLog rows.
 *
 * WHAT EACH FILE IS (on-car finding 2026-08-17):
 *  • `-main`            = display 0 (main head-unit) via `fission_screencap`. Display 0 shows whatever nav app
 *                         is foreground — not necessarily GMaps — hence `-main`, not the old `-gmaps`.
 *  • `-cluster`         = display 1 via `fission_screencap` (OEM composite). UNRELIABLE for our overlay: on-car
 *                         it sometimes returns the MAIN screen and never includes our TYPE_APPLICATION_OVERLAY
 *                         badge.
 *  • `-cluster-overlay` = display 1 via the platform `screencap` (Android overlay layer). This DOES capture the
 *                         TYPE_APPLICATION_OVERLAY badge, so it reflects what the driver actually sees on the
 *                         cluster. Both cluster shots are kept so the two layers can be compared per turn.
 *
 * Safety envelope: (a) GATED behind [NavLog.verbose] (default OFF), (b) the debounce + shell round-trips run on
 * a single-thread daemon Executor, never on the main/notification thread, (c) DEBOUNCED to at most once per
 * ~3 s ([SegmentShotDecision.shouldFire]) and only on a real change, and (d) every screencap is wrapped in
 * runCatching — a screenshot failure must never affect navigation (each capture fails independently).
 *
 * Process-singleton so the debounce timestamp + segment counter survive across notifications.
 */
class SegmentShotCapturer private constructor(private val appContext: Context) {

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "segmentshot").apply { isDaemon = true } }
    }

    private val segment = AtomicInteger(0)

    // Touched ONLY on the single io thread → no visibility race, no lock needed.
    private var lastFireMs = 0L

    /**
     * Call on a confirmed nav segment change. No-op unless [NavLog.verbose]. Posts to the io thread where the
     * ~3 s debounce is applied; on fire, captures both displays. Never blocks the caller, never throws.
     */
    fun onSegmentChange() {
        if (!NavLog.verbose) return
        val now = SystemClock.elapsedRealtime()
        runCatching { io.execute { fireLocked(now) } }
    }

    private fun fireLocked(now: Long) {
        if (!SegmentShotDecision.shouldFire(lastFireMs, now)) return
        lastFireMs = now
        val n = segment.incrementAndGet()
        val ts = System.currentTimeMillis()
        runCatching {
            val diag = File(appContext.getExternalFilesDir(null), "diag").apply { mkdirs() }
            // display 0 (main head-unit): shows whatever nav app is foreground — not necessarily GMaps — so
            // the suffix is -main. fission_screencap grabs the OEM composite for that display.
            captureFission(0, File(diag, "seg-$n-$ts-main.png").absolutePath)
            // display 1 (cluster): capture TWICE so the two layers can be compared per turn (on-car 2026-08-17):
            //  • fission_screencap -> -cluster.png         = OEM composite (UNRELIABLE for our overlay: sometimes
            //    returns the main screen, never includes the TYPE_APPLICATION_OVERLAY badge).
            //  • platform screencap -> -cluster-overlay.png = Android overlay layer, which DOES include our badge.
            captureFission(1, File(diag, "seg-$n-$ts-cluster.png").absolutePath)
            captureAndroid(1, File(diag, "seg-$n-$ts-cluster-overlay.png").absolutePath)
            Log.i(TAG, "segment $n screencaps -> ${diag.absolutePath}/seg-$n-$ts-*.png")
        }.onFailure { Log.w(TAG, "segment $n screencap failed", it) }
    }

    /** OEM composite grab via BYD's `fission_screencap` (its `-p` flag TAKES the output path). Degrade-safe. */
    private fun captureFission(display: Int, outPath: String) {
        runCatching {
            SimpleCastRuntime.coordinator(appContext).executeShell("fission_screencap -d $display -p $outPath")
        }.onFailure { Log.w(TAG, "fission_screencap -d $display failed", it) }
    }

    /**
     * Android overlay-layer grab via the platform `screencap` (proven on-car 2026-08-17 to capture the
     * TYPE_APPLICATION_OVERLAY badge that `fission_screencap` misses on the cluster). The output path is
     * POSITIONAL and its `.png` extension makes screencap encode PNG — no `-p` flag (unlike fission, the
     * platform `-p` is a boolean, and omitting it avoids any fork consuming the path as an argument).
     * Degrade-safe: a failure here never blocks the fission captures (each runs in its own runCatching).
     */
    private fun captureAndroid(display: Int, outPath: String) {
        runCatching {
            SimpleCastRuntime.coordinator(appContext).executeShell("screencap -d $display $outPath")
        }.onFailure { Log.w(TAG, "screencap -d $display failed", it) }
    }

    companion object {
        private const val TAG = "SegmentShot"

        @Volatile private var instance: SegmentShotCapturer? = null

        fun get(context: Context): SegmentShotCapturer = instance ?: synchronized(this) {
            instance ?: SegmentShotCapturer(context.applicationContext).also { instance = it }
        }
    }
}
