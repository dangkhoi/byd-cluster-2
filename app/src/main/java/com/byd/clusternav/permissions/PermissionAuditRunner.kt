package com.byd.clusternav.permissions

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.byd.clusternav.AdbKeys
import com.byd.clusternav.NavConnect
import com.byd.clusternav.NavNotificationListener
import com.byd.clusternav.Prefs
import com.byd.clusternav.VietMapAutostartService
import com.byd.clusternav.carexec.LocalDeviceShell
import com.byd.clusternav.carexec.LocalShellResult
import com.byd.clusternav.carexec.LocalShellRetry
import com.byd.clusternav.carexec.LocalShellText
import com.byd.clusternav.core.AuditFeatures
import com.byd.clusternav.core.AuditReport
import com.byd.clusternav.core.FloatAppList
import com.byd.clusternav.core.Grant
import com.byd.clusternav.core.PermissionAudit
import com.byd.clusternav.modules.navaccess.NavAccessibilityService
import com.byd.clusternav.modules.voicekey.AssistantLauncher
import com.byd.clusternav.navigation.NavApps
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bộ **kiểm tra quyền** của ClusterNav: đọc trạng thái THẬT → đủ thì im lặng → thiếu thì tự vá → đọc lại →
 * còn thiếu thì báo caller để popup xin owner. Spec `docs/specs/permission-health-audit.html`.
 *
 * Owner chốt 2026-09-10 (nguyên văn): *"cần đọc trước, nếu đã cấp thì không cấp lại, cần phải làm 1 cái
 * auto-boot kiểm tra toàn bộ quyền, nếu OK hết thì pass, không ok thì popup lên để xin quyền lại, chắc chắn
 * vào app là xài ngon"*.
 *
 * **Vì sao ĐỌC TRƯỚC chứ không ghim cờ** — bug on-car v1.38: bóng VietMap vẫn dính toast *"Hệ thống IVI không
 * hỗ trợ hoạt động này"* dù v1.35 đã có công thức whitelist, vì công thức đó bị gate bởi một cờ prefs "đã áp
 * một lần". Cài lại bản mod VietMap (khác chữ ký ⇒ gỡ+cài) XOÁ appop `SYSTEM_ALERT_WINDOW` của gói đó, còn cờ
 * thì vẫn true ⇒ không bao giờ áp lại. Thêm nữa `sh("settings put …")` bị từ chối KHÔNG ném ⇒ cờ vẫn được set
 * dù lệnh trượt. Nay: nguồn sự thật là THIẾT BỊ, đọc mỗi lần; ghi CHỈ khi thiếu; ghi rồi ĐỌC LẠI xác nhận.
 *
 * **Ba việc nó KHÔNG tự làm** (vá bằng cách gọi lại đúng đường đã proven, không chép công thức — DRY):
 * `NOTIF_LISTENER` → [NavConnect.selfGrant] · `A11Y_SERVICE` → [NavConnect.grantAccessibility] ·
 * `ASSISTANT` → [AssistantLauncher.setSystemAssistant]. Sáu mục còn lại (float list ×2, appop ×2, doze ×2) là
 * `settings`/`appops`/`deviceidle` nên audit đọc-ghi thẳng trong MỘT phiên dadb của chính nó.
 *
 * Thread: [runForBoot] chạy ĐỒNG BỘ trên thread nền của [com.byd.clusternav.BootSetupService] (FGS giữ tiến
 * trình sống); [runForAppOpen] tự bung thread + gọi callback trên MAIN. Degrade-safe: mọi lỗi bị bọc, audit
 * KHÔNG BAO GIỜ chặn boot hay chặn mở app.
 */
object PermissionAuditRunner {

    private const val TAG = "PermAudit"

    /** Khoảng cách tối thiểu giữa 2 lượt audit — boot + mở app + recreate có thể bắn dồn. */
    const val COOLDOWN_MS = 30_000L

    /** Trần chờ [NavConnect.selfGrant] (đường dadb ~3–5 s; nó tự có timeout bên trong). */
    private const val GRANT_NOTIF_TIMEOUT_MS = 15_000L

    /** Trần chờ [NavConnect.grantAccessibility] (settle 1,2 s + toggle 0,8 s + dumpsys; nó tự timeout ~9 s). */
    private const val GRANT_A11Y_TIMEOUT_MS = 12_000L

    private val inFlight = AtomicBoolean(false)

    @Volatile private var lastRunAtMs = 0L

