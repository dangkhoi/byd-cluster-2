package com.byd.clusternav

import android.content.Context
import com.byd.clusternav.core.CsvEscape
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * T3 telemetry — persist what the accessibility booster reads off the GMaps screen (distance-to-turn, road, and
 * a best-effort maneuver/arrow hint) to a pullable CSV, so on-car ground-truth can be compared off-car against
 * our own notification-parsed / bitmap-classified NavState to improve arrow + road + distance accuracy.
 * Pull: adb pull /sdcard/Android/data/com.byd.clusternav2/files/nav_access_log_*.csv
 *
 * Same shape as [NavNotifLog]: GATED behind [NavLog.verbose] (default OFF), all I/O on a single-thread daemon
 * Executor (the accessibility scan runs on the main/UI thread), CSV-escaped fields ([CsvEscape]), lazy thread,
 * degrade-safe. This is READ-ONLY diagnostics: it does NOT touch [com.byd.clusternav.navigation.TurnDistanceInterpolator.refine].
 */
object NavAccessLog {
    private var w: BufferedWriter? = null
    @Volatile var path: String = ""; private set

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "navaccesslog").apply { isDaemon = true } }
    }

    const val HEADER = "t_ms,screenRead_m,screenRead_road,screenRead_maneuverHint"

    private fun ensureLocked(ctx: Context) {
        if (w != null) return
        runCatching {
            val f = File(ctx.applicationContext.getExternalFilesDir(null), "nav_access_log_${System.currentTimeMillis()}.csv")
            w = f.bufferedWriter().also { it.appendLine(HEADER) }
            path = f.absolutePath
        }
    }

    /**
     * Record one accessibility screen read. [screenReadMeters] uses the source's own -1 = "not read" sentinel;
     * [road] / [maneuverHint] are blank when they can't be extracted. No-op unless [NavLog.verbose].
     */
    fun record(ctx: Context, screenReadMeters: Int, road: String, maneuverHint: String) {
        if (!NavLog.verbose) return
        val t = System.currentTimeMillis()
        io.execute { recordLocked(ctx, t, screenReadMeters, road, maneuverHint) }
    }

    private fun recordLocked(ctx: Context, t: Long, screenReadMeters: Int, road: String, maneuverHint: String) {
        ensureLocked(ctx)
        val ww = w ?: return
        runCatching {
            ww.appendLine(CsvEscape.row(listOf(t.toString(), screenReadMeters.toString(), road, maneuverHint)))
            ww.flush()
        }
    }
}
