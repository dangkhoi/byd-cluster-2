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
 *  • `-cluster`         = `fission_screencap -d 0` = the CLUSTER composite (OEM + our badge). PROVEN on-car
 *                         that fission's display ids are OPPOSITE Android's: fission `-d 0` is the cluster and
 *                         fission `-d 1` is the main head-unit. This grabs the OEM composite for the cluster.
 *  • `-main`            = `fission_screencap -d 1` = the MAIN head-unit screen. Shows whatever nav app is
 *                         foreground — not necessarily GMaps — hence `-main`, not the old `-gmaps`.
 *  • `-cluster-overlay` = Android display 1 via the platform `screencap` (the Android cast surface / overlay
 *                         layer). Kept alongside the fission cluster shot so the two layers can be compared
 *                         per turn.
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
            // fission display ids are OPPOSITE Android's (proven on-car 2026-08-17):
            //  • fission -d 0 = CLUSTER composite (OEM + our badge)  -> -cluster.png
            //  • fission -d 1 = MAIN head-unit (foreground nav app)  -> -main.png
            captureFission(0, File(diag, "seg-$n-$ts-cluster.png").absolutePath)
            captureFission(1, File(diag, "seg-$n-$ts-main.png").absolutePath)
            // Android display 1 via the platform screencap = the Android cast surface / overlay layer. Kept
            // alongside the fission cluster shot so the two layers can be compared per turn.
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
