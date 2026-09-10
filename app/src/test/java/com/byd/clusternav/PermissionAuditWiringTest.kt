package com.byd.clusternav

import com.byd.clusternav.navigation.NavApps
import com.byd.clusternav.permissions.PermissionAuditRunner
import com.byd.clusternav.testsupport.KotlinSource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Wiring của bộ **kiểm tra quyền** (spec `docs/specs/permission-health-audit.html`, v1.39).
 *
 * Thay cho `VmFloatWhitelistWiringTest` (v1.35): công thức whitelist float/overlay đã CHUYỂN từ
 * [VietMapAutostart] sang [PermissionAuditRunner] và bỏ cờ một-lần `vm_float_whitelist_applied`. Bug on-car
 * v1.38 mà đợt này sửa: owner bật bóng VietMap, mở ClusterNav → autostart chạy nhưng bóng vẫn dính toast
 * *"Hệ thống IVI không hỗ trợ hoạt động này"*, vì (a) cờ một-lần đã true từ lần cài trước nên công thức không
 * áp lại dù cài lại bản mod đã XOÁ appop `SYSTEM_ALERT_WINDOW`, (b) `settings put` bị từ chối không ném nên cờ
 * vẫn được set dù lệnh trượt, (c) vá xong không dựng lại VietMap nên bóng (chỉ init lúc khởi động) vẫn thiếu.
 *
 * Runtime cần Android (Context, dadb, appops) và `:app` không có Robolectric ⇒ khoá wiring bằng cách ĐỌC
 * SOURCE, comment bị strip trước ([KotlinSource]) nên một câu trong comment không thể làm hợp đồng xanh.
 * Phần thuần (cooldown/single-flight, luật cần-gì, parser) khoá bằng unit test thật:
 * `core/…/PermissionAuditTest` + các test gate ở cuối file này.
 */