    private const val KEY_NOTIF_LISTENERS = "enabled_notification_listeners"
    private const val KEY_A11Y_SERVICES = "enabled_accessibility_services"
    private const val KEY_ASSISTANT = "assistant"
    private const val KEY_VOICE_INTERACTION = "voice_interaction_service"
    private const val FLOAT_LIST_KEY = "byd_float_app_list"

    // ─────────────────────────────── Đọc IN-PROCESS (miễn phí, không dadb) ───────────────────────────────

    /**
     * Quyền đọc thông báo đã bật chưa. Đọc THẲNG secure setting — mọi app đọc được, KHÔNG cần dadb.
     *
     * Chỉ xét **đã bật** (có tên trong danh sách), KHÔNG xét đã *bound*: bind là vòng đời (chạy sau vài giây,
     * do [NavConnect.ensureConnected] lo) — trộn vào đây sẽ báo "thiếu quyền" oan ngay sau khi vừa cấp.
     */
    fun notificationListenerGranted(ctx: Context): Boolean =
        secureListHas(ctx, KEY_NOTIF_LISTENERS, ComponentName(ctx, NavNotificationListener::class.java))

    /** Accessibility service (booster GMaps + `onKeyEvent` phím-thoại) đã bật chưa. Xem ghi chú "bound" ở trên. */
    fun accessibilityServiceGranted(ctx: Context): Boolean =
        secureListHas(ctx, KEY_A11Y_SERVICES, ComponentName(ctx, NavAccessibilityService::class.java))

    /**
     * Một secure setting dạng danh sách phẳng (`a/b:c/d`) có chứa [comp] chưa.
     *
     * So khớp hai lượt: (1) so chuỗi qua [PermissionAudit.flatHasComponent] (thuần, khoá bằng unit test ở
     * `:core`), (2) nếu trượt thì so bằng [ComponentName] để chấp cả **dạng viết gọn** `pkg/.Class` mà một số
     * ROM ghi vào setting — dạng này không bằng chuỗi `flattenToString` nhưng là CÙNG một component.
     */
    private fun secureListHas(ctx: Context, key: String, comp: ComponentName): Boolean {
        val flat = runCatching { Settings.Secure.getString(ctx.contentResolver, key) }.getOrNull()
        if (PermissionAudit.flatHasComponent(flat, comp.flattenToString())) return true
        return (flat ?: "").split(':').any { ComponentName.unflattenFromString(it.trim()) == comp }
    }

    private fun overlayGranted(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /**
     * ĐỌC LẠI in-process xem trợ lý hệ thống có đang trỏ Google/Gemini — dùng để xác nhận
     * [AssistantLauncher.setSystemAssistant] đã "landed" thật, không tin chuỗi lỗi rỗng của nó.
     *
     * @return `true` = cả `secure assistant` và `voice_interaction_service` đều trỏ [AssistantLauncher.PKG_GSA] ·
     *   `false` = đọc được nhưng KHÔNG trỏ đúng (lệnh trượt) · `null` = **không đọc được khoá** (ROM chặn / lỗi
     *   provider) ⇒ không kết luận được, caller phải đành tin kết quả của recipe. Trả `false` trong ca `null` sẽ
     *   biến audit thành cái máy nhắc mỗi lần mở app trên một xe thật ra đang chạy tốt — đúng thứ owner ghét nhất.
     */
    private fun assistantReadBack(ctx: Context): Boolean? {
        val assistant = runCatching { Settings.Secure.getString(ctx.contentResolver, KEY_ASSISTANT) }.getOrNull()
        val voiceService =
            runCatching { Settings.Secure.getString(ctx.contentResolver, KEY_VOICE_INTERACTION) }.getOrNull()
        if (assistant == null && voiceService == null) return null   // không đọc được ⇒ không kết luận
        return PermissionAudit.serviceValuePointsTo(assistant, AssistantLauncher.PKG_GSA) &&
            PermissionAudit.serviceValuePointsTo(voiceService, AssistantLauncher.PKG_GSA)
    }

    // ─────────────────────────────────────── Điểm vào ───────────────────────────────────────

    /**
     * BOOT headless — ĐỒNG BỘ (block thread gọi, đúng như [com.byd.clusternav.BootSetupService] mong đợi).
     * retry [LocalShellRetry.BACKGROUND_READ_CAP]: owner KHÔNG ở màn hình để bấm "Cho phép gỡ lỗi USB", nên
     * một lần thử + chặn treo, không chờ ~31 s (F6).
     */
    fun runForBoot(ctx: Context): AuditReport =
        audit(ctx.applicationContext, LocalShellRetry.BACKGROUND_READ_CAP, "boot", forBoot = true)

    /**
     * MỞ APP — chạy nền, [onDone] gọi trên MAIN thread. retry [LocalShellRetry.AWAIT_ADB_APPROVAL]: owner đang
     * nhìn màn hình nên CHỜ được lúc hộp thoại "Cho phép gỡ lỗi USB" bung ra (F2).
     */
    fun runForAppOpen(ctx: Context, onDone: (AuditReport) -> Unit) =
        runAsync(ctx, "mở app", force = false, onDone = onDone)

    /**
     * Owner bấm **"Cấp lại quyền"** trong popup: bỏ qua cooldown (đây là lệnh tường minh của owner, không phải
     * nhịp tự động) nhưng GIỮ single-flight để hai lần bấm không mở hai phiên dadb song song.
     */
    fun retryFromPrompt(ctx: Context, onDone: (AuditReport) -> Unit) =
        runAsync(ctx, "owner bấm cấp lại", force = true, onDone = onDone)

    private fun runAsync(ctx: Context, label: String, force: Boolean, onDone: (AuditReport) -> Unit) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread({
            val report = audit(app, LocalShellRetry.AWAIT_ADB_APPROVAL, label, forBoot = false, force = force)
            main.post { onDone(report) }
        }, "perm-audit").start()
    }

