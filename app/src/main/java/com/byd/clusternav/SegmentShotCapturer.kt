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
 * T4 telemetry — on each per-turn (segment) change, capture BOTH displays (GMaps + cluster) to
 * `getExternalFilesDir(null)/diag/seg-<n>-<ts>-{gmaps,cluster}.png` over the SAME on-device dadb loopback the
 * app already uses for shell ([SimpleCastRuntime] / [com.byd.clusternav.modules.clustercast.ClusterDiag]) — no
 * ADB/laptop needed while driving. The `seg-<n>-<ts>` timestamp correlates the images with the NavNotifLog /
 * NavAccessLog rows.
 *
 * Safety envelope: (a) GATED behind [NavLog.verbose] (default OFF), (b) the debounce + shell round-trips run on
 * a single-thread daemon Executor, never on the main/notification thread, (c) DEBOUNCED to at most once per
 * ~3 s ([SegmentShotDecision.shouldFire]) and only on a real change, and (d) every screencap is wrapped in
 * runCatching — a screenshot failure must never affect navigation.
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
            // -d 0 / -d 1 = the two displays; filenames follow the T4 spec (gmaps vs cluster). Capturing both
            // means the pull has each turn's source (GMaps) and target (cluster) frame regardless of trim mapping.
            capture(0, File(diag, "seg-$n-$ts-gmaps.png").absolutePath)
            capture(1, File(diag, "seg-$n-$ts-cluster.png").absolutePath)
            Log.i(TAG, "segment $n screencaps -> ${diag.absolutePath}/seg-$n-$ts-*.png")
        }.onFailure { Log.w(TAG, "segment $n screencap failed", it) }
    }

    private fun capture(display: Int, outPath: String) {
        runCatching {
            SimpleCastRuntime.coordinator(appContext).executeShell("fission_screencap -d $display -p $outPath")
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
