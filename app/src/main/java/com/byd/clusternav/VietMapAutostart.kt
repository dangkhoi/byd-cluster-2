package com.byd.clusternav

import android.content.Context
import android.util.Log
import com.byd.clusternav.carexec.LocalDeviceShell
import com.byd.clusternav.carexec.LocalShellResult
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
     * xong (nếu spawn thread rời, process có thể bị kill sau finish()). No-op nếu CẢ badge tốc độ LẪN toggle bong
     * bóng VietMap đều tắt / VietMap chưa cài / đã chạy.
     */
    fun runNow(ctx: Context, returnToSelfPkg: String?) {
        val app = ctx.applicationContext
        // Tín hiệu CAST-MẶC-ĐỊNH: VietMap có phải app tự-chiếu-lên-cụm không. Đọc THẲNG pref "clustercast/autoCast"
        // (KHÔNG phụ thuộc singleton ClusterCast đã load chưa — runNow chạy từ boot/nền). Cặp file/khoá PHẢI khớp
        // producer [ClusterCast.save] / [ClusterCast.loadPrefs] (PREF="clustercast", key "autoCast", String) — đổi
        // một bên phải đổi bên kia.
        val castDefault = runCatching {
            app.getSharedPreferences("clustercast", Context.MODE_PRIVATE).getString("autoCast", "") == PKG
        }.getOrDefault(false)
        val silentReason = Prefs.badgeEnabled(app) || Prefs.vmBubbleEnabled(app)   // badge tốc độ / bong bóng
        if (!castDefault && !silentReason) return                                  // không lý do nào ⇒ thôi
        if (runCatching { app.packageManager.getLaunchIntentForPackage(PKG) }.getOrNull() == null) return  // chưa cài
        // Log QUYẾT ĐỊNH (TRƯỚC dadb) — verify được cả khi dadb fail (vd emulator): nhánh nào + vì tín hiệu nào.
        Log.i(TAG, "autostart quyết định: castDefault=$castDefault badge=${Prefs.badgeEnabled(app)} bubble=${Prefs.vmBubbleEnabled(app)} → ${if (castDefault) "ACTIVE" else "silent-bg"}")
        runCatching {
            val keys = AdbKeys.ensure(app)
            // sessionResult (KHÔNG phải session): [LocalDeviceShell.session] nuốt lỗi MỞ PHIÊN thành `null` IM
            // LẶNG (nó chỉ map Failed→null, KHÔNG ném), nên `onFailure` bên dưới CHỈ bắt được ngoại lệ thật (vd
            // AdbKeys.ensure) — KHÔNG bắt được ca dadb không nối được localhost:5555, mà đó CHÍNH là dạng hỏng của
            // Bug 2 cần chẩn đoán trên xe. Đọc kết quả để log LÝ DO đã phân loại (PORT_CLOSED / AWAITING_APPROVAL /
            // IO_ERROR…). KHÔNG đổi hành vi thực thi: session() vốn gọi cùng sessionResult() rồi vứt Failed.
            val result = LocalDeviceShell.sessionResult(keys, LocalShellRetry.BACKGROUND_READ_CAP) { sh ->
                val running = sh("pidof $PKG").output.trim().isNotEmpty()
                if (castDefault) {
                    // CAST-default ⇒ VietMap phải ACTIVE để đường cast chiếu lên cụm. LUÔN launch activity — kể cả
                    // process đã sống (widget/service): pidof chỉ biết PROCESS, KHÔNG biết activity/nav đang mở.
                    // KHÔNG trả foreground (để VietMap active cho cast).
                    sh("monkey -p $PKG -c android.intent.category.LAUNCHER 1")
                    Log.i(TAG, "autostart CAST-default → launch VietMap ACTIVE (process đã chạy=$running)")
                } else {
                    // SILENT background (badge tốc độ / bóng VietMap).
                    // ⚠ BÓNG VietMap: bản mod chỉ hiện bóng lên CỤM khi VietMap Ở BACKGROUND, và cần ACTIVITY/nav đã
                    //   mở — `pidof` chỉ biết PROCESS (service/widget) chứ KHÔNG biết activity đã mở chưa; process
                    //   sống mà activity chưa mở ⇒ bóng KHÔNG init/không hiện (bug on-car 2026-09-05). Vì vậy khi
                    //   BẬT BÓNG: LUÔN launch activity rồi ĐƯA VỀ NỀN (returnToSelfPkg=app-open ClusterNav / HOME=boot)
                    //   — bất kể pidof — để bóng chắc chắn init rồi hiện khi VietMap ở nền.
                    // BADGE-only: chỉ cần PROCESS sống (widget speed-limit); đã sống ⇒ GIỮ NGUYÊN (tránh churn).
                    val bubbleOn = Prefs.vmBubbleEnabled(app)
                    if (bubbleOn || !running) {
                        sh("monkey -p $PKG -c android.intent.category.LAUNCHER 1")
                        Thread.sleep(1500)
                        if (returnToSelfPkg != null) sh("monkey -p $returnToSelfPkg -c android.intent.category.LAUNCHER 1")
                        else sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                        Log.i(TAG, "autostart silent-bg → launch activity VietMap + trả nền (${returnToSelfPkg ?: "HOME"}) [bubbleOn=$bubbleOn running=$running] ⇒ VietMap ở nền để bóng hiện")
                    } else {
                        Log.i(TAG, "autostart silent-bg (badge-only) → VietMap process đã sống, giữ nguyên")
                    }
                }
                Unit
            }
            // Phiên dadb KHÔNG mở được (Bug 2 trên xe / emulator không có loopback) — session() sẽ nuốt thành null,
            // nên phải log tường minh ở đây để hiện trường biết VietMap CHƯA auto-start và VÌ SAO.
            if (result is LocalShellResult.Failed) {
                Log.w(TAG, "autostart: phiên dadb KHÔNG mở được (${result.reason}, ${result.attempts} lần thử) — VietMap CHƯA auto-start (localhost:5555 chưa sẵn?)")
            }
        }.onFailure { Log.w(TAG, "auto-start VietMap failed: ${it.message}") }
    }
}
