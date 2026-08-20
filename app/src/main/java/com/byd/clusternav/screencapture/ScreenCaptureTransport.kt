package com.byd.clusternav.screencapture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.byd.clusternav.modules.clustercast.simplified.SimpleCastRuntime
import java.io.File

/**
 * Transport B (đường dadb `fission_screencap`, ĐÃ PROVEN on-car) cho CASE 1/2/3 — TÁI DÙNG chính xác đường
 * chụp của [com.byd.clusternav.SegmentShotCapturer] (owner đã chốt on-car 2026-08-17): chạy
 * `fission_screencap -d <display> -p <path>` qua CÙNG dadb loopback app dùng cho shell
 * ([SimpleCastRuntime.coordinator]/`executeShell`), rồi ĐỌC LẠI file PNG → decode → `Bitmap`.
 *
 * fission display id ĐẢO so với Android (proven): fission `-d 0` = CỤM composite; fission `-d 1` = MÀN CHÍNH.
 *   - CASE 1/2 (app dẫn trên màn chính) → [FISSION_MAIN] = 1.
 *   - CASE 3 (app dẫn đã cast sang cụm)  → [FISSION_CLUSTER] = 0.
 *
 * Envelope an toàn (mirror SegmentShotCapturer, R-nf1/2): mỗi capture bọc `runCatching` — lỗi shell / thiếu
 * quyền screencap / decode hỏng → trả null (bỏ frame), KHÔNG ném vào caller. File tạm nằm ở `cacheDir` NỘI BỘ
 * (KHÔNG phải `getExternalFilesDir/diag` — nên nó không bao giờ là artefact chẩn đoán) và được xoá ngay sau decode.
 *
 * Gọi trên EXECUTOR của [ScreenCaptureNavSource] (không main thread). `executeShell` là blocking (1 round-trip).
 */
class ScreenCaptureTransport(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Chụp display fission [fissionDisplay] → `Bitmap` (ARGB_8888) hoặc null nếu lỗi/không đọc được.
     * [fissionDisplay] = [FISSION_MAIN] (1) cho màn chính, [FISSION_CLUSTER] (0) cho cụm.
     *
     * Đường chính = `fission_screencap` (proven on-car). **Fallback (chỉ MÀN CHÍNH):** khi fission fail/không có
     * (vd EMULATOR không có `fission_screencap`) → standard `screencap -p` (default display = màn chính; proven
     * chạy trên emulator). CỤM (fission CLUSTER) KHÔNG fallback — chỉ on-car nơi fission chạy — tránh chụp nhầm
     * display 0. Fallback này giúp B3 test end-to-end được off-car (emulator) + robust hơn on-car.
     */
    fun captureFission(fissionDisplay: Int): Bitmap? {
        val viaFission = captureShell("fission_screencap -d $fissionDisplay", "fission-d$fissionDisplay")
        if (viaFission != null) return viaFission
        if (fissionDisplay != FISSION_MAIN) return null   // cụm: fission-only (on-car)
        return captureShell("screencap", "screencap-main")
    }

    /** Chạy `<cmdPrefix> -p <file>` qua dadb shell → decode PNG → Bitmap; xoá file tạm. null nếu lỗi. Degrade-safe. */
    private fun captureShell(cmdPrefix: String, tag: String): Bitmap? = runCatching {
        val dir = File(appContext.cacheDir, "screencap").apply { mkdirs() }
        val out = File(dir, "cap-$tag.png")
        val cmd = "$cmdPrefix -p ${out.absolutePath}"
        val result = runCatching {
            SimpleCastRuntime.coordinator(appContext).executeShell(cmd)
        }.getOrNull()
        if (result == null || !result.success) {
            Log.w(TAG, "$tag failed exit=${result?.exitCode}")
            runCatching { out.delete() }
            return@runCatching null
        }
        val bmp = runCatching { BitmapFactory.decodeFile(out.absolutePath) }.getOrNull()
        runCatching { out.delete() }   // xoá tạm ngay (không giữ artefact); diag save là việc RIÊNG của caller
        if (bmp == null) Log.w(TAG, "decode $tag PNG null (empty/locked screencap?)")
        bmp
    }.getOrElse {
        Log.w(TAG, "$tag threw", it)
        null
    }

    companion object {
        private const val TAG = "ScreenCapTransport"

        /** fission `-d 1` = MÀN CHÍNH (proven on-car 2026-08-17). CASE 1/2. */
        const val FISSION_MAIN = 1

        /** fission `-d 0` = CỤM composite (proven on-car 2026-08-17). CASE 3. */
        const val FISSION_CLUSTER = 0
    }
}
