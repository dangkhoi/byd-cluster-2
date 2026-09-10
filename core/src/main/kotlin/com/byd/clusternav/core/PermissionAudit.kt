package com.byd.clusternav.core

/**
 * Một quyền / cấu hình **NGOÀI app** mà ClusterNav phụ thuộc để một tính năng chạy được trên xe.
 *
 * Tất cả đều là "quyền ADB" hoặc global setting của IVI: app không xin được bằng dialog Android thường,
 * nó phải tự cấp qua dadb uid-shell (loopback `localhost:5555`) — đường đã proven từ 1.13. Cái duy nhất
 * app KHÔNG tự làm được là chính đường dadb đó (owner phải bấm "Cho phép gỡ lỗi USB" một lần), nên đường
 * đó KHÔNG nằm trong enum này mà là [AuditReport.shellBlocked].
 *
 * ⚠ Vì sao cần audit thay vì "cấp một lần rồi ghim cờ": mỗi mục ở đây có thể **mất lẻ** sau reboot / cài
 * lại app / cài lại app khác (vd cài lại bản mod VietMap ⇒ gỡ+cài ⇒ appop `SYSTEM_ALERT_WINDOW` của gói đó
 * bị xoá) / ROM reset. Cờ "đã cấp" trong prefs chỉ nói *app đã từng chạy lệnh*, KHÔNG nói *quyền còn hay
 * không* — đó chính là bug on-car v1.38 (bóng VietMap vẫn dính toast "Hệ thống IVI không hỗ trợ hoạt động
 * này" dù v1.35 đã có công thức whitelist). Nguồn sự thật là THIẾT BỊ, phải đọc lại.
 */
enum class Grant {
    /** Quyền đọc thông báo (`enabled_notification_listeners`) — nguồn nav Google Maps đi kênh notification. */
    NOTIF_LISTENER,

    /** Accessibility service (`enabled_accessibility_services`) — booster đọc cự ly GMaps + `onKeyEvent` phím-thoại. */
    A11Y_SERVICE,

    /** Appop `SYSTEM_ALERT_WINDOW` của CHÍNH ClusterNav — badge tốc độ / nút nổi Cast / kéo-thả bóng. */
    SELF_OVERLAY,

    /** ClusterNav có mặt trong CSV toàn cục `byd_float_app_list` của BYD IVI (điều kiện thứ hai của overlay). */
    SELF_FLOAT_LIST,

    /** Bản mod VietMap có mặt trong `byd_float_app_list` — bóng dẫn đường của nó vẽ lên cụm. */
    VM_FLOAT_LIST,

    /** Appop `SYSTEM_ALERT_WINDOW` của bản mod VietMap. Mất theo mỗi lần cài lại mod (khác chữ ký ⇒ gỡ+cài). */
    VM_OVERLAY_OP,

    /** Trợ lý hệ thống trỏ Google/Gemini (`secure assistant` + `voice_interaction_service`) — hold-mic → Gemini. */
    ASSISTANT,

    /** ClusterNav trong doze whitelist — app chạy nền dài (listener nav, bóng, badge) không bị doze cắt. */
    DOZE_SELF,

    /** VietMap trong doze whitelist — widget speed-limit / bóng phải sống khi app ở nền. */
    DOZE_VIETMAP,
}

/**
 * Công tắc tính năng ĐANG BẬT. Quyết định quyền nào **CẦN** — tính năng tắt thì quyền của nó không bị coi
 * là thiếu, không xin, không vá (không quấy owner vì thứ họ không dùng — R2 của spec).
 */