class PermissionAuditWiringTest {

    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative) else current.resolve("app").resolve(relative)
    }

    private fun read(relative: String) = app("src/main/java/com/byd/clusternav/$relative").toFile().readText()

    private val runner by lazy { KotlinSource.stripComments(read("permissions/PermissionAuditRunner.kt")) }
    private val prompt by lazy { KotlinSource.stripComments(read("permissions/PermissionPrompt.kt")) }
    private val autostart by lazy { KotlinSource.stripComments(read("VietMapAutostart.kt")) }
    private val autostartSvc by lazy { KotlinSource.stripComments(read("VietMapAutostartService.kt")) }
    private val assistant by lazy { KotlinSource.stripComments(read("modules/voicekey/AssistantLauncher.kt")) }
    private val boot by lazy { KotlinSource.stripComments(read("BootSetupService.kt")) }
    private val mainActivity by lazy { KotlinSource.stripComments(read("MainActivity.kt")) }
    private val prefs by lazy { KotlinSource.stripComments(read("Prefs.kt")) }

    // ─────────────────────────── 1. ĐỌC TRƯỚC — đã cấp thì KHÔNG cấp lại ───────────────────────────

    @Test
    fun `doc byd_float_app_list TRUOC khi ghi`() {
        val get = runner.indexOf("settings get global \$FLOAT_LIST_KEY")
        val put = runner.indexOf("settings put global \$FLOAT_LIST_KEY")
        assertTrue(get in 0 until put, "phải ĐỌC danh sách float trước khi ghi (đọc-trước, không cấp lại)")
    }

    @Test
    fun `doc trang thai TRUOC khi appops set — VietMap doc bang shell, app minh doc in-process`() {
        val get = runner.indexOf("appops get \$vm \$op")
        val set = runner.indexOf("appops set \$vm \$op allow")
        assertTrue(get in 0 until set, "phải đọc appop của VietMap trước khi cấp")
        // Của CHÍNH app thì đọc in-process (Settings.canDrawOverlays) — rẻ hơn, không cần dadb; nên trước
        // `appops set $self` không có `appops get $self`, mà phải có phép đọc in-process + cổng "đang thiếu".
        assertTrue(
            runner.contains("Settings.canDrawOverlays(ctx)") && runner.contains("overlayGranted(app)"),
            "quyền overlay của chính app đọc in-process, không tốn lệnh shell",
        )
        val setSelf = runner.indexOf("appops set \$self \$op allow")
        assertTrue(
            runner.indexOf("if (Grant.SELF_OVERLAY in missingBefore)") in 0 until setSelf,
            "chỉ cấp appop cho chính app khi phép đọc in-process nói là ĐANG THIẾU",
        )
        // Và sau khi cấp thì đọc lại bằng shell để xác nhận (canDrawOverlays trong tiến trình này có thể còn cache).
        assertTrue(runner.indexOf("appops get \$self \$op", setSelf) > setSelf, "cấp xong phải đọc lại xác nhận")
    }

    @Test
    fun `doc doze whitelist TRUOC khi them`() {
        val get = runner.indexOf("dumpsys deviceidle whitelist")
        val add = runner.indexOf("cmd deviceidle whitelist +")
        assertTrue(get in 0 until add, "phải đọc doze whitelist trước khi thêm gói")
    }

    @Test
    fun `du het thi RETURN truoc moi lenh ghi (khong phat lenh nao)`() {
        // Cổng: missingBefore rỗng ⇒ return ngay, KHÔNG chạm settings put / appops set / deviceidle +.
        val guard = runner.indexOf("if (missingBefore.isEmpty())")
        assertTrue(guard >= 0, "phải có cổng 'thiếu rỗng ⇒ thôi'")
        listOf(
            "settings put global \$FLOAT_LIST_KEY",
            "appops set \$self \$op allow",
            "appops set \$vm \$op allow",
            "cmd deviceidle whitelist +",
        ).forEach { write ->
            val at = runner.indexOf(write)
            assertTrue(at > guard, "lệnh ghi '$write' phải nằm SAU cổng đủ-thì-thôi")
        }
        // Và cổng đó phải THOÁT thật (return) chứ không chỉ log.
        val body = runner.substring(guard, runner.indexOf('\n', runner.indexOf("return ShellPhase", guard)) + 1)
        assertTrue(body.contains("return ShellPhase"), "cổng đủ-thì-thôi phải return, không đi tiếp xuống phần vá")
    }

    @Test
    fun `khong can shell thi KHONG mo phien dadb`() {
        val guard = runner.indexOf("if (!PermissionAudit.needsShellSession(required, okInProcess))")
        val session = runner.indexOf("LocalDeviceShell.sessionResult(keys")
        assertTrue(guard in 0 until session, "cổng 'không cần shell' phải đứng TRƯỚC khi mở phiên dadb")
        assertTrue(runner.indexOf("AdbKeys.ensure(app)") > guard, "cũng không sinh khoá adb khi không cần")
    }

    // ─────────────────────────── 2. Ghi rồi ĐỌC LẠI xác nhận (read-back) ───────────────────────────

    @Test
    fun `sau khi ghi float list phai doc lai va xac nhan bang FloatAppList contains`() {
        val put = runner.indexOf("settings put global \$FLOAT_LIST_KEY")
        val reread = runner.indexOf("settings get global \$FLOAT_LIST_KEY", put)
        assertTrue(reread > put, "phải đọc lại danh sách sau khi ghi (settings put bị từ chối KHÔNG ném)")
        assertTrue(
            runner.contains("FloatAppList.contains(after, self)") && runner.contains("FloatAppList.contains(after, vm)"),
            "xác nhận 'đã landed' bằng nội dung đọc lại, không tin lệnh ghi",
        )
    }

    @Test
    fun `sau khi cap appop phai doc lai appops get de xac nhan`() {
        val setVm = runner.indexOf("appops set \$vm \$op allow")
        val getAfter = runner.indexOf("appops get \$vm \$op", setVm)
        assertTrue(getAfter > setVm, "phải đọc lại appop sau khi cấp")
    }

    @Test
    fun `sau khi them doze phai doc lai dumpsys de xac nhan`() {
        val add = runner.indexOf("cmd deviceidle whitelist +")
        val getAfter = runner.indexOf("dumpsys deviceidle whitelist", add)
        assertTrue(getAfter > add, "phải đọc lại doze whitelist sau khi thêm")
    }

    @Test
    fun `tro ly he thong cung phai DOC LAI, khong tin chuoi loi rong`() {
        // setSystemAssistant chỉ báo "phiên dadb chạy được" — nó KHÔNG soi exit code từng lệnh, mà
        // `settings put secure …` bị từ chối thì in lỗi ra stdout chứ không ném. Nên err rỗng KHÔNG đủ.
        assertTrue(
            runner.contains("val confirmed = assistantReadBack(app)"),
            "phải đọc lại trạng thái trợ lý sau khi chạy recipe",
        )
        assertTrue(
            runner.contains("if (err.isEmpty() && confirmed != false) fixed += Grant.ASSISTANT"),
            "chỉ tính là đã vá khi recipe không lỗi VÀ phép đọc lại không phản đối",
        )
        // null = ROM không cho đọc khoá ⇒ KHÔNG được coi là thiếu (nếu không, popup nhắc mỗi lần mở app trên
        // một xe thật ra đang chạy tốt — lỗi UX nặng nhất của tính năng này).
        assertTrue(
            runner.contains("if (assistant == null && voiceService == null) return null"),
            "đọc không được thì trả null (không kết luận), không trả false",
        )
    }

    // ─────────────────────────── 3. Merge dùng chung — KHÔNG clobber gói khác ───────────────────────────

    @Test
    fun `ca hai caller dung chung FloatAppList merge (DRY, khong clobber)`() {
        assertTrue(
            assistant.contains("FloatAppList.merge(cur, listOf(PKG_GSA, PKG_BARD, app.packageName))"),
            "AssistantLauncher phải gọi helper dùng chung",
        )
        assertTrue(
            runner.contains("FloatAppList.merge(fresh, append)"),
            "bộ audit phải gọi cùng helper FloatAppList.merge (append, không ghi đè)",
        )
        assertFalse(
            assistant.contains("filter { it.isNotEmpty() && it != \"null\" }"),
            "không còn bản merge chép tay ở AssistantLauncher",
        )
    }

    @Test
    fun `appops nham dung goi VietMap`() {
        assertTrue(runner.contains("val vm = NavApps.VIETMAP_LIVE"), "gói VietMap lấy từ roster NavApps, không chép tay")
        assertEquals("vn.vietmap.live", NavApps.VIETMAP_LIVE)
    }

    @Test
    fun `VietMap chua cai thi khong doi quyen cua no (doc PackageManager, khong tin co)`() {
        // Cờ badge/bóng là opt-in và VẪN BẬT sau khi owner gỡ VietMap. Quyền của một gói không tồn tại thì
        // `appops set` / `deviceidle +` đều trượt ⇒ đòi nó = popup vĩnh viễn owner không làm gì được.
        assertTrue(
            runner.contains("vmInstalled = runCatching {") &&
                runner.contains("app.packageManager.getLaunchIntentForPackage(NavApps.VIETMAP_LIVE) != null"),
            "phải hỏi PackageManager (cùng phép thử VietMapAutostart dùng), không suy từ prefs",
        )
        assertTrue(
            autostart.contains("app.packageManager.getLaunchIntentForPackage(PKG)"),
            "và đó đúng là phép thử autostart đang dùng — hai bên không được lệch",
        )
    }

    @Test
    fun `ghi float list phai merge tren ban doc SAT LUC GHI (chong lost-update voi AssistantLauncher)`() {
        // `byd_float_app_list` là khoá TOÀN CỤC mà AssistantLauncher cũng read-modify-write (thêm Google/Gemini),
        // và nó chạy SONG SONG mỗi lần mở app khi có binding Gemini. Ghi bằng bản đọc từ đầu phiên có thể xoá mất
        // mục bên kia vừa thêm ⇒ phải đọc lại NGAY trước khi ghi (thu cửa sổ tranh chấp xuống ~1 ms).
        val put = runner.indexOf("settings put global \$FLOAT_LIST_KEY")
        val freshRead = runner.lastIndexOf("val fresh = sh(\"settings get global \$FLOAT_LIST_KEY\")", put)
        assertTrue(freshRead in 0 until put, "phải đọc lại danh sách ngay trước khi ghi")
        assertFalse(
            runner.contains("FloatAppList.merge(floatCsv, append)"),
            "KHÔNG merge trên bản đọc cũ từ đầu phiên",
        )
    }

    // ─────────────── 4. Vá xong phải DỰNG LẠI VietMap (bóng chỉ init lúc khởi động) ───────────────

    @Test
    fun `force-stop VietMap CHI khi vua va quyen bong`() {
        val gate = runner.indexOf("(Grant.VM_FLOAT_LIST in fixed || Grant.VM_OVERLAY_OP in fixed) && Prefs.vmBubbleEnabled(app)")
        val forceStop = runner.indexOf("am force-stop \$vm")
        assertTrue(gate in 0 until forceStop, "chỉ force-stop khi VỪA vá quyền bóng + bóng đang bật")
        assertTrue(runner.indexOf("pidof \$vm") in gate until forceStop, "và chỉ khi VietMap đang chạy")
    }

    @Test
    fun `va xong thi goi autostart voi force (bo cooldown) de bong len ngay`() {
        assertTrue(
            runner.contains("VietMapAutostartService.startForPermissionFix(app, returnToSelf = !forBoot)"),
            "phải nhờ autostart dựng lại VietMap sau khi vá",
        )
        assertTrue(
            runner.indexOf("if (phase.vmNeedsRestart)") in 0 until runner.indexOf("startForPermissionFix"),
            "chỉ dựng lại khi thật sự vừa vá quyền bóng",
        )
        assertTrue(
            autostartSvc.contains("fun startForPermissionFix(ctx: Context, returnToSelf: Boolean) =") &&
                autostartSvc.contains("force = true"),
            "startForPermissionFix phải truyền force để bỏ cooldown",
        )
        assertTrue(
            autostartSvc.contains("VietMapAutostart.runNow(applicationContext, returnToSelfPkg, force)"),
            "service phải chuyển cờ force xuống runNow",
        )
        assertTrue(
            autostart.contains("tryBeginRun(force = force)"),
            "runNow phải chuyển force vào gate (bỏ cooldown, giữ single-flight)",
        )
        assertTrue(
            autostart.contains("if (!force && !outsideCooldown(nowMs, lastRunAtMs))"),
            "force chỉ bỏ COOLDOWN, single-flight vẫn phải giữ",
        )
    }

    @Test
    fun `force-start den luc worker dang chay thi HOAN, khong bo (va khong mat suat)`() {
        // Ca thật: mở app start autostart (worker #1 vào pollUntilInMap tới 25 s) → vài giây sau audit vá xong
        // appop, force-stop VietMap rồi force-start. Bỏ start đó = VietMap nằm chết + cooldown 30 s đẩy việc
        // dựng bóng sang lần mở app SAU = đúng triệu chứng v1.38.
        assertTrue(autostartSvc.contains("} else if (force) {"), "force-start phải có nhánh riêng, không rơi vào 'bỏ qua start trùng'")
        assertTrue(autostartSvc.contains("pendingForce.set(true)"), "phải nhớ suất force đã hoãn")
        assertTrue(
            autostartSvc.contains("val tookForce = pendingForce.compareAndSet(true, false)") &&
                autostartSvc.contains("val chained = tookForce && workerActive.compareAndSet(false, true)"),
            "worker khi xong phải nối tiếp suất force đã hoãn",
        )
        assertTrue(
            autostartSvc.contains("if (tookForce) {") && autostartSvc.contains("pendingForce.set(true)"),
            "giành lại suất worker mà TRƯỢT thì phải TRẢ cờ force lại, không để bốc hơi",
        )
    }

    @Test
    fun `force-start KHONG duoc bo khi worker autostart dang chay (hoan roi chay noi tiep)`() {
        // Ca thật gây bug: mở app → MainActivity start autostart NGAY (worker vào pollUntilInMap tới 25 s) →
        // vài giây sau audit vá xong appop bản mod, force-stop VietMap rồi force-start. Nếu force-start bị bỏ
        // như một start trùng thường thì VietMap nằm chết, BÓNG KHÔNG LÊN, và việc dựng lại bị đẩy sang lần mở
        // app sau (cooldown 30 s) — đúng triệu chứng v1.38 đang sửa.
        val cas = autostartSvc.indexOf("workerActive.compareAndSet(false, true)")
        assertTrue(cas >= 0)
        assertTrue(
            autostartSvc.contains("} else if (force) {") && autostartSvc.contains("pendingForce.set(true)"),
            "start FORCE trượt CAS phải được HOÃN lại, không bỏ",
        )
        val chained = autostartSvc.indexOf("pendingForce.compareAndSet(true, false)")
        assertTrue(chained > 0, "worker khi xong phải kiểm tra force đã hoãn")
        val finish = autostartSvc.indexOf("finish(latestStartId)", chained)
        assertTrue(finish > chained, "và chỉ finish() khi KHÔNG nối tiếp (finish giữa lượt force = xé FGS)")
        assertTrue(
            autostartSvc.indexOf("startWorker(pendingForceReturnToSelfPkg, force = true)") in chained until finish,
            "nối tiếp phải chạy lại đúng nhánh force (bỏ cooldown)",
        )
        // Nhả cờ TRƯỚC khi quyết định nối tiếp/dừng — giữ nguyên hợp đồng chống-rò-FGS của v1.37.
        assertTrue(
            autostartSvc.indexOf("workerActive.set(false)") in 0 until chained,
            "vẫn phải nhả workerActive trước finish()/nối tiếp",
        )
    }

    // ─────────────── 5. Cờ một-lần đã bị GỠ (gốc bug v1.38) + công thức không còn ở autostart ───────────────

    @Test
    fun `co mot-lan vm_float_whitelist_applied da bi go khoi Prefs`() {
        assertFalse(prefs.contains("fun vmFloatWhitelistApplied"), "cờ một-lần phải bị gỡ (đọc-trước thay cờ)")
        assertFalse(prefs.contains("fun setVmFloatWhitelistApplied"), "và không còn setter")
    }

    @Test
    fun `VietMapAutostart khong con tu ghi whitelist nua`() {
        assertFalse(
            autostart.contains("settings put global byd_float_app_list"),
            "công thức whitelist đã chuyển sang bộ audit — autostart không được ghi nữa (một chủ duy nhất)",
        )
        assertFalse(autostart.contains("vmFloatWhitelistApplied"), "và không còn tham chiếu cờ một-lần")
        assertFalse(
            autostart.contains("appops set"),
            "autostart không còn cấp appop — việc đó của bộ audit",
        )
    }

    // ─────────────────────────── 6. Hai điểm chạy: boot + mở app ───────────────────────────

    @Test
    fun `boot chay audit truoc cac buoc CAN QUYEN va bao bang notification khi con thieu`() {
        val audit = boot.indexOf("PermissionAuditRunner.runForBoot(applicationContext)")
        assertTrue(audit >= 0, "BootSetupService phải chạy audit")
        assertTrue(
            audit < boot.indexOf("VietMapAutostartService.startForBoot"),
            "audit phải chạy TRƯỚC autostart VietMap (vá quyền rồi mới dựng bóng)",
        )
        assertTrue(
            audit < boot.indexOf("NavConnect.grantAccessibility(applicationContext)"),
            "và trước accessibility grant (audit có thể đã cấp ⇒ khỏi làm hai lần)",
        )
        // NGƯỢC LẠI với tiện nghi cabin: ghế + lọc bụi chỉ dùng HAL trong tiến trình, KHÔNG cần quyền nào, và
        // mỗi hàm chỉ bung thread rồi trả về ngay. Đặt SAU audit thì một phiên dadb câm (hạn đọc 30 s) đẩy ghế/
        // lọc bụi trễ tới ~35 s sau khi nổ máy — owner cảm thấy ngay. Nên chúng phải đứng TRƯỚC audit.
        assertTrue(
            boot.indexOf("SeatComfortApplier.applyOnStart") in 0 until audit,
            "ghế KHÔNG được chờ audit (không phụ thuộc quyền)",
        )
        assertTrue(
            boot.indexOf("Pm25FilterApplier.applyOnStart") in 0 until audit,
            "lọc bụi PM2.5 KHÔNG được chờ audit (không phụ thuộc quyền)",
        )
        val notif = boot.indexOf("PermissionPrompt.postBootNotification")
        assertTrue(notif > audit, "boot headless báo bằng notification (không dựng được dialog)")
        assertTrue(
            boot.substring(audit, notif).contains("audit.needsOwner"),
            "chỉ báo khi CÒN THIẾU sau khi đã tự vá",
        )
    }

    @Test
    fun `mo app chay audit (chi khi tao that) va popup khi con thieu`() {
        val call = mainActivity.indexOf("PermissionAuditRunner.runForAppOpen(applicationContext)")
        assertTrue(call >= 0, "MainActivity phải chạy audit khi mở app")
        val gate = mainActivity.lastIndexOf("if (savedInstanceState == null)", call)
        assertTrue(gate in 0 until call, "audit chỉ chạy khi onCreate là lần tạo THẬT (không chạy lúc recreate)")
        assertTrue(
            mainActivity.contains("PermissionPrompt.show(this, report)"),
            "kết quả audit phải đi vào popup",
        )
    }

    @Test
    fun `boot dung BACKGROUND_READ_CAP - mo app dung AWAIT_ADB_APPROVAL`() {
        assertTrue(
            runner.contains("audit(ctx.applicationContext, LocalShellRetry.BACKGROUND_READ_CAP, \"boot\""),
            "boot: owner không ở màn hình ⇒ một lần thử + chặn treo",
        )
        assertTrue(
            runner.contains("LocalShellRetry.AWAIT_ADB_APPROVAL, label, forBoot = false"),
            "mở app: owner đang nhìn ⇒ chờ được lúc hộp thoại 'Cho phép gỡ lỗi USB' bung ra",
        )
    }

    // ─────────────────────────── 7. Popup: chỉ hiện khi thiếu, có nút cấp lại ───────────────────────────

    @Test
    fun `popup KHONG hien khi du het`() {
        val show = prompt.indexOf("fun show(activity: Activity, report: AuditReport)")
        assertTrue(show >= 0)
        assertTrue(
            prompt.indexOf("if (report.allOk) return", show) in show until prompt.indexOf("AlertDialog.Builder", show),
            "đủ hết ⇒ return trước khi dựng dialog (không quấy owner)",
        )
        val notif = prompt.indexOf("fun postBootNotification")
        assertTrue(
            prompt.indexOf("if (report.allOk) {", notif) > notif,
            "notification boot cũng chỉ đăng khi còn thiếu",
        )
    }

    @Test
    fun `notification boot KHONG bao lai khi ket luan khong doi (khong quay owner moi lan no may)`() {
        // Boot chạy BACKGROUND_READ_CAP ⇒ xe chưa cấp khoá adb thì shellBlocked ⇒ mọi mục cần shell vào "còn
        // thiếu" ⇒ báo vô điều kiện = notification y hệt MỖI LẦN NỔ MÁY, mãi mãi. Đó là quấy rầy (R8).
        val notif = prompt.indexOf("fun postBootNotification")
        val gate = prompt.indexOf("Prefs.permNagSignature(app)", notif)
        val post = prompt.indexOf("nm.notify(NOTIFICATION_ID", notif)
        assertTrue(gate in 0 until post, "phải so chữ ký kết luận TRƯỚC khi đăng notification")
        assertTrue(
            prompt.indexOf("Prefs.setPermNagSignature(app, signature)", post) > post,
            "chỉ ghim chữ ký SAU khi đã đăng được (đăng lỗi ⇒ lần sau vẫn báo)",
        )
        assertTrue(
            prompt.indexOf("Prefs.setPermNagSignature(app, \"\")") in notif until gate,
            "xe lành lại ⇒ xoá chữ ký, để sự cố cũ quay lại vẫn được báo tiếp",
        )
    }

    @Test
    fun `nut cap lai KHONG duoc bao 'da cap du quyen' khi luot bi bo`() {
        // retryFromPrompt giữ single-flight: một lượt khác đang chạy ⇒ trả report skipped (allOk=true để không
        // popup). Nếu toast chỉ đọc allOk thì nói "Đã cấp đủ quyền" trong khi CHƯA chạy lệnh nào = báo sai.
        assertTrue(prompt.contains("again.skipped ->"), "phải phân biệt lượt bị bỏ với 'đã đủ'")
        val skipped = prompt.indexOf("again.skipped ->")
        val allOk = prompt.indexOf("again.allOk ->")
        assertTrue(skipped in 0 until allOk, "nhánh skipped phải được xét TRƯỚC nhánh allOk")
    }

    @Test
    fun `popup co nut cap lai + de sau, va nut cap lai chay lai audit voi force`() {
        assertTrue(prompt.contains("Lang.t(\"Cấp lại quyền\""), "phải có nút cấp lại")
        assertTrue(prompt.contains("Lang.t(\"Để sau\""), "và nút để sau")
        assertTrue(
            prompt.contains("PermissionAuditRunner.retryFromPrompt(activity.applicationContext)"),
            "nút cấp lại phải chạy lại vòng vá",
        )
        assertTrue(
            runner.contains("runAsync(ctx, \"owner bấm cấp lại\", force = true"),
            "owner bấm ⇒ bỏ cooldown (lệnh tường minh, không phải nhịp tự động)",
        )
    }

    @Test
    fun `moi Grant deu co nhan tieng nguoi song ngu`() {
        // when đủ nhánh (exhaustive) là compile-time; ở đây khoá thêm: không nhánh nào bỏ trống chữ EN.
        com.byd.clusternav.core.Grant.entries.forEach { g ->
            assertTrue(prompt.contains("Grant.${g.name} ->"), "thiếu nhãn cho ${g.name}")
        }
        assertTrue(prompt.contains("fun shellHint"), "phải nói owner cần LÀM GÌ khi chưa nối được dadb")
        assertTrue(
            prompt.contains("Cho phép gỡ lỗi USB"),
            "ca duy nhất app không tự làm được = owner bấm Cho phép gỡ lỗi USB",
        )
    }

    @Test
    fun `shellHint doc dung TEN ENUM LocalShellFailure (doi ten enum phai lam do test nay)`() {
        // Ranh giới chuỗi: runner truyền `reason.name`, prompt so bằng literal. Đổi tên hằng enum mà không sửa
        // literal thì lời khuyên cho owner âm thầm rơi về câu chung chung — kiểu lỗi không bao giờ tự lộ ra.
        assertTrue(runner.contains("shellReason = result.reason.name"), "runner phải truyền đúng tên enum")
        listOf(
            com.byd.clusternav.carexec.LocalShellFailure.AWAITING_APPROVAL,
            com.byd.clusternav.carexec.LocalShellFailure.AUTH_REJECTED,
            com.byd.clusternav.carexec.LocalShellFailure.PORT_CLOSED,
        ).forEach {
            assertTrue(prompt.contains("\"${it.name}\""), "shellHint thiếu nhánh cho ${it.name}")
        }
    }

    // ─────────── 8. Uỷ quyền cho đường đã proven (không chép công thức) + một nguồn đọc in-process ───────────

    @Test
    fun `ba muc nang uy quyen cho duong da proven, khong chep cong thuc`() {
        assertTrue(runner.contains("NavConnect.selfGrant(app)"), "quyền notification: gọi lại NavConnect.selfGrant")
        assertTrue(runner.contains("NavConnect.grantAccessibility(app)"), "a11y: gọi lại NavConnect.grantAccessibility")
        assertTrue(
            runner.contains("AssistantLauncher.setSystemAssistant(app, retry)"),
            "trợ lý: gọi lại recipe 8hare đã proven",
        )
        assertFalse(
            runner.contains("cmd notification allow_listener"),
            "KHÔNG chép lại công thức cấp listener (một chủ duy nhất là NavConnect)",
        )
    }

    @Test
    fun `MainActivity va audit dung CHUNG mot phep doc in-process`() {
        assertTrue(
            mainActivity.contains("PermissionAuditRunner.notificationListenerGranted(this)"),
            "UI phải dùng chung phép đọc với audit (không hai kết quả khác nhau)",
        )
        assertTrue(
            mainActivity.contains("PermissionAuditRunner.accessibilityServiceGranted(this)"),
            "tương tự cho accessibility",
        )
    }

    // ─────────────────────────── 9. Gate cooldown / single-flight (thuần, chạy thật) ───────────────────────────

    @BeforeEach
    fun resetGate() = PermissionAuditRunner.resetGateForTest()

    @Test
    fun `cooldown chan luot thu hai, force thi vuot duoc`() {
        assertTrue(PermissionAuditRunner.tryBegin(force = false, nowMs = 1_000L))
        PermissionAuditRunner.finishRun()
        assertFalse(
            PermissionAuditRunner.tryBegin(force = false, nowMs = 1_500L),
            "trong cooldown ⇒ bỏ (boot + mở app + recreate bắn dồn)",
        )
        assertTrue(
            PermissionAuditRunner.tryBegin(force = true, nowMs = 1_600L),
            "owner bấm 'Cấp lại quyền' ⇒ vượt cooldown",
        )
        PermissionAuditRunner.finishRun()
        assertTrue(
            PermissionAuditRunner.tryBegin(force = false, nowMs = 1_600L + PermissionAuditRunner.COOLDOWN_MS),
            "hết cooldown (tính từ lượt force vừa rồi) ⇒ chạy lại được",
        )
    }

    @Test
    fun `single-flight - force cung KHONG mo hai luot song song`() {
        assertTrue(PermissionAuditRunner.tryBegin(force = false, nowMs = 1_000L))
        assertFalse(
            PermissionAuditRunner.tryBegin(force = true, nowMs = 1_100L),
            "đang có lượt chạy ⇒ force cũng phải chờ (hai phiên dadb song song là bug)",
        )
        PermissionAuditRunner.finishRun()
    }

    @Test
    fun `outsideCooldown - lan dau luon cho chay`() {
        assertTrue(PermissionAuditRunner.outsideCooldown(nowMs = 5_000L, lastAtMs = 0L))
        assertFalse(PermissionAuditRunner.outsideCooldown(nowMs = 5_000L, lastAtMs = 4_999L))
    }
}
