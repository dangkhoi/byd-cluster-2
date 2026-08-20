package com.byd.clusternav.navigation.screencapture

/**
 * Snapshot bounds ĐỘNG mà `NavAccessibilityService` (:app) đọc được (`getBoundsInScreen` của node mũi tên
 * Waze / icon camera VietMap) — nguồn cho TẦNG 1 của [CaptureRouter.computeBounds] (§4.4 spec
 * waze-vietmap-screen-capture). Service GHI, [com.byd.clusternav.navigation.screencapture] ĐỌC qua router.
 * Thuần `@Volatile`, không khoá — cùng khuôn với `NavAccessibilitySource` (Q1: state holder thuần ở :core).
 *
 * Toạ độ TUYỆT ĐỐI trong không gian ảnh của display app dẫn đang ở; router tự clamp về nửa app khi split.
 * Nếu a11y KHÔNG publish (không tìm được node / chưa cấp quyền hỗ trợ) thì snapshot cũ hết tươi → router tự
 * rớt xuống tầng cố-định (degrade-safe, R-nf1).
 *
 * ⚠ VERIFY-ON-CAR (OQ2): việc a11y đọc ĐÚNG node mũi tên/camera + bounds ổn định trên xe (nhất là trên
 * display cụm) CHƯA xác minh. Off-car chỉ khoá được đường publish→snapshot + gate freshness (test thuần).
 */
object CaptureBoundsSource {

    /** a11y coi là "tươi" trong bao lâu; router chấm lại bằng `now - capturedAtMs` (đây chỉ là hằng tham chiếu). */
    const val FRESH_MS = 1500L

    @Volatile private var left = 0
    @Volatile private var top = 0
    @Volatile private var right = 0
    @Volatile private var bottom = 0
    @Volatile private var capturedAtMs = 0L

    /** a11y GHI: vùng node mũi tên/camera (toạ độ màn tuyệt đối) + mốc đơn điệu [now]. */
    fun publish(l: Int, t: Int, r: Int, b: Int, now: Long) {
        left = l; top = t; right = r; bottom = b; capturedAtMs = now
    }

    /**
     * Snapshot cho router (tầng 1). null nếu chưa từng publish HOẶC rect rỗng. Router tự chấm freshness bằng
     * `now - capturedAtMs <= freshMs`, nên ở đây KHÔNG lọc theo thời gian (giữ hàm thuần, không đọc đồng hồ).
     */
    fun snapshot(): CaptureBounds? {
        if (capturedAtMs <= 0L) return null
        val rect = CropRect(left, top, right, bottom)
        if (rect.isEmpty()) return null
        return CaptureBounds(rect, capturedAtMs)
    }

    /** Xoá (nav idle / mất foreground) → router rớt về tầng cố-định. */
    fun clear() {
        left = 0; top = 0; right = 0; bottom = 0; capturedAtMs = 0L
    }
}

/**
 * Snapshot package dẫn đang FOREGROUND theo a11y — cho GATE của nguồn screen-capture (R5) khi KHÔNG có kênh
 * data (Waze/VietMap không noti → `SourceArbiter` không giữ khoá). `SourceArbiter` chỉ tươi khi có frame
 * data; đây là đường thứ hai để gate mở cho ca ẢNH-thuần.
 *
 * `NavAccessibilitySource.foreground` chỉ theo GMaps; holder này theo MỌI nav package (Waze/VietMap/WazeMod/
 * GMaps) để gate ảnh mở đúng cho app đang thật sự ở foreground.
 *
 * ⚠ VERIFY-ON-CAR (OQ2): phụ thuộc a11y được cấp quyền + app bắn event trên xe.
 */
object CaptureForegroundSource {

    /** Cùng ngưỡng tươi với `NavAccessibilitySource.FRESH_MS` (3s) — foreground event thưa hơn bounds. */
    const val FRESH_MS = 3000L

    @Volatile var pkg: String? = null
        private set
    @Volatile private var lastEventAt = 0L

    /** a11y GHI khi có event của một nav package (foreground trên display của nó). */
    fun publish(pkg: String, now: Long) {
        this.pkg = pkg
        lastEventAt = now
    }

    /** Foreground còn tươi không (gate). */
    fun isFresh(now: Long): Boolean = lastEventAt > 0L && now - lastEventAt <= FRESH_MS

    fun clear() {
        pkg = null
        lastEventAt = 0L
    }
}
