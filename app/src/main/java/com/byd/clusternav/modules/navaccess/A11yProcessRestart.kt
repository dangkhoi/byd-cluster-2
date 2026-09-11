package com.byd.clusternav.modules.navaccess

import android.content.Context
import android.util.Log
import com.byd.clusternav.AdbKeys
import com.byd.clusternav.Prefs
import com.byd.clusternav.carexec.LocalDeviceShell
import com.byd.clusternav.carexec.LocalShellRetry
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Đường CỨU CUỐI cho phím-thoại: **khởi động lại tiến trình app** để AMS nhả trạng thái bind kẹt.
 *
 * ## Vì sao phải có (đo trên xe owner 2026-09-11)
 * Owner báo "mất kết nối phím thoại". Đo trên xe (`dumpsys accessibility`):
 * ```
 * Bound services:{Service[label=.custom.StatusBarAcces…]}                  ← KHÔNG có ClusterNav
 * Enabled services:{… com.byd.clusternav2/…NavAccessibilityService …}      ← vẫn được bật
 * Binding services:{{com.byd.clusternav2/…NavAccessibilityService}}        ← KẸT ở đây
 * ConnectionRecord{… CR FGSA DEAD com.byd.clusternav2/…NavAccessibilityService}  (×2)
 * ```
 * Thứ tự đã thử, có bằng chứng từng bước:
 *  1. Toggle danh sách (đường tự-chữa từ 2026-08-14) → `force-rebind xong: bound=false`. **Không cứu được.**
 *  2. Gỡ HẲN component khỏi `enabled_accessibility_services` → `Binding services` **vẫn còn** nó ⇒ trạng thái
 *     kẹt nằm trong `system_server`, không nằm ở setting ⇒ mọi cách ghi setting đều vô ích.
 *  3. `am force-stop com.byd.clusternav2` → `Binding services:{}` ⇒ **chỉ tiến trình chết mới nhả**. Mở lại app
 *     → `Bound services` có `Service[label=ClusterNav — booster đọ…]`, log `accessibility booster connected`.
 * Tác nhân: tiến trình app chết trong lúc đang bound (ROM giết vì thiếu bộ nhớ, hoặc một lần `am force-stop`).
 * Nên nó tái diễn được, và cách chữa duy nhất đã đo được là (3).
 *
 * ## Vì sao chuỗi lệnh TÁCH RỜI (`nohup … &`) chứ không phải AlarmManager
 * `am force-stop` giết chính tiến trình đang phát lệnh, nên không code nào của mình chạy sau đó — và nó **huỷ
 * luôn mọi alarm/PendingIntent/job của gói**, nên mẹo `UpdateRelaunch` (alarm đặt trước, dùng cho OTA `pm
 * install -r`, thứ KHÔNG huỷ alarm) KHÔNG dùng lại được ở đây. Lệnh được chạy trong shell của adbd (uid 2000,
 * tiến trình KHÁC app), nên `nohup … &` (đã chuyển hướng stdout/stderr) sống tiếp sau khi socket dadb đứt: nó
 * ngủ 1 giây (đủ để mình trả lời caller), force-stop, chờ 3 giây rồi mở lại app — đúng chuỗi đã đo tay ở bước
 * (3), cộng hai lần thử lại có kiểm tra (xem [restartCommand]).
 *
 * ⚠ [SUY] chỗ duy nhất chưa đo được off-car: chuỗi tách rời có SỐNG SÓT qua lúc adbd đóng phiên trên ROM này
 * không. Điểm an tâm: phiên dadb bị đóng **ngay** sau khi phát lệnh (~50 ms), tức TRƯỚC cả `sleep 1` — nên nếu
 * ROM giết chuỗi cùng phiên thì `force-stop` KHÔNG BAO GIỜ chạy: hỏng theo hướng "không làm gì cả", chứ không
 * phải "chết mà không mở lại". Cửa sổ xấu (sống qua force-stop rồi chết trước khi mở lại) đòi chuỗi phải sống
 * ~1 giây rồi mới bị giết bởi một thứ không tồn tại. Bằng chứng chốt được việc này chỉ có trên xe: bấm "Kiểm
 * tra / Sửa ngay" ở trạng thái kẹt và xem app có tự mở lại sau ~4 giây (logcat: "ĐÃ phát lệnh khởi động lại"
 * → tiến trình mới → "accessibility booster connected").
 *
 * ## Cổng an toàn
 *  • MỘT lần cho mỗi tiến trình ([armed]) — không bao giờ tự giết mình hai lần trong một đời tiến trình.
 *  • Cooldown bền [Prefs.a11yRestartAtMs] ([COOLDOWN_MS]), ghi ĐỒNG BỘ ngay trước khi phát lệnh và CHỈ khi đã
 *    mở được phiên dadb — nếu khởi động lại mà vẫn kẹt thì KHÔNG vòng lặp giết-mở-giết; owner còn nút "Kiểm
 *    tra / Sửa ngay" và cách cuối là khởi động lại xe.
 *  • Chỉ gọi khi [AccessibilityRebind.healStep] = `RESTART_PROCESS` **được xác nhận bằng HAI lần đọc dumpsys**
 *    cách nhau ~2,5 s (`NavConnect.awaitBoundThenDecide`): `Binding services` cũng chứa component của một lần
 *    bind ĐANG CHẠY bình thường, nên một lần đọc không phân biệt được "kẹt" với "bind chậm" — và bind chậm hay
 *    xảy ra đúng lúc nổ máy. KHÔNG chạy vì một lần đọc lỗi (xem fail-safe của
 *    [AccessibilityRebind.isBindingStuck] = `false`).
 *  • Trong CHÍNH chuỗi lệnh: đúng MỘT `force-stop`; mọi lần thử lại chỉ MỞ app (gác bằng `pidof`).
 */