data class AuditFeatures(
    val nav: Boolean = false,
    val voiceKey: Boolean = false,
    val badge: Boolean = false,
    val vmBubble: Boolean = false,
    val cast: Boolean = false,
    val geminiBinding: Boolean = false,
    /**
     * Bản mod VietMap có THẬT SỰ được cài không.
     *
     * Chưa cài ⇒ ba mục quyền của gói đó ([Grant.VM_FLOAT_LIST], [Grant.VM_OVERLAY_OP],
     * [Grant.DOZE_VIETMAP]) **không thể vá được bằng bất cứ lệnh nào** (`appops set` / `deviceidle +` trên một
     * gói không tồn tại đều bị từ chối), nên coi chúng là "thiếu" chỉ tạo ra một popup VĨNH VIỄN mà owner
     * không làm gì được — đúng thứ R2 cấm ("không quấy owner vì thứ họ không dùng"). Cờ bóng/badge là opt-in
     * và VẪN BẬT sau khi owner gỡ VietMap, nên cổng này phải đọc từ PackageManager chứ không từ prefs.
     * [com.byd.clusternav.VietMapAutostart] cũng tự bỏ qua bằng đúng phép thử đó (`getLaunchIntentForPackage`).
     */
    val vmInstalled: Boolean = true,
) {
    /** Có tính năng nền nào đang bật không (dùng cho doze của chính app). */
    val anyBackgroundFeature: Boolean get() = nav || voiceKey || badge || vmBubble || cast

    /** Tính năng nào cần VietMap sống (widget badge · bóng cụm · chiếu VietMap lên cụm) — và VietMap đã cài. */
    val needsVietMapAlive: Boolean get() = vmInstalled && (badge || vmBubble || cast)
}

/**
 * Kết quả một lượt audit: cần gì · thiếu gì trước khi vá · đã vá được gì · còn thiếu gì (sau khi ĐỌC LẠI).
 *
 * [shellBlocked] = không mở được phiên dadb ⇒ mọi mục cần shell không đọc/vá được ⇒ phải hỏi owner
 * ([shellReason] = tên [com.byd.clusternav.carexec.LocalShellFailure] đã phân loại, giữ dạng String để
 * `:core` không phụ thuộc `:car-integration`).
 */
data class AuditReport(
    val required: Set<Grant> = emptySet(),
    val missingBefore: Set<Grant> = emptySet(),
    val fixed: Set<Grant> = emptySet(),
    val missingAfter: Set<Grant> = emptySet(),
    val shellBlocked: Boolean = false,
    val shellReason: String? = null,
    /**
     * Lượt này KHÔNG chạy (đang có lượt khác chạy dở, hoặc còn trong cooldown) ⇒ báo cáo này **không kết luận
     * gì**. Khác hẳn "đã kiểm và đủ hết": cả hai đều [allOk] (không được quấy owner vì một lượt bị bỏ), nhưng
     * bề mặt nào NÓI KẾT QUẢ cho owner (toast của nút "Cấp lại quyền") phải phân biệt — nói "đã cấp đủ quyền"
     * khi thật ra chưa chạy lệnh nào là nói sai.
     */
    val skipped: Boolean = false,
) {
    /** Đủ hết ⇒ pass im lặng (không popup, không toast). */
    val allOk: Boolean get() = missingAfter.isEmpty() && !shellBlocked

    /** Còn thiếu sau khi đã tự vá ⇒ cần owner ra tay (popup / notification). */
    val needsOwner: Boolean get() = !allOk

    /** Đã phát lệnh ghi nào chưa (dùng cho log + quyết định restart VietMap). */
    val didFixAnything: Boolean get() = fixed.isNotEmpty()

    /**
     * Chữ ký của một **kết luận** — để bề mặt headless (notification lúc boot) không báo LẠI y nguyên cái
     * owner đã thấy lần trước.
     *
     * Vì sao cần: nhánh boot chạy retry `BACKGROUND_READ_CAP`, nên một xe chưa/không cấp được khoá adb
     * (`PORT_CLOSED` / `AWAITING_APPROVAL`) sẽ [shellBlocked] ⇒ [missingAfter] gom HẾT các mục cần shell ⇒
     * notification "thiếu quyền" bung ra **mỗi lần nổ máy**, mãi mãi, dù không có gì mới. Đó là quấy rầy
     * (R8), không phải thông tin. Chữ ký chỉ đổi khi kết luận đổi, nên owner được báo MỘT lần cho mỗi trạng
     * thái khác nhau. Không nhét [fixed] vào chữ ký: vá được rồi thì không còn là việc của owner.
     */
    fun nagSignature(): String =
        if (shellBlocked) "BLOCKED:${shellReason ?: "?"}|${sorted(missingAfter)}" else "MISSING:${sorted(missingAfter)}"

    /** Log một dòng, thứ tự ổn định theo enum để so được giữa hai lần chạy. */
    fun describe(): String = buildString {
        append("cần=").append(sorted(required))
        append(" thiếu=").append(sorted(missingBefore))
        append(" đã vá=").append(sorted(fixed))
        append(" còn thiếu=").append(sorted(missingAfter))
        if (shellBlocked) append(" shell=BLOCKED(").append(shellReason ?: "?").append(')')
    }

    private fun sorted(s: Set<Grant>): String =
        if (s.isEmpty()) "-" else s.sortedBy { it.ordinal }.joinToString(",") { it.name }
}

