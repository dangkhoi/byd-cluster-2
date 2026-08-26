package com.byd.clusternav

import android.content.Context
import android.util.Log
import com.byd.clusternav.carexec.LocalDeviceShell
import com.byd.clusternav.carexec.LocalShellRetry

/**
 * COPY the app's diagnostic data to a file-manager-visible folder so a teammate can grab the logs off the car
 * WITHOUT adb/laptop.
 *
 * The verbose diagnostics (CSV / PNG / screenshots) are WRITTEN to `getExternalFilesDir(null)` — that is
 * UNCHANGED here (the 10 log writers and [DiagStorageCap] stay exactly as they are). The problem this solves is
 * only ACCESS: `Android/data/<pkg>/files` is awkward to reach from the head unit's file manager, so this class
 * copies those files to [DEST] (`/sdcard/Download/ClusterNavLog`), which any file manager lists.
 *
 * HOW: the same on-device dadb loopback shell every other helper uses ([LocalDeviceShell.session] with
 * [LocalShellRetry.BACKGROUND_READ_CAP] — 1 attempt + a 30 s read cap so a silent socket can't hang the caller,
 * NO retry: nobody is watching). The shell runs as uid 2000, which can read the app's external files dir (group
 * `ext_data_rw`, see [com.byd.clusternav.screencapture.ScreenCaptureTransport]) and write `/sdcard/Download`.
 *
 * Degrade-safe: any failure (no external dir, shell can't connect, first-run "Allow USB debugging" not yet
 * tapped) yields `null`. Returns the destination path on success. Call OFF the main thread (it opens a socket).
 */
object NavLogExport {
    private const val TAG = "NavLogExport"

    /** File-manager-visible destination. uid 2000 can write here; any file manager lists /sdcard/Download. */
    const val DEST = "/sdcard/Download/ClusterNavLog"

    /**
     * Copy everything under `getExternalFilesDir(null)` into [DEST]. Returns [DEST] when the loopback session
     * opened and ran the copy, or `null` on any failure. Does NOT touch the log WRITE path.
     */
    fun exportToSharedStorage(ctx: Context): String? = runCatching {
        val app = ctx.applicationContext
        val srcDir = app.getExternalFilesDir(null) ?: return null
        val src = srcDir.absolutePath
        val keys = AdbKeys.ensure(app)
        LocalDeviceShell.session(keys, LocalShellRetry.BACKGROUND_READ_CAP) { sh ->
            // Single-quote BOTH paths. `$src` is system-derived (getExternalFilesDir → a path built from the
            // fixed package name, so no shell metacharacters today), but quoting keeps the no-injection property
            // LOCAL and future-proof rather than relying on that non-local invariant; `$DEST` is a constant.
            sh("mkdir -p '$DEST'")
            // `<src>/.` copies the CONTENTS of the diag dir (not the dir itself); `2>/dev/null` so a missing
            // file mid-copy doesn't abort. Read-only copy — the originals under getExternalFilesDir stay put.
            sh("cp -r '$src/.' '$DEST/' 2>/dev/null")
            Log.i(TAG, "exported diag logs → $DEST (from $src)")
            DEST
        }
    }.getOrElse {
        Log.w(TAG, "export to $DEST failed: ${it.message}")
        null
    }
}
