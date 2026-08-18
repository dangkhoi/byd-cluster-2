package com.byd.clusternav.vietmapwidget

import android.content.Context
import com.byd.clusternav.NavLog
import com.byd.clusternav.core.CsvEscape
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Part C (docs/specs/speed-badge-placement-vietmap-logging.html §4.4) — capture EVERY VietMap signal
 * the widget bridge sees so tomorrow's drive can be evaluated off-car (which extra signals are worth
 * drawing on the cluster).
 *
 * Two pullable CSVs, both written to the app external files dir (same place as [com.byd.clusternav.NavNotifLog]):
 *   • `vietmap_signal_<startTs>.csv` — one row per DISTINCT published snapshot: parsed current/limit speed +
 *     BOTH alerts with first/second order PRESERVED (limit / distance / image-visible / image-hash) +
 *     freshness + provider version.
 *   • `vietmap_views_<startTs>.csv`  — one row per host-view update: a full dump of every TextView /
 *     ImageView in the applied RemoteViews tree, INCLUDING fields the app does not yet parse.
 * Pull: `adb pull /sdcard/Android/data/com.byd.clusternav2/files/vietmap_signal_*.csv` (and `vietmap_views_*.csv`).
 *
 * Mirrors [com.byd.clusternav.NavNotifLog] exactly: (a) GATED behind [NavLog.verbose] (default OFF,
 * flipped by the hidden long-press / DEBUG auto-on), (b) file open + append + flush run on a single-thread
 * daemon Executor — NEVER on the widget/main thread, (c) every field is RFC-4180 escaped via [CsvEscape]
 * because raw widget text legitimately carries commas / quotes, (d) lazy — the thread + files are not created
 * until verbose actually fires, (e) degrade-safe — any failure is swallowed per row so logging can never
 * affect navigation or the widget pipeline.
 */
object VietMapSignalLog {

    const val HEADER =
        "ts,freshness,providerVersion,currentSpeedKph,speedLimitKph," +
            "a1Limit,a1Dist,a1ImgVisible,a1ImgHash,a2Limit,a2Dist,a2ImgVisible,a2ImgHash," +
            "upLimit,upDist,up2Limit,up2Dist"

    const val VIEWS_HEADER = "ts,dump"

    /** Separator joining per-view dump entries INSIDE the single quoted `dump` CSV field. */
    private const val DUMP_SEP = " | "

    /** Session id shared by both log files, captured once when this object is first touched (verbose on). */
    private val startTs: Long = System.currentTimeMillis()

    // Writers + paths are only ever touched from the single [io] thread (like NavNotifLog) → no locks needed.
    private var signalWriter: BufferedWriter? = null
    private var viewsWriter: BufferedWriter? = null

    @Volatile
    var signalPath: String = ""
        private set

    @Volatile
    var viewsPath: String = ""
        private set

    private val io: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "vietmapsignallog").apply { isDaemon = true } }
    }

    /**
     * Pure, Android-free CSV row builder — the single source of truth for column order + escaping,
     * unit-tested off-car. Booleans render as `true`/`false`; nulls render as empty fields (RFC-4180).
     */
    fun buildRow(
        ts: Long,
        freshness: String?,
        providerVersion: String?,
        currentSpeedKph: Int?,
        speedLimitKph: Int?,
        a1Limit: Int?,
        a1Dist: String?,
        a1ImgVisible: Boolean,
        a1ImgHash: String?,
        a2Limit: Int?,
        a2Dist: String?,
        a2ImgVisible: Boolean,
        a2ImgHash: String?,
        upLimit: Int? = null,
        upDist: String? = null,
        up2Limit: Int? = null,
        up2Dist: String? = null,
    ): String = CsvEscape.row(
        listOf(
            ts.toString(),
            freshness,
            providerVersion,
            currentSpeedKph?.toString(),
            speedLimitKph?.toString(),
            a1Limit?.toString(),
            a1Dist,
            a1ImgVisible.toString(),
            a1ImgHash,
            a2Limit?.toString(),
            a2Dist,
            a2ImgVisible.toString(),
            a2ImgHash,
            upLimit?.toString(),
            upDist,
            up2Limit?.toString(),
            up2Dist,
        ),
    )

    /** Pure builder for the raw view-dump row: `ts` + the dump entries joined into one escaped field. */
    fun buildViewsRow(ts: Long, dump: List<String>): String =
        CsvEscape.row(listOf(ts.toString(), dump.joinToString(DUMP_SEP)))

    /**
     * Append one signal row. No-op unless [NavLog.verbose]. The row is built on the caller thread (cheap,
     * pure) so the timestamp is accurate; the file append runs off-main.
     */
    fun log(
        ctx: Context,
        freshness: String?,
        providerVersion: String?,
        currentSpeedKph: Int?,
        speedLimitKph: Int?,
        a1Limit: Int?,
        a1Dist: String?,
        a1ImgVisible: Boolean,
        a1ImgHash: String?,
        a2Limit: Int?,
        a2Dist: String?,
        a2ImgVisible: Boolean,
        a2ImgHash: String?,
        upLimit: Int? = null,
        upDist: String? = null,
        up2Limit: Int? = null,
        up2Dist: String? = null,
    ) {
        if (!NavLog.verbose) return
        val row = buildRow(
            System.currentTimeMillis(), freshness, providerVersion, currentSpeedKph, speedLimitKph,
            a1Limit, a1Dist, a1ImgVisible, a1ImgHash, a2Limit, a2Dist, a2ImgVisible, a2ImgHash,
            upLimit, upDist, up2Limit, up2Dist,
        )
        val app = ctx.applicationContext
        runCatching { io.execute { appendSignalLocked(app, row) } }
    }

    /** Append one raw view-tree dump row (`ts` + joined dump). No-op unless [NavLog.verbose] or dump empty. */
    fun logViews(ctx: Context, dump: List<String>) {
        if (!NavLog.verbose || dump.isEmpty()) return
        val row = buildViewsRow(System.currentTimeMillis(), dump)
        val app = ctx.applicationContext
        runCatching { io.execute { appendViewsLocked(app, row) } }
    }

    private fun appendSignalLocked(app: Context, row: String) {
        val w = ensureSignalWriter(app) ?: return
        runCatching {
            w.appendLine(row)
            w.flush()
        }
    }

    private fun appendViewsLocked(app: Context, row: String) {
        val w = ensureViewsWriter(app) ?: return
        runCatching {
            w.appendLine(row)
            w.flush()
        }
    }

    private fun ensureSignalWriter(app: Context): BufferedWriter? {
        signalWriter?.let { return it }
        runCatching {
            val f = File(app.getExternalFilesDir(null), "vietmap_signal_$startTs.csv")
            signalWriter = f.bufferedWriter().also { it.appendLine(HEADER) }
            signalPath = f.absolutePath
        }
        return signalWriter
    }

    private fun ensureViewsWriter(app: Context): BufferedWriter? {
        viewsWriter?.let { return it }
        runCatching {
            val f = File(app.getExternalFilesDir(null), "vietmap_views_$startTs.csv")
            viewsWriter = f.bufferedWriter().also { it.appendLine(VIEWS_HEADER) }
            viewsPath = f.absolutePath
        }
        return viewsWriter
    }
}