object A11yProcessRestart {

    private const val TAG = "A11yRestart"

    /** Khoảng cách tối thiểu giữa hai lần tự khởi động lại (bền qua các lần chạy). */
    const val COOLDOWN_MS = 10 * 60_000L

    /** Đợi 1 s trước khi force-stop để caller kịp trả kết quả/toast cho owner. */
    private const val PRE_KILL_DELAY_S = 1

    /** Đợi 3 s sau force-stop rồi mở lại (đúng nhịp đã đo tay trên xe). */
    private const val POST_KILL_DELAY_S = 3

    /** Đợi giữa hai lần thử mở lại (đủ để tiến trình kịp hiện trong `pidof`). */
    private const val RELAUNCH_RETRY_DELAY_S = 4

    private val armed = AtomicBoolean(false)

    /** Test-only: xả cờ một-lần giữa các test (state process-global trong object). */
    internal fun resetForTest() = armed.set(false)

    /** PURE (device-free): đã ra ngoài cooldown chưa (0 = chưa từng khởi động lại). */
    internal fun outsideCooldown(nowMs: Long, lastAtMs: Long, cooldownMs: Long = COOLDOWN_MS): Boolean =
        lastAtMs == 0L || nowMs - lastAtMs >= cooldownMs

    /**
     * PURE (device-free, unit-tested): chuỗi shell tách rời làm việc (3) — ngủ, force-stop, ngủ, mở lại, **kiểm
     * tra và mở lại nữa nếu chưa lên**.
     *
     * Một chuỗi DUY NHẤT trong `sh -c` để nó tự chạy tiếp sau khi app chết; `nohup` + `&` để adbd đóng socket
     * (lúc app chết) không SIGHUP giết nó.
     *
     * ## Vì sao có 3 lần mở lại chứ không 1 (sửa review v1.40)
     * `am force-stop` đặt gói vào trạng thái **stopped**: không nhận broadcast nữa (kể cả `BOOT_COMPLETED` ở
     * lần nổ máy sau) cho tới khi có người mở app bằng tay. Nên "chết mà KHÔNG mở lại được" không phải một lỗi
     * nhỏ — nó tắt câm mọi tính năng nền (phím-thoại · ghế · lọc bụi · nav) tới khi owner tự bấm icon. Vì cả
     * chuỗi này chỉ có MỘT cơ hội (force-stop huỷ alarm/job của gói ⇒ không có lưới an toàn nào khác), nó tự
     * kiểm bằng `pidof` và thử lại bằng đường CÒN LẠI:
     *  1. `am start -n <pkg>/<launcher>` — tất định, cùng công thức đã chạy trên xe ở `CastShell` (và
     *     MainActivity `exported=true` nên uid shell được phép). Mở **activity launcher** là quan trọng: đó là
     *     thao tác xoá trạng thái *stopped* (giống owner bấm icon), khác với `am startservice`.
     *  2. chưa lên ⇒ `monkey -p <pkg> -c android.intent.category.LAUNCHER 1` — đường đã chạy trên xe ở
     *     `VietMapAutostart`, không cần biết tên class.
     *  3. vẫn chưa lên ⇒ thử lại (1) một lần nữa.
     * KHÔNG BAO GIỜ force-stop lần thứ hai trong chuỗi này: mọi lần thử lại chỉ MỞ, nên không có cách nào rơi
     * vào vòng giết-mở-giết bên trong một lệnh.
     *
     * @param launcherActivity tên class activity launcher (đã resolve từ `PackageManager`), `null` ⇒ chỉ dùng
     *   `monkey` (không đoán tên class — đoán sai thì `am start` báo lỗi và mất một lượt thử).
     */
    internal fun restartCommand(pkg: String, launcherActivity: String? = null): String {
        val amStart = launcherActivity?.let {
            "am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $pkg/$it"
        }
        val monkey = "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
        // Thứ tự: đường tất định trước (nếu resolve được), rồi đường không-cần-class, rồi lặp lại đường đầu.
        val attempts = if (amStart != null) listOf(amStart, monkey, amStart) else listOf(monkey, monkey)
        val body = StringBuilder("sleep $PRE_KILL_DELAY_S; am force-stop $pkg; sleep $POST_KILL_DELAY_S; ")
        body.append(attempts.first())
        attempts.drop(1).forEach { next ->
            // `pidof` thành công ⇒ đã sống ⇒ bỏ qua các lần thử còn lại (|| chỉ chạy khi CHƯA có tiến trình).
            body.append("; sleep $RELAUNCH_RETRY_DELAY_S; pidof $pkg >/dev/null 2>&1 || ").append(next)
        }
        return "nohup sh -c '$body' >/dev/null 2>&1 &"
    }

