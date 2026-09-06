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

    // ── B2 (on-car 2026-09-06): CHỐNG LOOP autostart ────────────────────────────────────────────
    // Bug: [ensureRunning]/[runNow] không có dedup/cooldown; bóng bật ⇒ mỗi onCreate (mở app · recreate khi
    // đổi ngôn ngữ/giao diện · auto-open lúc boot) chạy "launch VietMap → sleep 1500 → trả ClusterNav" ⇒
    // VietMap nhảy foreground rồi lùi = "loop flash" owner thấy. Vá bằng 3 lớp: (a) cooldown + in-flight ở đây;
    // (b) bỏ launch nếu VietMap ĐÃ foreground (trong [runNow]); (c) không gọi lúc recreate (MainActivity gate).
    /** Khoảng cách tối thiểu giữa 2 lần autostart. Trong cửa sổ này (recreate/mở lại nhanh) ⇒ KHÔNG launch lại. */
    const val COOLDOWN_MS = 30_000L
    private val inFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastRunAtMs = 0L

    /** PURE (device-free, unit-tested): [nowMs] đã ra ngoài cooldown so với [lastRunAtMs] chưa (0 = chưa từng chạy). */
    internal fun outsideCooldown(nowMs: Long, lastRunAtMs: Long, cooldownMs: Long = COOLDOWN_MS): Boolean =
        lastRunAtMs == 0L || nowMs - lastRunAtMs >= cooldownMs

    /**
     * PURE (device-free, unit-tested) — từ output của `dumpsys activity activities | grep <pkg>`, VietMap có
     * **BẢN GHI ACTIVITY** (activity record / task / recent) trong hệ thống window chưa.
     *
     * ⚠ SỬA on-car v1.33→v1.34 (bóng): guard cũ chỉ bỏ launch khi VietMap đang **RESUMED** (foreground). Nhưng
     * khi mở ClusterNav, ClusterNav mới là foreground nên VietMap KHÔNG resumed ⇒ nhánh bóng LUÔN relaunch dù
     * VietMap đã mở sẵn (activity đã dựng) ⇒ owner thấy VietMap giật/relaunch, bóng không lên. Bóng của bản mod
     * chỉ cần activity ĐÃ TỪNG DỰNG (đang chạy nền) — không cần resumed. Nên nếu ĐÃ có bản ghi activity ⇒ bóng đã
     * init ⇒ KHÔNG cần launch lại.
     *
     * `dumpsys activity activities` liệt kê stack/task/recents của ACTIVITY (KHÔNG liệt kê service/widget), nên
     * grep theo gói: có dòng tham chiếu component `pkg/…` (ActivityRecord{…pkg/.X}, realActivity=pkg/…,
     * baseActivity=…pkg/…) HOẶC dòng `ActivityRecord`/`Task{`/`Hist ` kèm pkg ⇒ CÓ activity record. Output rỗng
     * (chỉ process service/widget, không activity) ⇒ chưa init bóng ⇒ vẫn nên launch.
     */
    internal fun hasActivityRecord(dumpsysActivitiesGrep: String, pkg: String = PKG): Boolean {
        if (dumpsysActivitiesGrep.isBlank()) return false
        return dumpsysActivitiesGrep.lineSequence().any { line ->
            line.contains("$pkg/") ||
                (line.contains(pkg) && (line.contains("ActivityRecord") || line.contains("Task{") || line.contains("Hist ")))
        }
    }

    /**
     * Giành 1 suất chạy: trả `true` nếu được phép tiếp tục (đánh dấu in-flight + đóng dấu thời gian). Trả
     * `false` nếu ĐANG có phiên chạy (in-flight) HOẶC còn trong [COOLDOWN_MS]. Thành công ⇒ caller PHẢI gọi
     * [finishRun] khi xong (dùng `try/finally`). `internal` để test off-car lái được trọn vòng gate.
     */
    internal fun tryBeginRun(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false      // đã có phiên đang chạy
        if (!outsideCooldown(nowMs, lastRunAtMs)) {                  // còn trong cooldown
            inFlight.set(false)
            return false
        }
        lastRunAtMs = nowMs
        return true
    }

    /** Nhả suất chạy (gọi trong `finally` của [runNow]). */
    internal fun finishRun() {
        inFlight.set(false)
    }

    /** Test-only: xả state gate giữa các test (state process-global trong object). */
    internal fun resetGateForTest() {
        inFlight.set(false)
        lastRunAtMs = 0L
    }

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
        // (a) CHỐNG LOOP (B2, on-car 2026-09-06): chỉ MỘT phiên chạy tại một thời điểm + cooldown giữa hai lần.
        // onCreate/recreate(đổi ngôn ngữ/giao diện)/boot bắn dồn ⇒ chỉ lần đầu đi qua; các lần trong COOLDOWN_MS bị
        // bỏ (khỏi lặp "launch → sleep 1500 → trả foreground" = flash loop owner thấy). finishRun() ở finally.
        if (!tryBeginRun()) {
            Log.i(TAG, "autostart: bỏ qua (đang chạy hoặc trong cooldown ${COOLDOWN_MS}ms) — chống loop onCreate/recreate/boot")
            return
        }
        try {
        runCatching {
            val keys = AdbKeys.ensure(app)
            // sessionResult (KHÔNG phải session): [LocalDeviceShell.session] nuốt lỗi MỞ PHIÊN thành `null` IM
            // LẶNG (nó chỉ map Failed→null, KHÔNG ném), nên `onFailure` bên dưới CHỈ bắt được ngoại lệ thật (vd
            // AdbKeys.ensure) — KHÔNG bắt được ca dadb không nối được localhost:5555, mà đó CHÍNH là dạng hỏng của
            // Bug 2 cần chẩn đoán trên xe. Đọc kết quả để log LÝ DO đã phân loại (PORT_CLOSED / AWAITING_APPROVAL /
            // IO_ERROR…). KHÔNG đổi hành vi thực thi: session() vốn gọi cùng sessionResult() rồi vứt Failed.
            val result = LocalDeviceShell.sessionResult(keys, LocalShellRetry.BACKGROUND_READ_CAP) { sh ->
                val running = sh("pidof $PKG").output.trim().isNotEmpty()
                // (b) VietMap ĐÃ ở foreground rồi → launch lại chỉ gây "giật" (flash), không cần. Đọc activity
                // đang resumed/focus; degrade-safe (đọc lỗi / grep vắng ⇒ coi như KHÔNG-foreground ⇒ giữ hành vi
                // cũ = vẫn launch). Chỉ có ý nghĩa khi process đang sống (running).
                val foreground = running && runCatching {
                    sh("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity|ResumedActivity'").output.contains(PKG)
                }.getOrDefault(false)
                if (foreground) {
                    Log.i(TAG, "autostart: VietMap đã ở foreground (running=$running) — bỏ launch (khỏi giật)")
                    return@sessionResult
                }
                if (castDefault) {
                    // CAST-default ⇒ VietMap phải ACTIVE để đường cast chiếu lên cụm. LUÔN launch activity — kể cả
                    // process đã sống (widget/service): pidof chỉ biết PROCESS, KHÔNG biết activity/nav đang mở.
                    // KHÔNG trả foreground (để VietMap active cho cast).
                    sh("monkey -p $PKG -c android.intent.category.LAUNCHER 1")
                    Log.i(TAG, "autostart CAST-default → launch VietMap ACTIVE (process đã chạy=$running)")
                } else {
                    // SILENT background (badge tốc độ / bóng VietMap).
                    // ⚠ BÓNG VietMap: bản mod chỉ hiện bóng lên CỤM khi VietMap Ở BACKGROUND, và cần ACTIVITY đã
                    //   dựng — `pidof` chỉ biết PROCESS (service/widget), KHÔNG biết activity đã mở chưa.
                    // BADGE-only: chỉ cần PROCESS sống (widget speed-limit); đã sống ⇒ GIỮ NGUYÊN (tránh churn).
                    val bubbleOn = Prefs.vmBubbleEnabled(app)
                    // (c) SỬA on-car v1.33→v1.34: bóng bật + VietMap ĐÃ có bản ghi activity (đã init, đang chạy nền)
                    //   ⇒ KHÔNG relaunch. Guard `foreground` phía trên vô dụng cho ca này vì mở ClusterNav thì
                    //   ClusterNav mới là foreground, VietMap không resumed ⇒ nhánh bóng cũ LUÔN relaunch (flash,
                    //   bóng không lên — bug owner báo on-car). Chỉ đọc khi process đang sống; degrade-safe: đọc
                    //   dumpsys lỗi/không nối được ⇒ hasActivity=false ⇒ rơi về hành vi cũ (vẫn launch).
                    val hasActivity = running && runCatching {
                        hasActivityRecord(sh("dumpsys activity activities | grep -E '$PKG'").output)
                    }.getOrDefault(false)
                    if (bubbleOn && hasActivity) {
                        Log.i(TAG, "autostart silent-bg (bóng): VietMap đã có bản ghi activity trong stack (running=$running) — bỏ launch, bóng đã init (chống relaunch/flash on-car v1.33)")
                    } else if (bubbleOn || !running) {
                        // BẬT BÓNG (chưa có activity record) HOẶC process chưa sống: LUÔN launch activity rồi ĐƯA VỀ
                        // NỀN (returnToSelfPkg=app-open ClusterNav / HOME=boot) — bất kể pidof — để bóng chắc chắn
                        // init rồi hiện khi VietMap ở nền.
                        sh("monkey -p $PKG -c android.intent.category.LAUNCHER 1")
                        Thread.sleep(1500)
                        if (returnToSelfPkg != null) sh("monkey -p $returnToSelfPkg -c android.intent.category.LAUNCHER 1")
                        else sh("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
                        Log.i(TAG, "autostart silent-bg → launch activity VietMap + trả nền (${returnToSelfPkg ?: "HOME"}) [bubbleOn=$bubbleOn running=$running hasActivity=$hasActivity] ⇒ VietMap ở nền để bóng hiện")
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
        } finally {
            finishRun()   // (a) nhả suất chạy dù thành công hay ném — lần autostart kế mới vào được sau cooldown
        }
    }
}