    // ─────────────────────────────────────── Lõi audit ───────────────────────────────────────

    /** PURE (device-free, unit-tested): đã ra ngoài cooldown chưa (0 = chưa từng chạy). */
    internal fun outsideCooldown(nowMs: Long, lastAtMs: Long, cooldownMs: Long = COOLDOWN_MS): Boolean =
        lastAtMs == 0L || nowMs - lastAtMs >= cooldownMs

    /** Giành suất chạy: single-flight luôn, cooldown bỏ được khi [force] (owner bấm). */
    internal fun tryBegin(force: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        if (!force && !outsideCooldown(nowMs, lastRunAtMs)) {
            inFlight.set(false)
            return false
        }
        lastRunAtMs = nowMs
        return true
    }

    internal fun finishRun() = inFlight.set(false)

    /** Test-only: xả state gate giữa các test (state process-global trong object). */
    internal fun resetGateForTest() {
        inFlight.set(false)
        lastRunAtMs = 0L
    }

    /** Công tắc tính năng hiện tại → quyết định quyền nào CẦN (tính năng tắt ⇒ không đòi, không vá). */
    internal fun readFeatures(app: Context): AuditFeatures = AuditFeatures(
        nav = Prefs.enabled(app),
        voiceKey = Prefs.voiceKeyEnabled(app),
        badge = Prefs.badgeEnabled(app),
        vmBubble = Prefs.vmBubbleEnabled(app),
        cast = runCatching {
            com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
                .coordinator(app).prefs.castEnabled()
        }.getOrDefault(false),
        geminiBinding = runCatching { AssistantLauncher.hasGeminiBinding(app) }.getOrDefault(false),
        // Bóng/badge là cờ opt-in và VẪN BẬT sau khi owner gỡ VietMap ⇒ phải hỏi PackageManager, không tin cờ:
        // quyền của một gói KHÔNG CÀI thì không lệnh nào vá được, đòi nó = popup vĩnh viễn vô nghĩa. CÙNG phép
        // thử mà [com.byd.clusternav.VietMapAutostart.runNow] dùng để tự bỏ qua.
        vmInstalled = runCatching {
            app.packageManager.getLaunchIntentForPackage(NavApps.VIETMAP_LIVE) != null
        }.getOrDefault(false),
    )

    private fun audit(
        app: Context,
        retry: LocalShellRetry,
        label: String,
        forBoot: Boolean,
        force: Boolean = false,
    ): AuditReport {
        if (!tryBegin(force)) {
            Log.i(TAG, "$label: bỏ qua (đang chạy hoặc trong cooldown ${COOLDOWN_MS}ms)")
            // skipped ⇒ allOk ⇒ không bề mặt nào quấy owner; nhưng bề mặt NÓI KẾT QUẢ (toast nút "Cấp lại
            // quyền") đọc cờ này để không báo "đã cấp đủ quyền" khi thật ra chưa chạy lệnh nào.
            return AuditReport(skipped = true)
        }
        try {
            return runCatching { auditInner(app, retry, label, forBoot) }
                .getOrElse {
                    // Degrade-safe: audit KHÔNG được làm chết boot / chết mở app.
                    Log.w(TAG, "$label: audit lỗi (bỏ qua): ${it.message}", it)
                    AuditReport()
                }
        } finally {
            finishRun()
        }
    }

