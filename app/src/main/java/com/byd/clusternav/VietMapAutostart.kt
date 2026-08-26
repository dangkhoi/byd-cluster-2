package com.byd.clusternav

import android.content.Context
import android.util.Log
import com.byd.clusternav.carexec.LocalDeviceShell
import com.byd.clusternav.carexec.LocalShellRetry
import com.byd.clusternav.navigation.NavApps

/**
 * Auto-start VietMap để widget/notification có nguồn speed-limit (badge cụm mirror). Dùng chung cho 2 case:
 *  • BOOT headless ([BootSetupService]) → sau khi start, VỀ HOME (không đè launcher; app mình vốn không foreground).
 *  • Mở app ([MainActivity.onCreate]) → sau khi start, đưa ClusterNav lại TRƯỚC (user đang xem app mình).
 *
 * SỬA 2 bug on-car (2026-08-21): (1) guard cũ dùng runningAppProcesses (Android 10+ chỉ thấy process mình → luôn
 * relaunch); nay dùng `pidof` qua dadb (uid shell, tin cậy cross-app) → CHỈ start khi CHƯA chạy. (2) không để VietMap
 * đè: sau start thì trả foreground về đúng chỗ (HOME cho boot / ClusterNav cho app-open). Chạy NỀN, degrade-safe.
 */
object VietMapAutostart {
    private const val TAG = "VietMapAutostart"
    /** §7 — KHÔNG chép lại tên gói: roster ở [NavApps] là nguồn sự thật duy nhất. */
    const val PKG = NavApps.VIETMAP_LIVE

    /**
     * @param returnToSelfPkg  package đưa lại foreground sau khi start VietMap; null = về HOME (boot headless).
     * Non-blocking (spawn thread) — cho case MỞ APP. Boot headless nên dùng [runNow] (đồng bộ, giữ FGS sống).
     */
    fun ensureRunning(ctx: Context, returnToSelfPkg: String?) {
        val app = ctx.applicationContext
        Thread { runNow(app, returnToSelfPkg) }.start()
    }

    /**
     * ĐỒNG BỘ (block thread gọi) — dùng cho [BootSetupService] để foreground-service giữ tiến trình sống tới khi
     * xong (nếu spawn thread rời, process có thể bị kill sau finish()). No-op nếu badge tắt / VietMap chưa cài / đã chạy.
     */
    fun runNow(ctx: Context, returnToSelfPkg: String?) {
        val app = ctx.applicationContext
        if (!Prefs.badgeEnabled(app)) return
        if (runCatching { app.packageManager.getLaunchIntentForPackage(PKG) }.getOrNull() == null) return  // chưa cài
        runCatching {
            val keys = AdbKeys.ensure(app)
            LocalDeviceShell.session(keys, LocalShellRetry.BACKGROUND_READ_CAP) { sh ->
                if (sh("pidof $PKG").output.trim().isNotEmpty()) {
                    Log.i(TAG, "VietMap đã chạy → bỏ auto-start (không đè)")
                } else {
                    sh("monkey -p $PKG -c android.intent.category.LAUNCHER 1")   // start CHỈ khi chưa chạy
                    Thread.sleep(1500)                                            // chờ process VietMap lên (widget có nguồn)
                    if (returnToSelfPkg != null) {
                        sh("monkey -p $returnToSelfPkg -c android.intent.category.LAUNCHER 1")   // đưa ClusterNav lại trước
                    } else {
                        sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME")  // boot: về launcher, không đè
                    }
                    Log.i(TAG, "VietMap chưa chạy → đã start + trả foreground (${returnToSelfPkg ?: "HOME"})")
                }
                Unit
            }
        }.onFailure { Log.w(TAG, "auto-start VietMap failed: ${it.message}") }
    }
}
