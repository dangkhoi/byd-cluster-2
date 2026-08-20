package com.byd.clusternav.navigation.screencapture

/**
 * Dựng [AppLocation] (nơi + trạng thái app dẫn) từ output `am stack list` — THUẦN, không Android, để
 * `ScreenCaptureNavSource` (:app) chỉ lo gọi shell rồi giao chuỗi cho đây. Là phần khoá-được-off-car của việc
 * "detect app đang ở đâu" (§4.3 selectCase input).
 *
 * Đọc BOUNDS của stack chứa task app dẫn (mẫu như `AppMover.isWindowedOnMain`):
 * ```
 * Stack id=10 bounds=[0,0][1920,720] displayId=0 userId=0
 *   taskId=33: vn.vietmap.live/…MainActivity bounds=[0,0][960,720] visible=true …
 * ```
 * Suy: displayId của stack; fullscreen nếu stack phủ gần trọn bề rộng display; nếu không → split trái/phải +
 * leftPercent (khớp nghĩa `AppMover.fitToCluster`: vách = W·lp/100). Bề rộng display lấy PROXY = right lớn nhất
 * trong các stack cùng display (stack home/placeholder thường full-width).
 *
 * Q3: navigation KHÔNG import cast → dùng [CaptureSlotSide] (nav-local), không `ClusterSlotSide`. Lớp :app map
 * ở biên nếu cần.
 *
 * ⚠ VERIFY-ON-CAR: giá trị thật (bounds, có visible=true đúng lúc, proxy bề rộng) tuỳ firmware/trim — off-car
 * chỉ khoá logic suy-luận bằng chuỗi tổng hợp. Degrade-safe: không tìm thấy task hợp lệ → dùng [foregroundHint].
 */
object CaptureLocationResolver {

    private val STACK = Regex("""Stack id=\d+ bounds=\[(-?\d+),(-?\d+)]\[(-?\d+),(-?\d+)] displayId=(\d+)""")
    private val TASK = Regex("""taskId=(\d+):\s*(\S+)""")
    private const val SLOP = 4
    private const val DEFAULT_DISPLAY_W = 1920

    private data class StackBox(val left: Int, val right: Int, val displayId: Int)

    /**
     * @param amOut          output `am stack list`.
     * @param pkg            package app dẫn (thường = `SourceArbiter.activeSource` hoặc [CaptureForegroundSource.pkg]).
     * @param navFresh       gate: nguồn dẫn còn tươi (nếu false, router trả null → không capture).
     * @param foregroundHint a11y báo pkg foreground khi am-stack KHÔNG có task visible (đường ảnh-thuần).
     */
    fun resolve(
        amOut: String,
        pkg: String,
        navFresh: Boolean,
        foregroundHint: Boolean,
        mainDisplayId: Int = 0,
        clusterDisplayId: Int = 1,
    ): AppLocation {
        // Quét: với mỗi stack (bounds+display) theo sau bởi các task line; nếu task khớp pkg + visible → chốt.
        var current: StackBox? = null
        val widthByDisplay = HashMap<Int, Int>()
        var hit: StackBox? = null
        var hitVisible = false
        for (line in amOut.lines()) {
            val sm = STACK.find(line)
            if (sm != null) {
                val l = sm.groupValues[1].toIntOrNull() ?: 0
                val r = sm.groupValues[3].toIntOrNull() ?: 0
                val d = sm.groupValues[5].toIntOrNull() ?: -1
                current = StackBox(l, r, d)
                // Proxy bề rộng display = right lớn nhất thấy trên display đó.
                val prev = widthByDisplay[d] ?: 0
                if (r > prev) widthByDisplay[d] = r
                continue
            }
            val tm = TASK.find(line) ?: continue
            val comp = tm.groupValues[2]
            val taskPkg = comp.substringBefore("/")
            if (taskPkg == pkg) {
                val visible = line.contains("visible=true")
                val box = current
                // Ưu tiên task VISIBLE; nếu chưa có hit, giữ hit đầu tiên làm dự phòng.
                if (box != null && (visible || hit == null)) {
                    hit = box
                    hitVisible = visible || hitVisible
                }
            }
        }

        val box = hit
        if (box == null) {
            // Không thấy task → dựa vào a11y foreground. foreground=false ⇒ selectCase → NOT_ACTIVE (case 4).
            return AppLocation(
                pkg = pkg,
                displayId = mainDisplayId,
                isFullscreen = true,
                slotSide = null,
                leftPercent = 50,
                foreground = foregroundHint,
                navFresh = navFresh,
            )
        }

        val displayW = (widthByDisplay[box.displayId] ?: 0).takeIf { it > 0 } ?: DEFAULT_DISPLAY_W
        val stackW = box.right - box.left
        val fullscreen = box.left <= SLOP && stackW >= displayW - SLOP
        val slotSide: CaptureSlotSide?
        val leftPercent: Int
        if (fullscreen) {
            slotSide = null
            leftPercent = 50
        } else if (box.left <= SLOP) {
            // App ở NỬA TRÁI: chiếm [0, right) → vách tại right.
            slotSide = CaptureSlotSide.LEFT
            leftPercent = pct(box.right, displayW)
        } else {
            // App ở NỬA PHẢI: chiếm [left, W) → vách tại left.
            slotSide = CaptureSlotSide.RIGHT
            leftPercent = pct(box.left, displayW)
        }

        return AppLocation(
            pkg = pkg,
            displayId = box.displayId,
            isFullscreen = fullscreen,
            slotSide = slotSide,
            leftPercent = leftPercent,
            // Tìm thấy task visible → foreground trên display đó; task không-visible → dùng a11y hint.
            foreground = hitVisible || foregroundHint,
            navFresh = navFresh,
        )
    }

    private fun pct(divider: Int, displayW: Int): Int =
        if (displayW <= 0) 50 else (100L * divider / displayW).toInt().coerceIn(1, 99)
}
