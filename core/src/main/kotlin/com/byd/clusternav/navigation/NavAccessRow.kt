package com.byd.clusternav.navigation

import com.byd.clusternav.core.CsvEscape

/**
 * Pure builder for the `nav_access` telemetry CSV (multi-source voice-guidance capture). Lives in :core so
 * the row/column shape — in particular the SOURCE `pkg` column that separates GMaps / VietMap / Waze /
 * WazeMod rows, and the `text` column that carries the announced voice-guidance string — is unit-tested
 * off-car. [com.byd.clusternav.NavAccessLog] (:app) owns the Android file I/O and delegates row formatting
 * here so the two never drift.
 *
 * Why the columns:
 *  • `pkg`      — event.packageName. GMaps posts rich nav notifications AND exposes on-screen distance; the
 *                 other three post NO nav notifications and draw the map in a SurfaceView (empty a11y tree),
 *                 so their ONLY same-device nav signal is the accessibility ANNOUNCEMENT text. Tagging the
 *                 source lets the four be told apart off-car.
 *  • `screenRead_m` — GMaps screen-scan distance ground-truth ([NO_METERS] when not read, e.g. announcement
 *                 rows from any package).
 *  • `text`     — the announced / window-content voice-guidance string (blank for pure GMaps screen-scan rows).
 */
object NavAccessRow {
    /** -1 = "no on-screen distance read" (only GMaps' screen-scan path fills a real value). */
    const val NO_METERS = -1

    const val HEADER = "t_ms,pkg,screenRead_m,screenRead_road,screenRead_maneuverHint,text"

    fun row(
        tMs: Long,
        pkg: String,
        screenReadMeters: Int,
        road: String,
        maneuverHint: String,
        text: String,
    ): String = CsvEscape.row(
        listOf(tMs.toString(), pkg, screenReadMeters.toString(), road, maneuverHint, text),
    )
}