    private fun auditInner(app: Context, retry: LocalShellRetry, label: String, forBoot: Boolean): AuditReport {
        val features = readFeatures(app)
        val required = PermissionAudit.requiredFor(features)
        if (required.isEmpty()) {
            Log.i(TAG, "$label: không tính năng nào bật ⇒ không cần quyền nào")
            return AuditReport()
        }
        val okInProcess = buildSet {
            if (Grant.NOTIF_LISTENER in required && notificationListenerGranted(app)) add(Grant.NOTIF_LISTENER)
            if (Grant.A11Y_SERVICE in required && accessibilityServiceGranted(app)) add(Grant.A11Y_SERVICE)
            if (Grant.SELF_OVERLAY in required && overlayGranted(app)) add(Grant.SELF_OVERLAY)
        }
        if (!PermissionAudit.needsShellSession(required, okInProcess)) {
            // ĐỦ HẾT và không mục nào cần shell ⇒ KHÔNG mở phiên dadb, KHÔNG phát một lệnh nào (R1).
            Log.i(TAG, "$label: đủ hết (đọc in-process) — không mở dadb, không cấp lại gì")
            return AuditReport(required = required)
        }

        // ── Một phiên dadb: đọc 6 mục shell → vá mục thiếu → đọc lại xác nhận → (nếu vừa vá quyền VietMap) force-stop
        val keys = AdbKeys.ensure(app)
        val result = LocalDeviceShell.sessionResult(keys, retry) { sh ->
            shellPhase(app, sh, required, okInProcess)
        }
        val phase = when (result) {
            is LocalShellResult.Ok -> result.value
            is LocalShellResult.Failed -> {
                // Không mở được dadb ⇒ không đọc/vá được gì cần shell, và cũng KHÔNG thể kết luận "đủ".
                // Bỏ luôn 3 mục uỷ quyền (chúng cũng đi dadb, sẽ hỏng y hệt) để không treo boot vô ích.
                Log.w(TAG, "$label: phiên dadb KHÔNG mở được (${result.reason}, ${result.attempts} lần thử)")
                val report = AuditReport(
                    required = required,
                    missingBefore = PermissionAudit.missing(required, okInProcess),
                    missingAfter = PermissionAudit.missing(required, okInProcess),
                    shellBlocked = true,
                    shellReason = result.reason.name,
                )
                Log.i(TAG, "$label: ${report.describe()}")
                return report
            }
        }

        // ── Ba mục uỷ quyền cho đường đã proven (mỗi cái tự mở phiên riêng — KHÔNG lồng trong phiên trên)
        val fixed = LinkedHashSet(phase.fixed)
        if (Grant.NOTIF_LISTENER in phase.missingBefore) {
            awaitCallback(GRANT_NOTIF_TIMEOUT_MS) { done -> NavConnect.selfGrant(app) { done() } }
            if (notificationListenerGranted(app)) fixed += Grant.NOTIF_LISTENER
        }
        if (Grant.A11Y_SERVICE in phase.missingBefore) {
            awaitCallback(GRANT_A11Y_TIMEOUT_MS) { done -> NavConnect.grantAccessibility(app) { done() } }
            if (accessibilityServiceGranted(app)) fixed += Grant.A11Y_SERVICE
        }
        if (Grant.ASSISTANT in phase.missingBefore) {
            val err = runCatching { AssistantLauncher.setSystemAssistant(app, retry) }.getOrDefault("lỗi")
            // ĐỌC LẠI, không tin chuỗi lỗi: `setSystemAssistant` chỉ báo "phiên dadb chạy được", nó KHÔNG soi
            // exit code từng lệnh — mà `settings put secure …` bị từ chối thì in lỗi ra stdout chứ KHÔNG ném
            // (đúng cái bẫy mà cả bộ audit này sinh ra để chống). Xem [assistantReadBack] cho ca ROM không cho
            // đọc khoá (trả null ⇒ không kết luận được ⇒ đành tin err).
            val confirmed = assistantReadBack(app)
            if (err.isEmpty() && confirmed != false) fixed += Grant.ASSISTANT
            else Log.w(TAG, "$label: đặt trợ lý hệ thống chưa được (err='$err' đọc lại=$confirmed)")
        }

        val report = AuditReport(
            required = required,
            missingBefore = phase.missingBefore,
            fixed = fixed,
            missingAfter = PermissionAudit.missing(required, okInProcess + phase.okAfterShell + fixed),
        )
        Log.i(TAG, "$label: ${report.describe()}")

        // ── Vừa vá quyền overlay của VietMap ⇒ bóng cũ đã addView-FAIL rồi, đổi quyền KHÔNG hồi tố ⇒ nhờ
        // autostart dựng lại (force = bỏ cooldown; VietMap đã bị force-stop trong phiên trên nếu đang chạy).
        if (phase.vmNeedsRestart) {
            Log.i(TAG, "$label: vừa vá quyền bóng VietMap ⇒ dựng lại VietMap để bóng lên cụm")
            runCatching {
                VietMapAutostartService.startForPermissionFix(app, returnToSelf = !forBoot)
            }.onFailure { Log.w(TAG, "$label: không start được autostart VietMap: ${it.message}") }
        }
        return report
    }

