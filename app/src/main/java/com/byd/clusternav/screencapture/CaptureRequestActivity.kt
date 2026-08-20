package com.byd.clusternav.screencapture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log

/**
 * Activity XIN consent MediaProjection MỘT LẦN cho CASE 4 (§4.2/R4). Kết quả (resultCode + data) cất trong
 * [MediaProjectionHolder] để [OffscreenMirrorCapturer] dùng headless về sau. KHÔNG UI (translucent), tự finish.
 *
 * ⚠ BẮT BUỘC `android:exported="false"` trong Manifest (R-nf6 — release APK không có bề mặt test/consent nào
 * export/tiếp cận được từ ngoài). Ở lát B3 này activity KHÔNG được tự bung; nó là scaffold để đường A
 * (MediaProjection) có consent-path khi owner bật trên xe (hoặc thay bằng auto-grant dadb OQ1). VERIFY-ON-CAR.
 */
class CaptureRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ok = runCatching {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
            if (mgr == null) {
                finish(); return
            }
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
            true
        }.getOrElse {
            Log.w(TAG, "createScreenCaptureIntent failed", it)
            finish()
            false
        }
        if (!ok) finish()
    }

    @Deprecated("startActivityForResult is deprecated but fine for a one-shot consent scaffold")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        runCatching {
            if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
                MediaProjectionHolder.store(resultCode, data)
                Log.i(TAG, "MediaProjection consent granted → token stored")
            } else {
                Log.i(TAG, "MediaProjection consent denied/cancelled (rc=$resultCode)")
            }
        }
        finish()
    }

    companion object {
        private const val TAG = "CaptureRequest"
        private const val REQ_PROJECTION = 0x0B3C
    }
}
