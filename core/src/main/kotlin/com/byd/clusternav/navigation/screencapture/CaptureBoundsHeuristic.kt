package com.byd.clusternav.navigation.screencapture

/**
 * Chọn node a11y ứng viên cho bounds mũi tên (Waze) / icon camera (VietMap) — THUẦN, không Android, để
 * `NavAccessibilityService` (:app, biết Android) chỉ lo gom node → gọi đây → publish vào [CaptureBoundsSource].
 *
 * Đây là phần khoá-được-off-car của deliverable T3: LOGIC chọn node (deterministic) test được; còn việc a11y
 * đọc ĐÚNG node/độ ổn định trên xe là VERIFY-ON-CAR (OQ2). Heuristic (không phải chân lý — tune on-car):
 *   - lọc ứng viên GIAO với [region] (vùng app dẫn, loại nhiễu app nửa kia khi split);
 *   - ưu tiên node có contentDescription/className khớp TỪ KHOÁ target (mũi tên: turn/exit/arrow/rẽ…;
 *     camera: camera/speed/tốc độ/phạt…);
 *   - trong nhóm khớp, chọn node DIỆN TÍCH NHỎ NHẤT (icon nhỏ, tránh chọn cả panel) nhưng ≥ MIN_ICON_PX²;
 *   - không có node khớp từ khoá → chọn node ảnh (ImageView) nhỏ nhất trong region (fallback);
 *   - không có gì hợp lệ → null (router rớt về tầng cố-định, degrade-safe).
 */
object CaptureBoundsHeuristic {

    /** Một node a11y đã rút gọn: class + mô tả + bounds tuyệt đối (đã đọc getBoundsInScreen). */
    data class Candidate(
        val className: String,
        val contentDescription: String,
        val rect: CropRect,
    )

    /** Icon phải ít nhất ~ MIN_ICON_PX mỗi chiều (chống chọn node 1px / rỗng). */
    private const val MIN_ICON_PX = 8

    /** Icon không nên quá to (panel/toàn màn). Trần theo cạnh — icon mũi tên/camera thường ≤ ~320px. */
    private const val MAX_ICON_PX = 360

    private val ARROW_KEYS = listOf(
        "turn", "arrow", "exit", "roundabout", "keep", "merge", "ramp", "rẽ", "ngã", "vòng xuyến", "lối ra",
    )
    private val CAMERA_KEYS = listOf(
        "camera", "speed camera", "phạt", "phat", "tốc độ", "toc do", "cam", "radar", "enforcement",
    )
    private val IMAGE_CLASSES = listOf("ImageView", "ImageButton", "Image")

    /**
     * Chọn CropRect tốt nhất cho [target] từ [candidates], giới hạn trong [region]. null nếu không có ứng viên
     * hợp lệ. Deterministic (không đọc thời gian / trạng thái).
     */
    fun pick(target: CaptureTarget, candidates: List<Candidate>, region: CropRect): CropRect? {
        if (candidates.isEmpty() || region.isEmpty()) return null
        val keys = if (target == CaptureTarget.CAMERA) CAMERA_KEYS else ARROW_KEYS

        // Clamp mọi ứng viên về region + lọc kích thước hợp lệ.
        val inRegion = candidates.mapNotNull { c ->
            val clamped = c.rect.clampTo(region)
            if (clamped.isEmpty()) return@mapNotNull null
            if (clamped.width < MIN_ICON_PX || clamped.height < MIN_ICON_PX) return@mapNotNull null
            if (clamped.width > MAX_ICON_PX && clamped.height > MAX_ICON_PX) return@mapNotNull null
            c to clamped
        }
        if (inRegion.isEmpty()) return null

        // 1) Ứng viên khớp TỪ KHOÁ (desc hoặc class) → nhỏ nhất thắng.
        val keyed = inRegion.filter { (c, _) -> matchesKeyword(c, keys) }
        smallest(keyed)?.let { return it }

        // 2) Fallback: node ẢNH (ImageView…) trong region → nhỏ nhất thắng.
        val images = inRegion.filter { (c, _) -> IMAGE_CLASSES.any { c.className.contains(it, ignoreCase = true) } }
        smallest(images)?.let { return it }

        // 3) Không có gợi ý nào → không đoán bừa (để tầng cố-định lo). Trả null.
        return null
    }

    private fun matchesKeyword(c: Candidate, keys: List<String>): Boolean {
        val hay = (c.contentDescription + " " + c.className).lowercase()
        return keys.any { hay.contains(it) }
    }

    private fun smallest(list: List<Pair<Candidate, CropRect>>): CropRect? =
        list.minByOrNull { (_, r) -> r.width.toLong() * r.height.toLong() }?.second
}
