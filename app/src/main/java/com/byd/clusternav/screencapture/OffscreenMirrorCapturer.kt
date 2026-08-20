package com.byd.clusternav.screencapture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Giữ token MediaProjection (resultCode + data Intent) sau khi user đồng ý MỘT LẦN — kiểu
 * `MediaProjectionHolder` của OpenBYD (§4.2 spec). Rỗng cho tới khi [CaptureRequestActivity] cấp; khi rỗng thì
 * [OffscreenMirrorCapturer] im lặng (degrade). Thuần `@Volatile`.
 */
object MediaProjectionHolder {
    @Volatile var resultCode: Int = Int.MIN_VALUE; private set
    @Volatile var data: Intent? = null; private set

    fun hasToken(): Boolean = resultCode != Int.MIN_VALUE && data != null

    fun store(resultCode: Int, data: Intent) {
        this.resultCode = resultCode
        this.data = data
    }

    fun clear() {
        resultCode = Int.MIN_VALUE
        data = null
    }
}

/**
 * CASE 4 (nav tươi nhưng app KHÔNG foreground ở đâu → không có pixel trên màn thật để chụp): SCAFFOLD chụp
 * qua MediaProjection + AUTO_MIRROR VirtualDisplay + ImageReader (đúng bài `WazeArrowCaptureService` OpenBYD).
 *
 * ⚠ VERIFY-ON-CAR (OQ1/OQ5) — CHƯA chạy thật:
 *   - Cần token MediaProjection (consent 1 lần qua [CaptureRequestActivity], hoặc auto-grant dadb OQ1) — KHÔNG
 *     có token ⇒ [capture] trả null (im lặng). App KHÔNG tự bung dialog ở lát này (không có bề mặt export, R-nf6).
 *   - AUTO_MIRROR chỉ mirror display MẶC ĐỊNH; biến thể "4b" (đưa app render vào VD offscreen) là OQ5, CHƯA làm.
 *   - Mọi bước bọc `runCatching` → lỗi/không quyền/không VD ⇒ null, KHÔNG crash, KHÔNG đụng feed cụm (R-nf1).
 *
 * KHÔNG phải foreground service ở lát này: giữ scaffold gọn + không thêm quyền/loại-FGS. Trên Android 10
 * (xe DiLink3) MediaProjection không đòi FGS type; nếu về sau chạy nền trên SDK 34+ mới cần FGS mediaProjection.
 */
class OffscreenMirrorCapturer(context: Context) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var projection: MediaProjection? = null
    @Volatile private var reader: ImageReader? = null
    @Volatile private var display: VirtualDisplay? = null
    @Volatile private var width = 0
    @Volatile private var height = 0

    /**
     * Chụp 1 frame từ VD mirror → `Bitmap` (đã cắt về đúng bề rộng, loại padding rowStride). null nếu:
     * chưa có token / tạo projection|reader|VD lỗi / chưa có image (acquireLatestImage null). Degrade-safe.
     */
    fun capture(): Bitmap? = runCatching {
        if (!MediaProjectionHolder.hasToken()) return null
        if (!ensureStarted()) return null
        val ir = reader ?: return null
        val image = runCatching { ir.acquireLatestImage() }.getOrNull() ?: return null
        try {
            val plane = image.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val rowPadding = rowStride - pixelStride * width
            val stridedWidth = width + (if (pixelStride > 0) rowPadding / pixelStride else 0)
            if (stridedWidth <= 0 || height <= 0) return null
            val full = Bitmap.createBitmap(stridedWidth, height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(buffer)
            // Cắt phần padding phải để về đúng bề rộng thật của display.
            if (stridedWidth != width) Bitmap.createBitmap(full, 0, 0, width, height) else full
        } finally {
            runCatching { image.close() }
        }
    }.getOrElse {
        Log.w(TAG, "offscreen mirror capture threw", it)
        null
    }

    /** Tạo (một lần) projection + ImageReader + AUTO_MIRROR VirtualDisplay từ token đã cấp. false nếu lỗi. */
    private fun ensureStarted(): Boolean = runCatching {
        if (display != null && reader != null && projection != null) return true
        val mgr = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            ?: return false
        val data = MediaProjectionHolder.data ?: return false
        val mp = mgr.getMediaProjection(MediaProjectionHolder.resultCode, data) ?: return false
        // SDK 34+ đòi registerCallback trước createVirtualDisplay; onStop → dọn + xoá token (như OpenBYD).
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection stopped → release")
                release()
                MediaProjectionHolder.clear()
            }
        }, handler)
        val metrics = appContext.resources.displayMetrics
        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)
        val ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        val vd = mp.createVirtualDisplay(
            "clusternav-offscreen",
            w, h, metrics.densityDpi.coerceAtLeast(1),
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            ir.surface, null, handler,
        ) ?: run { runCatching { ir.close() }; runCatching { mp.stop() }; return false }
        projection = mp; reader = ir; display = vd; width = w; height = h
        true
    }.getOrElse {
        Log.w(TAG, "ensureStarted (MediaProjection VD) threw — degrade", it)
        release()
        false
    }

    /** Dọn VD/ImageReader/projection. An toàn gọi nhiều lần. */
    fun release() {
        runCatching { display?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.stop() }
        display = null; reader = null; projection = null; width = 0; height = 0
    }

    companion object {
        private const val TAG = "OffscreenMirror"
    }
}
