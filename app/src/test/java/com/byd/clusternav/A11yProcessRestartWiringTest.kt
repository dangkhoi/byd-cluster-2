package com.byd.clusternav

import com.byd.clusternav.modules.navaccess.A11yProcessRestart
import com.byd.clusternav.testsupport.KotlinSource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Đường CỨU CUỐI của phím-thoại (v1.40): khi AMS kẹt component ở `Binding services` thì **khởi động lại tiến
 * trình**, vì ghi setting vô ích.
 *
 * Bằng chứng đã đo trên xe owner 2026-09-11 (owner: *"sao mất kết nối phím thoại vậy"*):
 * `Bound services` không có ClusterNav · `Binding services` CÓ · 2 `ConnectionRecord … FGSA DEAD` · toggle →
 * `force-rebind xong: bound=false` · gỡ hẳn component khỏi setting → vẫn nằm trong `Binding services` ·
 * `am force-stop` → `Binding services:{}` → mở lại app → `Bound services` có `ClusterNav — booster đọ…` +
 * log `accessibility booster connected`.
 *
 * Quyết định thuần nằm ở `:core` ([com.byd.clusternav.modules.navaccess.AccessibilityRebind.healStep],
 * unit-test trong `AccessibilityStuckBindTest`). Test này khoá phần WIRING ở `:app` (Android + dadb, không có
 * Robolectric ⇒ đọc source, comment bị strip trước) cộng phần thuần của chính
 * [A11yProcessRestart] (chuỗi lệnh + cooldown) chạy thật.
 */
