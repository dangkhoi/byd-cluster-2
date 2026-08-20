package com.byd.clusternav.navigation.screencapture

/**
 * Mô hình THUẦN (không Android) cho nguồn dẫn đường bằng screen-capture (B3 — xem
 * `docs/specs/waze-vietmap-screen-capture.html`, §4.3/§4.4). Đây là phần "khoá được off-car" theo R-nf5:
 * quyết định app đang ở đâu (4 case) + vùng cần crop, không đụng MediaProjection/VirtualDisplay/PixelCopy.
 *
 * Ba khối:
 *   - [CaptureCase]  : app dẫn đang ở 1 trong 4 vị trí hiển thị (§4.3).
 *   - [AppLocation]  : đầu vào thô (nơi app + trạng thái) mà lớp :app đọc từ `am stack`/a11y/arbiter.
 *   - [CropRect]/[CaptureBounds]/[CapturePlan] : đầu ra (vùng crop + tầng bounds dùng).
 */

/** Bốn vị trí app dẫn có thể ở (owner requirement R3, spec §4.3). */
enum class CaptureCase {
    /** Case 1: app dẫn chiếm TOÀN màn chính (Android display 0). */
    FULL_MAIN,

    /** Case 2: app dẫn ở NỬA màn chính (split/freeform trái hoặc phải trên display 0). */
    HALF_MAIN_SPLIT,

    /** Case 3: app dẫn đang chiếu (cast) sang màn CỤM (display 1). */
    CLUSTER_CAST,

    /** Case 4: nav còn tươi nhưng app KHÔNG foreground ở đâu (offscreen / không active). Khó nhất (B3.4). */
    NOT_ACTIVE,
}

/** Vùng cần crop trong nhận diện (arrow của Waze / icon camera của VietMap). Chọn theo package (§4.4). */
enum class CaptureTarget {
    /** Mũi tên hướng rẽ (Waze/GMaps) → nuôi [com.byd.clusternav.navigation.ManeuverSignature]. */
    ARROW,

    /** Icon camera phạt nguội (VietMap) → nuôi [VietMapCameraMatcher]. */
    CAMERA,

    ;

    companion object {
        private val VIETMAP_PKGS = setOf("vn.vietmap.live")

        /** VietMap → CAMERA; còn lại (Waze/WazeMod/GMaps) → ARROW (mặc định an toàn). */
        fun forPackage(pkg: String): CaptureTarget =
            if (pkg in VIETMAP_PKGS) CAMERA else ARROW
    }
}

/** Tầng bounds nào đã được dùng (chẩn đoán + test khẳng định thứ tự ưu tiên §4.4). */
enum class BoundsSource {
    /** Tầng 1: bounds động từ a11y (`getBoundsInScreen`), còn tươi. */
    A11Y_DYNAMIC,

    /** Tầng 2: rect cố định đã hiệu chỉnh theo (app, geometry) — fallback khi a11y không tươi. */
    FIXED_CALIBRATED,

    /** Không có bounds nào áp được (không cả a11y lẫn bảng cố định) — caller bỏ frame. */
    NONE,
}

/**
 * Hình chữ nhật crop THUẦN (thay cho `android.graphics.Rect` — :core không biết Android). Toạ độ theo
 * KHÔNG GIAN ẢNH capture (pixel tuyệt đối của display đang chụp). [right]/[bottom] là exclusive.
 */
