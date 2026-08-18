package com.byd.clusternav

import android.content.Context

/**
 * Cheap IN-MEMORY gate for verbose diagnostic logging.
 *
 * OTA ships a RELEASE apk (no `BuildConfig.DEBUG`) and the owner debugs on-car via logcat, so verbosity is a
 * RUNTIME flag defaulting OFF — flipped by the visible "Thu thập dữ liệu chẩn đoán" settings switch OR the
 * hidden long-press on the version label (MainActivity) and mirrored here so per-frame hot paths (BydHal
 * keep-alive, ClusterBroadcaster / NavArrowLog / NavDistanceLog, ManeuverSignature.note) read a `@Volatile`
 * field instead of hitting SharedPreferences ~4×/second.
 *
 * The persisted source of truth is [Prefs.navVerboseLog]; [init] refreshes this mirror at the app entry points
 * that always run (MainActivity.onCreate, NavNotificationListener.onListenerConnected) and the D5 toggle keeps
 * both in sync.
 */
object NavLog {
    @Volatile
    var verbose = false

    /** Refresh the in-memory gate from the persisted flag. Call at entry points that always run. */
    fun init(ctx: Context) {
        // Verbose is controlled ONLY by the persisted pref, DEFAULT FALSE — so normal use collects NO logs /
        // PNGs / screenshots. The old `|| BuildConfig.DEBUG` auto-on was REMOVED (owner 2026-08-18): the debug
        // data-collection build force-enabled verbose, which filled the car's storage (7 GB+ nav_arrow_pngs +
        // diag screenshots + CSVs) on every normal drive. Data-collection is now opt-in via the visible settings
        // switch ("Thu thập dữ liệu chẩn đoán") or the hidden long-press on the version label; the storage cap
        // ([DiagStorageCap]) is the always-on backstop for the occasional data-collection drive.
        verbose = Prefs.navVerboseLog(ctx)
    }
}
