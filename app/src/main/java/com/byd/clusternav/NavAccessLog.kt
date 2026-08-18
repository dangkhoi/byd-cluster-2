package com.byd.clusternav

import android.content.Context
import com.byd.clusternav.navigation.NavAccessRow
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Multi-source nav telemetry — persist what the accessibility booster reads/hears from EACH navigation app,
 * tagged by its SOURCE package, to a pullable CSV so on-car ground-truth can be compared + SEPARATED off-car.
 * GMaps rows carry the on-screen distance-to-turn / road / maneuver-hint it reads; VietMap / Waze / WazeMod
 * rows carry the announced voice-guidance `text` (they post no nav notifications and draw the map in a
 * SurfaceView, so announcements are their only same-device nav signal).
 * Pull: adb pull /sdcard/Android/data/com.byd.clusternav2/files/nav_access_log_*.csv
 *
 * Same shape as [NavNotifLog]: GATED behind [NavLog.verbose] (default OFF), all I/O on a single-thread daemon
 * Executor (the accessibility scan runs on the main/UI thread), CSV row shape delegated to the pure
 * [NavAccessRow] (:core, unit-tested), lazy thread, degrade-safe. This is READ-ONLY diagnostics: it does NOT
 * touch [com.byd.clusternav.navigation.TurnDistanceInterpolator.refine].
 */
object NavAccessLog {
    private var w: BufferedWriter? = null
    @Volatile var path: String = ""; private set

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "navaccesslog").apply { isDaemon = true } }
    }

    const val HEADER = NavAccessRow.HEADER

    private fun ensureLocked(ctx: Context) {
        if (w != null) return
        runCatching {
            val f = File(ctx.applicationContext.getExternalFilesDir(null), "nav_access_log_${System.currentTimeMillis()}.csv")
            w = f.bufferedWriter().also { it.appendLine(HEADER) }
            path = f.absolutePath
        }
    }

    /**
     * Record one accessibility read/hear. [pkg] is the SOURCE package (event.packageName). [screenReadMeters]
     * uses the source's own -1 = "not read" sentinel ([NavAccessRow.NO_METERS]); [road] / [maneuverHint] are
     * blank when they can't be extracted (or for non-GMaps announcement rows); [text] is the announced
     * voice-guidance string (blank for pure GMaps screen-scan rows). No-op unless [NavLog.verbose].
     */
    fun record(
        ctx: Context,
        pkg: String,
        screenReadMeters: Int,
        road: String,
        maneuverHint: String,
        text: String,
    ) {
        if (!NavLog.verbose) return
        val t = System.currentTimeMillis()
        io.execute { recordLocked(ctx, t, pkg, screenReadMeters, road, maneuverHint, text) }
    }

    private fun recordLocked(
        ctx: Context,
        t: Long,
        pkg: String,
        screenReadMeters: Int,
        road: String,
        maneuverHint: String,
        text: String,
    ) {
        ensureLocked(ctx)
        val ww = w ?: return
        runCatching {
            ww.appendLine(NavAccessRow.row(t, pkg, screenReadMeters, road, maneuverHint, text))
            ww.flush()
        }
    }
}
