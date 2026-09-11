package com.byd.clusternav.modules.navaccess

/**
 * PURE (no-Android) decision + string logic for FORCE-REBINDING the accessibility service.
 *
 * The bug (measured on-car 2026-08-14, see docs/diagnostics/oncar-handoff-voicekey-2026-08-14.md §8):
 * after a reboot [NavAccessibilityService] is ENABLED (present in `settings get secure
 * enabled_accessibility_services`, `accessibility_enabled=1`) but NOT actually BOUND (absent from the
 * `dumpsys accessibility` "Bound services" section). `onServiceConnected` never runs, so `onKeyEvent`
 * (mic-hold voice key) and the screen-read booster are both dead. Writing the setting only makes it
 * *enabled*; it does not force a *bind*.
 *
 * The proven live fix is to TOGGLE the service OUT then IN, which forces the framework to rebind it:
 *   1. write enabled_accessibility_services WITHOUT ClusterNav (OEM services preserved),
 *   2. brief pause,
 *   3. write it back WITH ClusterNav appended, then `accessibility_enabled 1`.
 *
 * This object holds only the parts that can be decided without a device — which settings-write commands to
 * run, and whether the dump says we are bound — so they are covered by a JVM unit test
 * ([AccessibilityRebindTest]). The dadb I/O, pausing and never-leave-removed recovery live in
 * `com.byd.clusternav.NavConnect.doGrantAccessibility` in `:app` (which owns the Android + dadb transport).
 */
object AccessibilityRebind {
    /**
     * Tên CLASS của accessibility service — độc lập với `applicationId`.
     *
     * ⚠ KHÔNG hardcode component đầy đủ ở đây nữa: bản 2.0 đổi `applicationId` sang `com.byd.clusternav2`
     * trong khi Kotlin package vẫn là `com.byd.clusternav`, nên một hằng số "pkg/class" viết tay ở `:core` là
     * bẫy ngủ — nó sai ngay khi ai đó dùng giá trị mặc định (bản cũ để `com.byd.clusternav/...`). Caller ở
     * `:app` truyền `applicationId` thật vào [component].
     */
    const val ACC_SERVICE_CLASS = "com.byd.clusternav.modules.navaccess.NavAccessibilityService"

    /** Component đúng dạng nằm trong `enabled_accessibility_services`, dựng từ `applicationId` của bản build. */
    fun component(applicationId: String): String = "$applicationId/$ACC_SERVICE_CLASS"

    private const val KEY = "enabled_accessibility_services"

    /**
     * Việc CẦN LÀM để service sống lại, quyết từ hai điều đọc được trong `dumpsys accessibility`.
     *
     * Sinh ra 2026-09-11 sau phép đo trên xe owner: service ENABLED, `Bound services` KHÔNG có ClusterNav,
     * `Binding services` CÓ ClusterNav ⇒ AMS đã gọi bind và **kẹt vĩnh viễn** ở đó (còn đọng cả
     * `ConnectionRecord … DEAD` của các tiến trình cũ). Ở trạng thái đó, toggle danh sách setting KHÔNG cứu
     * được — [ĐO] gỡ hẳn component khỏi `enabled_accessibility_services` mà `Binding services` vẫn còn nó, và
     * `force-rebind xong: bound=false`. Chỉ khi **tiến trình app chết** thì AMS mới nhả (`Binding services:{}`),
     * sau đó bind lại thành công.
     */
    enum class RebindStep {
        /** Đã bound — không làm gì (tránh flicker). */
        NONE,

        /** Chưa bound và KHÔNG kẹt ⇒ toggle remove→re-add ép bind (đường proven từ 2026-08-14). */
        TOGGLE,

        /** Chưa bound và AMS đang kẹt ở "Binding services" ⇒ toggle vô ích, phải KHỞI ĐỘNG LẠI TIẾN TRÌNH. */
        RESTART_PROCESS,
    }

    /** PURE: từ hai cờ đọc được → việc cần làm. Xem [RebindStep] cho bằng chứng đo trên xe. */
    fun healStep(bound: Boolean, bindingStuck: Boolean): RebindStep = when {
        bound -> RebindStep.NONE
        bindingStuck -> RebindStep.RESTART_PROCESS
        else -> RebindStep.TOGGLE
    }