    /** Kết quả nửa-shell của một lượt audit (nằm trong phiên dadb). */
    private data class ShellPhase(
        val missingBefore: Set<Grant>,
        val okAfterShell: Set<Grant>,
        val fixed: Set<Grant>,
        val vmNeedsRestart: Boolean,
    )

    /**
     * Trong MỘT phiên dadb: đọc 6 mục cần shell (chỉ những lệnh mà tập [required] thật sự dùng) → vá mục
     * thiếu → ĐỌC LẠI để xác nhận đã "landed" (vì `settings put`/`appops set` bị từ chối thì in lỗi ra stdout
     * chứ KHÔNG ném) → nếu vừa vá quyền bóng VietMap thì `am force-stop` để nó dựng lại bóng.
     */
    private fun shellPhase(
        app: Context,
        sh: (String) -> LocalShellText,
        required: Set<Grant>,
        okInProcess: Set<Grant>,
    ): ShellPhase {
        val self = app.packageName
        val vm = NavApps.VIETMAP_LIVE
        val op = PermissionAudit.OP_OVERLAY
        val needFloat = Grant.SELF_FLOAT_LIST in required || Grant.VM_FLOAT_LIST in required
        val needDoze = Grant.DOZE_SELF in required || Grant.DOZE_VIETMAP in required

        // ── ĐỌC
        val floatCsv = if (needFloat) sh("settings get global $FLOAT_LIST_KEY").output.trim() else ""
        val dozeOut = if (needDoze) sh("dumpsys deviceidle whitelist").output else ""
        val ok = linkedSetOf<Grant>()
        if (needFloat) {
            if (Grant.SELF_FLOAT_LIST in required && FloatAppList.contains(floatCsv, self)) ok += Grant.SELF_FLOAT_LIST
            if (Grant.VM_FLOAT_LIST in required && FloatAppList.contains(floatCsv, vm)) ok += Grant.VM_FLOAT_LIST
        }
        if (Grant.VM_OVERLAY_OP in required &&
            PermissionAudit.appopAllowed(sh("appops get $vm $op").output, op)
        ) ok += Grant.VM_OVERLAY_OP
        if (needDoze) {
            if (Grant.DOZE_SELF in required && PermissionAudit.dozeWhitelisted(dozeOut, self)) ok += Grant.DOZE_SELF
            if (Grant.DOZE_VIETMAP in required && PermissionAudit.dozeWhitelisted(dozeOut, vm)) ok += Grant.DOZE_VIETMAP
        }
        if (Grant.ASSISTANT in required &&
            PermissionAudit.serviceValuePointsTo(sh("settings get secure $KEY_ASSISTANT").output.trim(), AssistantLauncher.PKG_GSA) &&
            PermissionAudit.serviceValuePointsTo(
                sh("settings get secure $KEY_VOICE_INTERACTION").output.trim(), AssistantLauncher.PKG_GSA,
            )
        ) ok += Grant.ASSISTANT

        val missingBefore = PermissionAudit.missing(required, okInProcess + ok)
        if (missingBefore.isEmpty()) {
            Log.i(TAG, "đủ hết — KHÔNG phát lệnh ghi nào (đã đọc ${if (needFloat) "list " else ""}${if (needDoze) "doze " else ""}xong)")
            return ShellPhase(emptySet(), ok, emptySet(), vmNeedsRestart = false)
        }

        // ── VÁ (chỉ mục thiếu)
        val fixed = linkedSetOf<Grant>()
        // (a) float list: MỘT lần ghi cho cả hai gói còn thiếu, merge để KHÔNG clobber gói OEM/app khác.
        val append = buildList {
            if (Grant.SELF_FLOAT_LIST in missingBefore) add(self)
            if (Grant.VM_FLOAT_LIST in missingBefore) add(vm)
        }
        if (append.isNotEmpty()) {
            // ĐỌC LẠI NGAY TRƯỚC KHI GHI (không dùng [floatCsv] đọc ở đầu phiên): đây là read-modify-write trên
            // MỘT khoá toàn cục mà [AssistantLauncher.setSystemAssistant] cũng ghi (thêm Google/Gemini) — và nó
            // chạy SONG SONG ở mỗi lần mở app khi có binding Gemini. Ghi bằng bản đọc cũ = có thể xoá mất mục
            // gói khác vừa thêm (lost update). Đọc lại sát lúc ghi thu cửa sổ đó từ vài giây xuống ~1 ms.
            // Còn lại là rủi ro tồn dư, tự lành ở lần mở app sau (bên kia đọc list đã có mục của mình rồi ghi).
            val fresh = sh("settings get global $FLOAT_LIST_KEY").output.trim()
            sh("settings put global $FLOAT_LIST_KEY ${FloatAppList.merge(fresh, append)}")
            val after = sh("settings get global $FLOAT_LIST_KEY").output.trim()          // read-back
            if (Grant.SELF_FLOAT_LIST in missingBefore && FloatAppList.contains(after, self)) fixed += Grant.SELF_FLOAT_LIST
            if (Grant.VM_FLOAT_LIST in missingBefore && FloatAppList.contains(after, vm)) fixed += Grant.VM_FLOAT_LIST
        }
        // (b) appop overlay — của chính app và/hoặc của bản mod VietMap.
        if (Grant.SELF_OVERLAY in missingBefore) {
            sh("appops set $self $op allow")
            if (PermissionAudit.appopAllowed(sh("appops get $self $op").output, op)) fixed += Grant.SELF_OVERLAY
        }
        if (Grant.VM_OVERLAY_OP in missingBefore) {
            sh("appops set $vm $op allow")
            if (PermissionAudit.appopAllowed(sh("appops get $vm $op").output, op)) fixed += Grant.VM_OVERLAY_OP
        }
        // (c) doze whitelist — một lần đọc lại cho cả hai gói.
        val dozeAdd = buildList {
            if (Grant.DOZE_SELF in missingBefore) add(self)
            if (Grant.DOZE_VIETMAP in missingBefore) add(vm)
        }
        if (dozeAdd.isNotEmpty()) {
            dozeAdd.forEach { sh("cmd deviceidle whitelist +$it") }
            val after = sh("dumpsys deviceidle whitelist").output                        // read-back
            if (Grant.DOZE_SELF in missingBefore && PermissionAudit.dozeWhitelisted(after, self)) fixed += Grant.DOZE_SELF
            if (Grant.DOZE_VIETMAP in missingBefore && PermissionAudit.dozeWhitelisted(after, vm)) fixed += Grant.DOZE_VIETMAP
        }

        // ── Vừa vá quyền của GÓI VIETMAP ⇒ bóng của nó đã thử addView và bị IVI từ chối rồi; đổi list/appop
        // KHÔNG hồi tố. Đang chạy thì force-stop để lần dựng sau có quyền (bản mod dựng bóng lúc khởi động).
        var vmNeedsRestart = false
        if ((Grant.VM_FLOAT_LIST in fixed || Grant.VM_OVERLAY_OP in fixed) && Prefs.vmBubbleEnabled(app)) {
            vmNeedsRestart = true
            if (sh("pidof $vm").output.trim().isNotEmpty()) {
                sh("am force-stop $vm")
                Log.i(TAG, "force-stop VietMap sau khi vá quyền bóng (mod dựng bóng lúc khởi động ⇒ phải mở lại)")
            }
        }
        return ShellPhase(missingBefore, ok, fixed, vmNeedsRestart)
    }

    /**
     * Gọi một API bất đồng bộ dạng callback rồi CHỜ có hạn. Hết hạn ⇒ đi tiếp (bên gọi đọc lại state để biết
     * kết quả thật), KHÔNG treo thread audit — cả boot lẫn mở app đều không được phép đứng vô hạn.
     */
    private fun awaitCallback(timeoutMs: Long, start: (done: () -> Unit) -> Unit) {
        val latch = CountDownLatch(1)
        runCatching { start { latch.countDown() } }.onFailure {
            Log.w(TAG, "gọi đường cấp quyền hỏng: ${it.message}")
            return
        }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }
}
