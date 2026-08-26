package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WIRING contract for the DIAG log-export path (a teammate drive-tests VietMap/Waze and grabs the logs with NO
 * adb/laptop). Like [VoiceKeyAdbApprovalWiringTest] it reads the source across the boundary because the runtime
 * needs a real `Context` (`AdbKeys.ensure` → `filesDir`, a dadb loopback socket) and `:app` has no Robolectric.
 *
 * It locks three things so a future edit can't silently break the tester's export:
 *  1. [NavLogExport] copies the app's OWN diag dir to the file-manager-visible `/sdcard/Download/ClusterNavLog`
 *     over the SAME loopback shell every other helper uses, with the anti-hang [LocalShellRetry.BACKGROUND_READ_CAP].
 *  2. Turning the "Thu thập dữ liệu chẩn đoán" switch OFF flushes to sdcard (so a tester who forgets still gets it).
 *  3. A `com.byd.clusternav.EXPORT_LOGS` broadcast triggers it too (RECEIVER_EXPORTED so `am broadcast` reaches it).
 *
 * It does NOT change any log WRITE path — the 10 getExternalFilesDir writers + DiagStorageCap are untouched.
 */
class NavLogExportContractTest {

    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative) else current.resolve("app").resolve(relative)
    }

    private fun read(relative: String) = app("src/main/java/com/byd/clusternav/$relative").toFile().readText()

    private val navLogExport by lazy { read("NavLogExport.kt") }
    private val mainActivity by lazy { read("MainActivity.kt") }
    private val navAccess by lazy { read("modules/navaccess/NavAccessibilityService.kt") }

    // ── 1) NavLogExport: dest path + loopback shell + anti-hang cap ───────────────────────────────
    @Test
    fun `export targets the file-manager-visible download folder`() {
        assertTrue(
            navLogExport.contains("fun exportToSharedStorage(ctx: Context): String?"),
            "public entry point with the documented signature",
        )
        assertTrue(
            navLogExport.contains("\"/sdcard/Download/ClusterNavLog\""),
            "dest is the file-manager-visible /sdcard/Download/ClusterNavLog (no adb needed to grab it)",
        )
    }

    @Test
    fun `export uses the loopback shell with the background anti-hang cap`() {
        assertTrue(
            navLogExport.contains("LocalDeviceShell.session(") &&
                navLogExport.contains("LocalShellRetry.BACKGROUND_READ_CAP"),
            "must reuse LocalDeviceShell.session with BACKGROUND_READ_CAP (1 try + 30s read cap, no retry — nobody watching)",
        )
        assertTrue(navLogExport.contains("AdbKeys.ensure("), "keys via AdbKeys.ensure like every other loopback caller")
        assertTrue(
            navLogExport.contains("getExternalFilesDir(null)"),
            "copies FROM the app's external files dir (the unchanged log WRITE location)",
        )
    }

    // ── 2) Trigger A: toggling collection OFF flushes to sdcard on a background thread ────────────
    @Test
    fun `toggling diag logging off exports to sdcard off the main thread`() {
        val start = mainActivity.indexOf("private fun setDiagLogging(on: Boolean)")
        assertTrue(start >= 0, "setDiagLogging exists (~the visible switch + hidden long-press route here)")
        val end = mainActivity.indexOf("private fun maybeShowDisclaimer", start)
        val body = mainActivity.substring(start, if (end > start) end else mainActivity.length)
        assertTrue(body.contains("if (!on)"), "export runs ONLY when turning collection OFF")
        assertTrue(body.contains("Thread {"), "export runs on a background thread (opens a socket, not on UI thread)")
        assertTrue(
            body.contains("NavLogExport.exportToSharedStorage"),
            "the OFF branch flushes the collected logs to the shared folder",
        )
    }

    // ── 3) Trigger B: EXPORT_LOGS broadcast (am broadcast -a com.byd.clusternav.EXPORT_LOGS) ───────
    @Test
    fun `accessibility service registers an exported EXPORT_LOGS receiver that exports off-thread`() {
        assertTrue(navAccess.contains("registerExportLogs()"), "registered on service connect")
        val start = navAccess.indexOf("private fun registerExportLogs()")
        assertTrue(start >= 0, "registerExportLogs defined")
        val body = navAccess.substring(start)
        assertTrue(
            body.contains("\"com.byd.clusternav.EXPORT_LOGS\""),
            "listens for the documented broadcast action",
        )
        assertTrue(
            body.contains("Context.RECEIVER_EXPORTED"),
            "RECEIVER_EXPORTED on SDK33+ so `am broadcast` from adb can reach it (NOT_EXPORTED would be unreachable)",
        )
        assertTrue(body.contains("Thread {"), "the copy runs on a background thread, not the a11y callback thread")
        assertTrue(
            body.contains("NavLogExport.exportToSharedStorage"),
            "the receiver delegates to the single export implementation",
        )
    }

    @Test
    fun `export receiver is unregistered on unbind`() {
        assertTrue(
            navAccess.contains("exportLogsReceiver?.let { unregisterReceiver(it) }"),
            "receiver cleaned up on unbind (mirrors the debug-window-dump receiver lifecycle)",
        )
    }
}