    /**
     * The ordered `settings put secure ...` commands that force a REBIND via a remove -> re-add toggle.
     *
     * Returns an EMPTY list when [boundContainsClusterNav] is already true — if the service is genuinely bound
     * we must do NOTHING (no flicker). Otherwise the returned order is exactly:
     *   - `[0]` remove ClusterNav from the enabled list (every other service, incl. the OEM ones, is preserved),
     *   - `[1]` re-add ClusterNav appended after the preserved services,
     *   - `[2]` `accessibility_enabled 1`.
     *
     * The caller MUST pause between `[0]` and the rest, and MUST guarantee `[1..]` run even on failure so the
     * setting is never left in the removed state (see `NavConnect`).
     *
     * [current] is the raw value of `enabled_accessibility_services` (colon-separated, possibly `"null"`,
     * blank, or with stray/duplicate colons). It is normalised: entries are trimmed, blanks and the literal
     * `null` are dropped, and every ClusterNav entry is removed before exactly one is re-appended — so the
     * output never contains a dangling/leading/trailing/double colon, and OEM services keep their exact
     * original strings and relative order. Values are quoted so an empty remove-list is written as `""`.
     *
     * [component] KHÔNG có giá trị mặc định (từ 2026-09-11): caller phải nói rõ component của bản build đang
     * chạy — xem [component]/[ACC_SERVICE_CLASS].
     */
    fun accessibilityRebindWrites(current: String?, boundContainsClusterNav: Boolean, component: String): List<String> {

        if (boundContainsClusterNav) return emptyList()
        val entries = (current ?: "")
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "null" }
        val without = entries.filter { it != component }
        val readd = without + component
        return listOf(
            "settings put secure $KEY \"${without.joinToString(":")}\"",
            "settings put secure $KEY \"${readd.joinToString(":")}\"",
            "settings put secure accessibility_enabled 1",
        )
    }

    /**
     * Whether ClusterNav appears in the **Bound services** section of `dumpsys accessibility` — i.e. the
     * service is really running, not merely listed under "Enabled services". On BYD DiLink (Android 10) the
     * dump prints `Bound services:{ ... }` (each bound service dumped with its label and/or `ComponentInfo{
     * pkg/cls}`) and, separately, `Enabled services:{ ... }`. After a reboot ClusterNav is only in the latter.
     *
     * We scope the search to the balanced braces immediately following the "Bound services" header (nested
     * `ComponentInfo{...}` braces are handled), so a ClusterNav entry in a LATER section (Enabled) is never
     * mistaken for being bound. Matching is case-insensitive on the distinctive token `clusternav`, which
     * appears whether the dump prints the package (`com.byd.clusternav/...`) or the label (`ClusterNav ...`).
     *
     * Fails SAFE: when the dump is null/blank or the section can't be located, returns `true` (treated as
     * bound) so the caller does NOT toggle — never risk a flicker on an unreadable/unexpected dump. The real
     * on-car dump is readable by the uid=shell dadb session, so the heal path still triggers when needed.
     */
    fun isClusterNavBound(dumpsysAccessibility: String?): Boolean {
        val section = sectionAfter(dumpsysAccessibility, "Bound services") ?: return true
        return section.contains("clusternav", ignoreCase = true)
    }

    /**
     * ClusterNav có đang **KẸT** trong mục `Binding services` của `dumpsys accessibility` không.
     *
     * AMS đưa component vào `mBindingServices` khi gọi `bindService` và chỉ lấy ra khi `onServiceConnected`
     * về. [ĐO on-car 2026-09-11] sau khi tiến trình app chết vài lần, component nằm lại đó **vĩnh viễn**:
     * `Bound services` chỉ còn service của systemui, `Binding services:{com.byd.clusternav2/…}`, kèm hai
     * `ConnectionRecord … FGSA DEAD` của chính service này. Ở trạng thái đó ghi setting bao nhiêu lần cũng vô
     * ích (đã thử gỡ hẳn component: vẫn còn trong `Binding services`) — chỉ tiến trình chết mới nhả.
     *
     * Fail-safe NGƯỢC với [isClusterNavBound]: dump không đọc được / không có mục này ⇒ `false` (KHÔNG kết
     * luận là kẹt), vì hành động tương ứng là khởi động lại tiến trình — nặng hơn nhiều một lần toggle, không
     * được phép chạy vì một lần đọc lỗi.
     */
    fun isBindingStuck(dumpsysAccessibility: String?): Boolean {
        val section = sectionAfter(dumpsysAccessibility, "Binding services") ?: return false
        return section.contains("clusternav", ignoreCase = true)
    }

    /**
     * Cắt đoạn `{...}` CÂN BẰNG NGOẶC ngay sau tiêu đề [header] trong dump (ngoặc lồng của `ComponentInfo{…}`
     * được tính đúng), để một mục ở SECTION KHÁC không bị nhận nhầm. `null` khi dump rỗng/không tìm được mục —
     * caller tự quyết fail-safe theo chiều an toàn của mình.
     */
    private fun sectionAfter(dump: String?, header: String): String? {
        if (dump.isNullOrBlank()) return null
        val at = dump.indexOf(header, ignoreCase = true, startIndex = 0)
        if (at < 0) return null
        val open = dump.indexOf('{', at)
        if (open < 0) return null
        var depth = 0
        var i = open
        while (i < dump.length) {
            when (dump[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return dump.substring(open, i + 1)
                }
            }
            i++
        }
        return dump.substring(open)
    }
}

