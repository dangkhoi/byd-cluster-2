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
     * ĐƯỜNG DỰ PHÒNG cho thư mục file tạm (2026-08-21, đo trên emulator ở kích thước cụm 1920×720).
     *
     * VÌ SAO: lệnh chụp chạy qua dadb nên NÓ là tiến trình của **shell (uid 2000)**, không phải của app.
     * Trên xe adbd chạy **root** nên ghi thẳng vào `cacheDir` riêng của app được (đường đã proven on-car
     * 2026-08-17, GIỮ NGUYÊN làm đường CHÍNH). Trên máy adbd KHÔNG root (emulator, và mọi head-unit adbd
     * user-build) thì `screencap -p /data/user/0/<pkg>/cache/...` trả **exit=1 Permission denied** ⇒ mọi
     * khung bị bỏ ⇒ kênh mũi tên câm mà KHÔNG có lỗi nào lộ ra ngoài.
     *
     * CHỌN externalFilesDir (KHÔNG phải `/data/local/tmp`): thư mục này là `drwxrws--- ext_data_rw`, shell
     * ghi được mà app khác KHÔNG đọc trộm được ảnh màn hình; `/data/local/tmp` thì world-executable nên ảnh
     * nằm đó bị lộ. Đây là thư mục con RIÊNG `screencap-tmp`, KHÔNG phải `files/diag` — file vẫn bị xoá ngay
     * sau khi decode nên không bao giờ trở thành artefact chẩn đoán (giữ đúng ý ban đầu của lớp này).
     *
     * Chỉ chuyển sang đường dự phòng SAU KHI đường chính thất bại một lần (rồi nhớ luôn cho các nhịp sau —
     * không trả giá thêm round-trip mỗi khung).
     */
    @Volatile private var useExternalTmp = false

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

    /**
     * Chạy `<cmdPrefix> -p <file>` qua dadb shell → decode PNG → Bitmap; xoá file tạm. null nếu lỗi.
     * Degrade-safe. Nếu đường chính (cacheDir nội bộ) bị shell từ chối ghi thì thử LẠI đúng một lần ở
     * externalFilesDir và nhớ lựa chọn đó (xem [useExternalTmp]).
     */
    private fun captureShell(cmdPrefix: String, tag: String): Bitmap? {
        val first = captureShellIn(cmdPrefix, tag, useExternalTmp)
        if (first != null || useExternalTmp) return first
        // Đường chính hỏng (rất có thể adbd không root ⇒ không ghi được cacheDir riêng của app) → leo 1 nấc.
        useExternalTmp = true
        Log.i(TAG, "$tag: cacheDir bị từ chối → chuyển sang externalFilesDir cho các nhịp sau")
        return captureShellIn(cmdPrefix, tag, true)
    }

    /** Thư mục file tạm theo nấc đã chọn. null nếu không dựng được (external chưa mount) → caller bỏ khung. */
    private fun tmpDir(external: Boolean): File? = runCatching {
        val base = if (external) appContext.getExternalFilesDir(null) ?: return null else appContext.cacheDir
        File(base, if (external) "screencap-tmp" else "screencap").apply { mkdirs() }
    }.getOrNull()

    private fun captureShellIn(cmdPrefix: String, tag: String, external: Boolean): Bitmap? = runCatching {
        val dir = tmpDir(external) ?: return@runCatching null
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