/**
 * Quyết định THUẦN của bộ kiểm-tra-quyền: *cần những gì* · *đọc được ở đâu* · *thiếu những gì* + các parser
 * đọc output shell. Không Android, không dadb ⇒ test off-car trọn vẹn (ranh giới [CoreBoundary]).
 *
 * Phần đọc/ghi thiết bị nằm ở `:app` (`PermissionAuditRunner`): nó dựng tập "đang OK" rồi hỏi object này
 * "còn thiếu gì", vá, đọc lại, hỏi lần nữa. Nhờ vậy toàn bộ luật "cần gì / đủ chưa" khoá được bằng unit test.
 */
object PermissionAudit {

    /**
     * Ba mục đọc được **in-process** (miễn phí, không cần dadb): hai secure setting mà mọi app đọc được +
     * `Settings.canDrawOverlays`. Vá thì vẫn cần shell — xem [needsShellSession].
     */
    val IN_PROCESS_READABLE: Set<Grant> =
        setOf(Grant.NOTIF_LISTENER, Grant.A11Y_SERVICE, Grant.SELF_OVERLAY)

    /** Quyền mà tổ hợp tính năng [f] đang bật thật sự CẦN. Tính năng tắt ⇒ không có mặt ở đây. */
    fun requiredFor(f: AuditFeatures): Set<Grant> {
        val out = linkedSetOf<Grant>()
        // Nav+HUD đọc GMaps qua notification, và booster a11y cho cự ly ground-truth.
        if (f.nav) { out += Grant.NOTIF_LISTENER; out += Grant.A11Y_SERVICE }
        // Phím vật lý → trợ lý bắt key bằng chính accessibility service (KHÔNG phụ thuộc Nav+HUD).
        if (f.voiceKey) out += Grant.A11Y_SERVICE
        // Binding trỏ Gemini ⇒ trợ lý hệ thống phải là Google/Gemini để keyevent 231 route đúng.
        if (f.geminiBinding) out += Grant.ASSISTANT
        // Overlay của CHÍNH app: badge cụm, nút nổi Cast, lớp kéo-thả vị trí bóng. Trên IVI BYD cần CẢ HAI
        // điều kiện: appop + có tên trong byd_float_app_list.
        if (f.badge || f.vmBubble || f.cast) { out += Grant.SELF_OVERLAY; out += Grant.SELF_FLOAT_LIST }
        // Bóng do CHÍNH VietMap vẽ ⇒ quyền phải nằm ở gói VietMap, không phải ở app mình. Gỡ VietMap ⇒ bỏ hẳn
        // (không vá được ⇒ không đòi, xem [AuditFeatures.vmInstalled]).
        if (f.vmBubble && f.vmInstalled) { out += Grant.VM_FLOAT_LIST; out += Grant.VM_OVERLAY_OP }
        // VietMap phải sống ở nền để widget/bóng còn nguồn — và để đường Cast (openProjection) chiếu được nó
        // lên cụm; chính vì Cast mà `SimpleCastCoordinator` đã whitelist doze cho VietMap từ trước (spec §4.2
        // mục 8: "Cần khi Cast bật"). Gộp cả ba cổng để đường đọc-trước phủ đúng chỗ cái cờ một-lần từng phủ.
        if (f.needsVietMapAlive) out += Grant.DOZE_VIETMAP
        if (f.anyBackgroundFeature) out += Grant.DOZE_SELF
        return out
    }

    /**
     * Có phải mở phiên dadb không. Mở khi (a) có mục CẦN mà không đọc được in-process (phải đọc bằng shell),
     * hoặc (b) có mục đọc in-process đang THIẾU (vá phải bằng shell). Đủ hết + không mục nào cần shell ⇒
     * `false` ⇒ audit không tốn một lệnh nào (R1/R-nf1).
     */
    fun needsShellSession(required: Set<Grant>, okInProcess: Set<Grant>): Boolean =
        required.any { it !in IN_PROCESS_READABLE || it !in okInProcess }