class A11yProcessRestartWiringTest {

    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative) else current.resolve("app").resolve(relative)
    }

    private fun read(relative: String) = app("src/main/java/com/byd/clusternav/$relative").toFile().readText()

    private val navConnect by lazy { KotlinSource.stripComments(read("NavConnect.kt")) }
    private val restart by lazy { KotlinSource.stripComments(read("modules/navaccess/A11yProcessRestart.kt")) }
    private val mainActivity by lazy { KotlinSource.stripComments(read("MainActivity.kt")) }
    private val boot by lazy { KotlinSource.stripComments(read("BootSetupService.kt")) }
    private val prefs by lazy { KotlinSource.stripComments(read("Prefs.kt")) }

    // ───────────── 1. Chuỗi lệnh khởi động lại: đúng thứ tự, tách rời, tự mở lại ─────────────

    @Test
    fun `lenh khoi dong lai - force-stop ROI mo lai, chay tach roi khoi tien trinh minh`() {
        val cmd = A11yProcessRestart.restartCommand("com.byd.clusternav2")
        val kill = cmd.indexOf("am force-stop com.byd.clusternav2")
        val relaunch = cmd.indexOf("monkey -p com.byd.clusternav2")
        assertTrue(kill > 0, "phải có force-stop chính gói mình (thứ DUY NHẤT đo được là cứu được)")
        assertTrue(relaunch > kill, "phải mở lại app SAU khi force-stop, không thì app nằm chết")
        assertTrue(cmd.startsWith("nohup "), "phải nohup: adbd đóng socket lúc app chết sẽ SIGHUP giết chuỗi lệnh")
        assertTrue(cmd.trimEnd().endsWith("&"), "phải chạy nền (&) để lệnh sống tiếp sau khi phiên dadb đứt")
        assertTrue(kill > cmd.indexOf("sleep"), "phải ngủ trước khi giết để caller kịp trả kết quả/toast")
    }

    @Test
    fun `mo lai co KIEM TRA va thu lai - khong bao gio de app chet ma khong len`() {
        // `am force-stop` đặt gói vào trạng thái stopped ⇒ mất cả BOOT_COMPLETED lần sau ⇒ "chết mà không mở
        // lại" là hỏng nặng nhất của đường này, và chuỗi lệnh là cơ hội DUY NHẤT (force-stop huỷ alarm/job).
        val cmd = A11yProcessRestart.restartCommand("com.byd.clusternav2", "com.byd.clusternav.MainActivity")
        val amStart = cmd.indexOf("am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.byd.clusternav2/com.byd.clusternav.MainActivity")
        val kill = cmd.indexOf("am force-stop com.byd.clusternav2")
        assertTrue(amStart > kill, "đường tất định (am start -n) chạy sau khi giết")
        assertEquals(
            2,
            Regex("""pidof com\.byd\.clusternav2 >/dev/null 2>&1 \|\|""").findAll(cmd).count(),
            "mỗi lần thử lại phải được gác bằng pidof (chỉ mở lại khi CHƯA có tiến trình), và phải có 2 lần thử lại",
        )
        assertTrue(cmd.indexOf("monkey -p com.byd.clusternav2") > amStart, "monkey là đường DỰ PHÒNG sau am start")
    }

    @Test
    fun `KHONG BAO GIO giet lan thu hai trong cung mot chuoi lenh`() {
        // Bên trong một lệnh không được có khả năng rơi vào vòng giết-mở-giết: mọi lần thử lại chỉ MỞ.
        for (cmd in listOf(
            A11yProcessRestart.restartCommand("com.byd.clusternav2"),
            A11yProcessRestart.restartCommand("com.byd.clusternav2", "com.byd.clusternav.MainActivity"),
        )) {
            assertEquals(1, Regex("""am force-stop""").findAll(cmd).count(), "đúng MỘT lần force-stop: $cmd")
        }
    }

    @Test
    fun `khong resolve duoc activity launcher thi KHONG doan ten class`() {
        // Đoán sai tên class ⇒ `am start` báo lỗi và mất một lượt thử. Không có tên ⇒ chỉ dùng monkey.
        val cmd = A11yProcessRestart.restartCommand("com.byd.clusternav2", null)
        assertFalse(cmd.contains("am start"), "không có tên class thì không phát am start")
        assertTrue(cmd.contains("monkey -p com.byd.clusternav2"), "vẫn còn đường monkey")
    }

    @Test
    fun `KHONG dung AlarmManager cho duong nay (force-stop huy alarm cua goi)`() {
        assertFalse(
            restart.contains("UpdateRelaunch") || restart.contains("AlarmManager"),
            "mẹo alarm của OTA không dùng lại được: am force-stop huỷ mọi alarm/PendingIntent của gói",
        )
    }

    // ───────────── 2. Cổng an toàn: một lần mỗi tiến trình + cooldown bền ─────────────

    @BeforeEach
    fun resetGate() = A11yProcessRestart.resetForTest()

    @Test
    fun `cooldown - lan dau cho chay, trong cooldown thi khong`() {
        assertTrue(A11yProcessRestart.outsideCooldown(nowMs = 1_000L, lastAtMs = 0L), "chưa từng restart ⇒ cho chạy")
        assertFalse(A11yProcessRestart.outsideCooldown(nowMs = 1_000L, lastAtMs = 999L))
        assertTrue(
            A11yProcessRestart.outsideCooldown(nowMs = A11yProcessRestart.COOLDOWN_MS + 1_000L, lastAtMs = 1_000L),
        )
    }

    @Test
    fun `cooldown du dai de khong thanh vong lap giet-mo-giet`() {
        assertTrue(
            A11yProcessRestart.COOLDOWN_MS >= 5 * 60_000L,
            "khởi động lại mà vẫn kẹt thì phải chờ đủ lâu, không được quay vòng",
        )
    }

    @Test
    fun `moc cooldown duoc GHI TRUOC khi phat lenh`() {
        val write = restart.indexOf("Prefs.setA11yRestartAtMs(app, now)")
        val issue = restart.indexOf("sh(cmd)")
        assertTrue(write in 0 until issue, "app chết ngay sau khi phát lệnh ⇒ không còn cơ hội ghi pref sau đó")
        // …nhưng phải NẰM TRONG phiên dadb: mở phiên không được ⇒ chưa giết ai ⇒ không đốt oan 10 phút cooldown.
        val session = restart.indexOf("LocalDeviceShell.session")
        assertTrue(session in 0 until write, "mốc chỉ được đóng khi đã mở được phiên (sắp phát lệnh thật)")
    }

    @Test
    fun `moc cooldown ghi DONG BO - apply se bi SIGKILL cuop mat`() {
        // `apply()` xếp lịch ghi đĩa trên thread khác; `am force-stop` gửi SIGKILL sau ~1 s và KHÔNG chờ hàng
        // đợi đó ⇒ mốc có thể bốc hơi ⇒ mất lớp chặn vòng lặp giết-mở-giết QUA các tiến trình.
        val setter = prefs.substring(prefs.indexOf("fun setA11yRestartAtMs"))
            .substringBefore("\n    fun ")
        assertTrue(setter.contains(".commit()"), "setter của mốc này phải commit (đồng bộ)")
        assertFalse(setter.contains(".apply()"), "không được apply (bất đồng bộ)")
    }

    @Test
    fun `moc cooldown la pref BEN (long), khong phai co trong RAM`() {
        assertTrue(prefs.contains("fun a11yRestartAtMs(ctx: Context): Long"), "phải đọc được sau khi tiến trình mới sống lại")
        assertTrue(prefs.contains("fun setA11yRestartAtMs"), "và ghi được")
    }

    // ───────────── 3. NavConnect: không toggle mù khi kẹt, và báo caller escalate ─────────────

    @Test
    fun `NavConnect doc dumpsys MOT lan roi quyet dinh qua healStep cua core`() {
        assertTrue(navConnect.contains("AccessibilityRebind.healStep("), "quyết định phải nằm ở hàm thuần đã test")
        assertTrue(navConnect.contains("AccessibilityRebind.isBindingStuck("), "phải đọc trạng thái kẹt")
        val dumpRead = navConnect.indexOf("val dump = sh(\"dumpsys accessibility\").output")
        val step = navConnect.indexOf("AccessibilityRebind.healStep(")
        assertTrue(dumpRead in 0 until step, "đọc dump trước, quyết định sau (một lần đọc cho cả hai cờ)")
    }

    @Test
    fun `ket thi KHONG toggle - tra ve NEEDS_PROCESS_RESTART`() {
        val stuckBranch = navConnect.indexOf("RebindStep.RESTART_PROCESS ->")
        val toggleLog = navConnect.indexOf("ENABLED nhưng CHƯA BOUND")
        assertTrue(stuckBranch in 0 until toggleLog, "nhánh kẹt phải THOÁT trước khi chạm tới chuỗi toggle")
        assertTrue(
            navConnect.contains("return HealOutcome.NEEDS_PROCESS_RESTART"),
            "phải báo caller escalate, không im lặng chịu thua",
        )
    }

    @Test
    fun `dau hieu ket o LAN DOC DAU cung phai xac nhan lan 2 truoc khi giet tien trinh`() {
        // Vì sao BẮT BUỘC: AMS giữ component trong `Binding services` từ lúc `bindService` tới lúc
        // `onServiceConnected` về ⇒ một lần bind BÌNH THƯỜNG mà chậm trông y như trạng thái kẹt. Ca này có thật
        // ĐÚNG lúc nổ máy (AMS bind service — chính nó dựng tiến trình mình — trong khi BOOT_COMPLETED tới gần
        // như cùng lúc, nên BootSetupService chạy lúc connected=false). Escalate từ MỘT lần đọc = tự giết app ở
        // mỗi lần nổ máy chậm.
        val branch = navConnect.substring(
            navConnect.indexOf("AccessibilityRebind.RebindStep.RESTART_PROCESS -> {"),
        ).substringBefore("AccessibilityRebind.RebindStep.TOGGLE -> Unit")
        assertTrue(
            branch.contains("awaitBoundThenDecide(sh)"),
            "nhánh kẹt ở lần đọc ĐẦU phải chờ + đọc lại, không được return NEEDS_PROCESS_RESTART ngay",
        )
        val decide = branch.indexOf("awaitBoundThenDecide(sh)")
        val escalate = branch.indexOf("return HealOutcome.NEEDS_PROCESS_RESTART")
        assertTrue(decide in 0 until escalate, "xác nhận TRƯỚC, kết luận SAU")
        assertTrue(
            branch.contains("AccessibilityRebind.RebindStep.TOGGLE ->"),
            "và nếu AMS đã nhả (không còn kẹt) thì rơi về đường toggle proven, KHÔNG giết tiến trình",
        )
    }

    @Test
    fun `toggle xong phai CHO roi DOC LAI truoc khi escalate (chong duong tinh gia)`() {
        // `onServiceConnected` bất đồng bộ: đọc dumpsys NGAY sau lệnh re-add sẽ thấy "chưa bound" cho một ca
        // đang bind BÌNH THƯỜNG. Nếu escalate theo lần đọc sớm đó thì đường boot sẽ TỰ GIẾT APP dù không kẹt.
        assertTrue(
            navConnect.contains("return when (awaitBoundThenDecide(sh)) {"),
            "sau toggle phải đi qua cửa sổ xác nhận, KHÔNG kết luận từ một lần đọc tức thì",
        )
        val fn = navConnect.substring(navConnect.indexOf("private fun awaitBoundThenDecide"))
            .substringBefore("\n    /**")
        assertTrue(fn.contains("NavAccessibilitySource.connected"), "chờ bằng cờ TRONG tiến trình (không tốn lệnh shell)")
        assertTrue(fn.contains("REBIND_CONFIRM_MS"), "cửa sổ chờ có HẠN (không chờ vô hạn trong phiên dadb)")
        assertTrue(
            fn.contains("val dump = sh(\"dumpsys accessibility\").output"),
            "hết hạn chờ thì ĐỌC LẠI dumpsys một lần nữa rồi mới quyết",
        )
        assertTrue(
            fn.contains("AccessibilityRebind.healStep(bound, stuck)"),
            "quyết bằng hàm thuần đã test, không tự viết lại điều kiện",
        )
    }

    @Test
    fun `chua bound ma Binding RONG thi KHONG giet tien trinh`() {
        // Chỉ chữ ký ĐÃ ĐO trên xe (chưa bound + CÒN trong `Binding services`) mới được phép escalate.
        val afterToggle = navConnect.substring(navConnect.indexOf("return when (awaitBoundThenDecide(sh)) {"))
            .substringBefore("} catch (e: InterruptedException)")
        val toggleBranch = afterToggle.indexOf("RebindStep.TOGGLE -> {")
        assertTrue(toggleBranch > 0, "phải xử lý tường minh ca chưa-bound-không-kẹt sau toggle")
        val tail = afterToggle.substring(toggleBranch)
        assertTrue(tail.contains("HealOutcome.FAILED"), "ca đó trả FAILED (báo owner), KHÔNG phải NEEDS_PROCESS_RESTART")
        assertFalse(
            tail.contains("NEEDS_PROCESS_RESTART"),
            "không được escalate cho một trạng thái chưa từng đo được là chữa bằng khởi động lại",
        )
    }

    @Test
    fun `grantAccessibility cu van chay (khong pha call site nao)`() {
        assertTrue(
            navConnect.contains("fun grantAccessibility(ctx: Context, reset: Boolean = false, onResult: ((Boolean) -> Unit)? = null)"),
            "API cũ giữ nguyên chữ ký, chỉ uỷ quyền sang heal()",
        )
        assertTrue(navConnect.contains("onResult?.invoke(outcome == HealOutcome.BOUND)"), "map BOUND→true cho caller cũ")
    }

    // ───────────── 4. Nút "Kiểm tra / Sửa ngay" + lúc mở app ─────────────

    @Test
    fun `nut Sua ngay di duong heal va HOI truoc khi khoi dong lai`() {
        val btn = mainActivity.indexOf("btn_voicekey_recheck")
        assertTrue(btn > 0)
        val body = mainActivity.substring(btn, btn + 2200)
        assertTrue(body.contains("NavConnect.heal(applicationContext, reset = true)"), "nút phải dùng kết quả có phân loại")
        assertTrue(body.contains("NEEDS_PROCESS_RESTART -> promptA11yProcessRestart()"), "kẹt ⇒ mở dialog xin khởi động lại")
        assertTrue(
            mainActivity.contains("private fun promptA11yProcessRestart()"),
            "phải HỎI owner (app sẽ tắt rồi tự mở lại — không được làm ngầm khi owner đang xem)",
        )
        val dialog = mainActivity.substring(mainActivity.indexOf("private fun promptA11yProcessRestart()"))
        assertTrue(dialog.contains("AlertDialog.Builder(this)"), "dialog dựng bằng CODE — không chạm layout byte-seal")
        assertTrue(dialog.contains("A11yProcessRestart"), "nút Khởi động lại gọi đúng đường cứu")
        assertTrue(dialog.contains("Lang.t("), "song ngữ VI/EN như phần còn lại của app")
    }

    @Test
    fun `mo app - phim thoai chua bound thi tu phat hien va hoi ngay`() {
        assertTrue(
            mainActivity.contains("NavConnect.heal(applicationContext, reset = false) { outcome ->"),
            "onResume phải dùng heal để biết ca kẹt",
        )
        val at = mainActivity.indexOf("NavConnect.heal(applicationContext, reset = false) { outcome ->")
        assertTrue(
            mainActivity.substring(at, at + 300).contains("promptA11yProcessRestart()"),
            "kẹt ⇒ hỏi ngay khi mở app, không để owner tự mò nút",
        )
    }

    @Test
    fun `mo app LAN DAU cung phat hien duoc - onCreate khong de mat ca ket vi dua single-flight`() {
        // ĐUA thật: onCreate gọi heal trước onResume vài ms; single-flight `grantingAcc` cho lần SAU trả FAILED
        // NGAY ⇒ nhánh onResume (nơi có dialog) LUÔN thua ở ca mở app lần đầu — đúng ca quan trọng nhất (mở app
        // sau khi nổ máy / sau khi app tự khởi động lại). Nên lần THẮNG cũng phải biết mở dialog.
        val onCreate = mainActivity.substring(
            mainActivity.indexOf("override fun onCreate(savedInstanceState: Bundle?)"),
        ).substringBefore("\n    private fun ")
        assertTrue(onCreate.contains("NavConnect.heal(applicationContext)"), "onCreate dùng kết quả có phân loại")
        val at = onCreate.indexOf("NavConnect.heal(applicationContext)")
        assertTrue(
            onCreate.substring(at, at + 220).contains("promptA11yProcessRestart()"),
            "và cũng mở dialog khi kẹt, không im lặng",
        )
    }

    @Test
    fun `boot - co quyet dinh GIET tien trinh phai la co ATOMIC (ghi main looper, doc thread boot)`() {
        assertTrue(
            boot.contains("val needA11yProcessRestart = java.util.concurrent.atomic.AtomicBoolean(false)"),
            "callback của heal chạy trên main looper, vòng setup đọc trên thread boot — cần rào bộ nhớ thật",
        )
        assertTrue(boot.contains("needA11yProcessRestart.get()"), "đọc qua API atomic")
    }

    @Test
    fun `gat cong tac phim-thoai OFF sang ON o ca ket cung hoi, khong chi SAI duong`() {
        // Changelog 1.30 dạy owner nghi thức "gạt TẮT rồi BẬT" khi phím-thoại chết. Ở trạng thái AMS kẹt nghi
        // thức đó KHÔNG cứu được; toast cũ lại chỉ owner đi "bấm Allow USB debugging" — một việc vô ích.
        val toggle = mainActivity
            .substringAfter("Prefs.setVoiceKeyEnabled(this, on)")
            .substringBefore("// Voice-key BINDING STATUS")
        assertTrue(toggle.contains("NavConnect.heal(applicationContext, reset = true)"), "nghi thức OFF→ON đi đường heal")
        assertTrue(
            toggle.contains("NEEDS_PROCESS_RESTART -> promptA11yProcessRestart()"),
            "ca kẹt phải mở dialog xin khởi động lại, KHÔNG hiện toast chỉ sai đường",
        )
        assertTrue(
            toggle.contains("HealOutcome.FAILED ->") && toggle.contains("Allow USB debugging"),
            "toast 'Allow USB debugging' chỉ còn dùng cho ca FAILED (đúng nghĩa: chưa cấp quyền adb)",
        )
    }

    // ───────────── 5. Boot: tự khởi động lại, nhưng ở BƯỚC CUỐI ─────────────

    @Test
    fun `boot tu khoi dong lai (khong hoi) vi owner khong o man hinh`() {
        assertTrue(boot.contains("needA11yProcessRestart"), "boot phải ghi nhận nhu cầu")
        assertTrue(boot.contains("A11yProcessRestart.restart(applicationContext, reason = \"boot\")"))
        assertFalse(boot.contains("promptA11yProcessRestart"), "boot headless không dựng được dialog")
    }

    @Test
    fun `boot chay restart SAU cac buoc con lai (khong giet chuoi setup giua duong)`() {
        val heal = boot.indexOf("NavConnect.heal(applicationContext)")
        val vietmap = boot.indexOf("VietMapAutostartService.startForBoot")
        val assistant = boot.indexOf("AssistantLauncher.setSystemAssistant")
        val doRestart = boot.indexOf("A11yProcessRestart.restart(")
        assertTrue(heal in 0 until doRestart)
        assertTrue(vietmap in 0 until doRestart, "autostart VietMap phải chạy TRƯỚC khi giết tiến trình")
        assertTrue(assistant in 0 until doRestart, "trợ lý Gemini cũng vậy")
        assertTrue(doRestart < boot.indexOf("finish(startId)", doRestart), "và restart trước khi service tự dừng")
    }

    @Test
    fun `so lan restart trong mot doi tien trinh la MOT`() {
        assertTrue(restart.contains("armed.compareAndSet(false, true)"), "một lần mỗi tiến trình")
        assertEquals(1, Regex("""armed\.compareAndSet\(false, true\)""").findAll(restart).count())
    }
}