data class CropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /** Rỗng/không hợp lệ (không có gì để crop) khi bề rộng hoặc cao ≤ 0. */
    fun isEmpty(): Boolean = width <= 0 || height <= 0

    /** Dời NGANG [dx] pixel (dùng cho Case-2 offset nửa phải). */
    fun offsetX(dx: Int): CropRect = copy(left = left + dx, right = right + dx)

    /**
     * Giao với [region] (clamp). Nếu không giao → trả rect rỗng (isEmpty). Dùng để loại nhiễu app nửa kia
     * (Case 2): a11y trả toạ độ tuyệt đối, clamp về nửa của app dẫn.
     */
    fun clampTo(region: CropRect): CropRect {
        val l = maxOf(left, region.left)
        val t = maxOf(top, region.top)
        val r = minOf(right, region.right)
        val b = minOf(bottom, region.bottom)
        return if (r <= l || b <= t) CropRect(l, t, l, t) else CropRect(l, t, r, b)
    }

    companion object {
        val EMPTY = CropRect(0, 0, 0, 0)
    }
}

/**
 * Kích thước + phân loại display cho router. [displayW]/[displayH] là của display app đang ở.
 * [mainDisplayId]/[clusterDisplayId] để router phân biệt màn chính vs cụm mà KHÔNG hardcode (dù mặc
 * định Android: 0 = chính, 1 = cụm).
 */
data class DisplayGeometry(
    val displayW: Int,
    val displayH: Int,
    val mainDisplayId: Int = 0,
    val clusterDisplayId: Int = 1,
) {
    val fullRect: CropRect get() = CropRect(0, 0, displayW, displayH)
}

/**
 * Nửa màn khi app dẫn ở chế độ split (Case 2 / cast chia đôi cụm). NAV-LOCAL: navigation SỞ HỮU bản của
 * mình để KHÔNG import ngang sang feature Cast (quy tắc Q3 — `LayeringRulesTest.navigation va cast khong
 * goi ngang nhau`). Ánh xạ 1-1 với enum `ClusterSlotSide` của feature Cast; lớp `:app` map
 * hai chiều ở BIÊN (ví dụ `CaptureLocationResolver`).
 */
enum class CaptureSlotSide { LEFT, RIGHT }

/**
 * Đầu vào thô cho [CaptureRouter]: app dẫn đang ở đâu + trạng thái. Lớp :app điền từ `am stack list`
 * (displayId/fullscreen/slot), a11y (foreground) và [com.byd.clusternav.navigation.SourceArbiter] (navFresh).
 *
 * @param pkg          package app dẫn (thường = `SourceArbiter.activeSource`).
 * @param displayId    id display Android mà task app đang nằm (0 = chính, 1 = cụm…).
 * @param isFullscreen task chiếm trọn display (khác split/freeform).
 * @param slotSide     nửa nào khi split ([CaptureSlotSide.LEFT]/[CaptureSlotSide.RIGHT]); null = không split.
 * @param leftPercent  vị trí vách chia theo % từ trái (0..100). Nửa trái = [0, W*lp/100); phải = [W*lp/100, W).
 *                     Cùng nghĩa với `AppMover.fitToCluster`.
 * @param foreground   task app đang hiển thị/foreground trên display của nó.
 * @param navFresh     SourceArbiter báo nguồn dẫn còn tươi (gate §4.2). false → router không capture.
 */
data class AppLocation(
    val pkg: String,
    val displayId: Int,
    val isFullscreen: Boolean,
    val slotSide: CaptureSlotSide? = null,
    val leftPercent: Int = 50,
    val foreground: Boolean = true,
    val navFresh: Boolean = true,
)

/**
 * Bounds động do a11y publish (`NavAccessibilitySource`), kèm mốc thời gian để router quyết "còn tươi"
 * (§4.4 tầng 1). Toạ độ tuyệt đối trong không gian ảnh display.
 */
data class CaptureBounds(val rect: CropRect, val capturedAtMs: Long)

/**
 * Kết quả của [CaptureRouter.route]: case đã chọn + vùng crop + tầng bounds dùng + target (arrow/camera).
 * [bounds].isEmpty() ⇒ caller bỏ frame (không có vùng hợp lệ). route() trả null khi gate đóng (navFresh=false).
 */
data class CapturePlan(
    val case: CaptureCase,
    val target: CaptureTarget,
    val bounds: CropRect,
    val boundsSource: BoundsSource,
)