    /** Còn thiếu = cần − đang OK. Giữ thứ tự enum để log/so sánh ổn định. */
    fun missing(required: Set<Grant>, ok: Set<Grant>): Set<Grant> =
        required.filter { it !in ok }.sortedBy { it.ordinal }.toCollection(LinkedHashSet())

    /**
     * Mode của một appop từ output `appops get <pkg> <OP>` có phải `allow`.
     *
     * Định dạng thật (Android 10, [ĐO] fixture `docs/refactor-car-execution/fixtures/appops-get-clusternav.txt`):
     * `SYSTEM_ALERT_WINDOW: allow; time=+37m46s20ms ago; duration=…`. Có ROM in gọn `SYSTEM_ALERT_WINDOW: allow`.
     * Gói chưa từng được set thì in `default` / `No operations.` / rỗng ⇒ **coi là CHƯA cấp** (đúng: với
     * `SYSTEM_ALERT_WINDOW`, `MODE_DEFAULT` không cho vẽ overlay khi app không phải preinstalled).
     *
     * Chỉ nhận mode nằm NGAY sau tên op (`<OP>:`) — không dùng `contains("allow")` để một dòng khác chứa chữ
     * "allow" (vd op khác trong cùng dump) không lừa được cổng này.
     */
    fun appopAllowed(appopsGetOutput: String, op: String = OP_OVERLAY): Boolean {
        if (appopsGetOutput.isBlank()) return false
        val needle = "$op:"
        return appopsGetOutput.lineSequence().any { line ->
            val at = line.indexOf(needle)
            if (at < 0) return@any false
            val mode = line.substring(at + needle.length).substringBefore(';').trim()
            mode.equals("allow", ignoreCase = true)
        }
    }

    /**
     * Gói [pkg] có trong output `dumpsys deviceidle whitelist` chưa.
     *
     * Định dạng mỗi dòng: `<nguồn>,<package>,<uid>` (vd `user,vn.vietmap.live,10145`; `system,com.android.x,10012`).
     * So khớp theo **segment tách bằng dấu phẩy** để `com.foo.bar` không khớp nhầm `com.foo.barbaz`; cũng chấp
     * dòng chỉ có tên gói (ROM in gọn). Output rỗng / lệnh không hỗ trợ ⇒ `false` ⇒ audit sẽ thêm lại (lệnh
     * `+pkg` idempotent, không hại).
     */
    fun dozeWhitelisted(dumpsysWhitelistOutput: String, pkg: String): Boolean {
        if (dumpsysWhitelistOutput.isBlank()) return false
        return dumpsysWhitelistOutput.lineSequence().any { line ->
            line.split(',').any { it.trim() == pkg }
        }
    }

    /**
     * Component [comp] có trong một secure setting dạng danh sách phẳng ngăn bởi `:`
     * (`enabled_notification_listeners`, `enabled_accessibility_services`).
     *
     * So khớp CHÍNH XÁC từng phần tử (không `contains`): một chuỗi con trùng tên gói nhưng khác class KHÔNG
     * được coi là đã bật. `null` (khoá chưa đặt) ⇒ `false`.
     */
    fun flatHasComponent(flatValue: String?, comp: String): Boolean {
        val flat = flatValue?.trim() ?: return false
        if (flat.isEmpty() || flat == "null") return false
        return flat.split(':').any { it.trim() == comp }
    }

    /**
     * Giá trị một secure setting dạng `<package>/<class>` (vd `secure assistant`,
     * `voice_interaction_service`) có trỏ tới [pkg] không. `null` / rỗng / `"null"` ⇒ `false`.
     */
    fun serviceValuePointsTo(secureValue: String?, pkg: String): Boolean {
        val v = secureValue?.trim() ?: return false
        if (v.isEmpty() || v == "null") return false
        return v.substringBefore('/').trim() == pkg
    }

    /** Tên appop overlay — một chỗ duy nhất, dùng cho cả lệnh đọc lẫn lệnh cấp. */
    const val OP_OVERLAY = "SYSTEM_ALERT_WINDOW"
}