    /**
     * Tên class activity launcher của chính app, đọc từ `PackageManager` (KHÔNG hardcode): tên sai thì
     * `am start` mất một lượt thử. `null` khi không resolve được ⇒ [restartCommand] chỉ dùng `monkey`.
     */
    private fun launcherActivityOf(app: Context): String? = runCatching {
        app.packageManager.getLaunchIntentForPackage(app.packageName)?.component
            ?.takeIf { it.packageName == app.packageName }
            ?.className
    }.getOrNull()

    /**
     * Phát lệnh khởi động lại tiến trình. Trả `true` nếu đã phát được lệnh (app sẽ chết sau ~1 giây và tự mở
     * lại sau ~4 giây), `false` nếu bị cổng an toàn chặn hoặc không mở được phiên dadb.
     *
     * Chạy trên thread nền của caller (mở phiên dadb). KHÔNG ném: mọi lỗi được log và trả `false`.
     */
    fun restart(ctx: Context, reason: String): Boolean {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        if (!outsideCooldown(now, Prefs.a11yRestartAtMs(app))) {
            Log.w(TAG, "bỏ qua khởi động lại ($reason): còn trong cooldown ${COOLDOWN_MS}ms")
            return false
        }
        if (!armed.compareAndSet(false, true)) {
            Log.w(TAG, "bỏ qua khởi động lại ($reason): tiến trình này đã khởi động lại một lần")
            return false
        }
        val cmd = restartCommand(app.packageName, launcherActivityOf(app))
        return runCatching {
            val keys = AdbKeys.ensure(app)
            val ok = LocalDeviceShell.session(keys, LocalShellRetry.BACKGROUND_READ_CAP) { sh ->
                // Đóng dấu cooldown NGAY TRƯỚC khi phát lệnh, và ghi ĐỒNG BỘ (commit, không apply): app chết vì
                // SIGKILL sau ~1 giây — `apply()` chỉ xếp lịch ghi đĩa trên thread khác và SIGKILL KHÔNG chờ
                // hàng đợi đó, nên mốc có thể bốc hơi, và mốc bốc hơi = mất lớp chặn vòng lặp giết-mở-giết QUA
                // các tiến trình. Đặt TRONG phiên (không phải trước khi mở phiên) để ca dadb KHÔNG mở được (chưa
                // bấm "Cho phép gỡ lỗi USB" / emulator) không đốt oan 10 phút cooldown của owner.
                Prefs.setA11yRestartAtMs(app, now)
                sh(cmd)
                true
            } ?: false
            if (ok) Log.w(TAG, "ĐÃ phát lệnh khởi động lại tiến trình ($reason) — app sẽ tự mở lại sau ~4s")
            else Log.e(TAG, "không mở được phiên dadb để khởi động lại ($reason)")
            ok
        }.getOrElse {
            Log.e(TAG, "khởi động lại tiến trình LỖI ($reason)", it)
            false
        }
    }
}
