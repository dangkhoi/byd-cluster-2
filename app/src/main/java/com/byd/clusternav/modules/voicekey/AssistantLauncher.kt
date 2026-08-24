package com.byd.clusternav.modules.voicekey

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.util.Log
import com.byd.clusternav.AdbKeys
import com.byd.clusternav.Prefs
import com.byd.clusternav.carexec.LocalDeviceShell

/**
 * Mở đích của "Nút vật lý → mở app". Rework 1.19: đích = 1 STRING — hoặc **package name** (mở thẳng app),
 * hoặc 1 trong 2 **sentinel** đặc biệt. Gọi từ [com.byd.clusternav.modules.navaccess.NavAccessibilityService]
 * (không có Activity context) → cần FLAG_ACTIVITY_NEW_TASK.
 *
 * BỎ kiểu đoán ACTION_ASSIST theo enum trợ lý (thủ phạm 1.18: chọn Kiki nhưng mở Gemini vì intent chung
 * dính trợ lý mặc định). Chọn app trực tiếp → launch-intent của đúng package → không lạc app khác.
 */
object AssistantLauncher {
    private const val TAG = "VoiceKeyLauncher"

    // Chống self-loop [P2 review]: nếu capture keycode == 231 (preset VOICE_ASSIST) + target Gemini, emit 231 có thể
    // tự kích lại onKeyEvent(231) → storm. Debounce = tối đa 1 emit / khoảng này (cùng tinh thần 8hare 800ms).
    private const val VOICE_ASSIST_DEBOUNCE_MS = 1500L
    @Volatile private var lastVoiceAssistEmitMs = 0L

    // Nguồn CHÂN LÝ DUY NHẤT của 2 sentinel = Prefs (nơi lưu/migrate spec + nơi MainActivity dựng dropdown).
    // Delegate compile-time const → không thể lệch literal giữa producer (Prefs/MainActivity) và consumer (đây).
    /** Trợ lý mặc định hệ thống (ghim đầu list). */
    const val TARGET_ASSIST = Prefs.VK_TARGET_ASSIST

    /** Nhận dạng giọng nói — RecognizerIntent (ghim đầu list). */
    const val TARGET_RECOGNIZER = Prefs.VK_TARGET_RECOGNIZER

    /** Trợ lý hệ thống qua phím cứng: phát KEYCODE_VOICE_ASSIST (231) qua dadb — như app 8hare. */
    const val TARGET_GEMINI_KEY = Prefs.VK_TARGET_GEMINI_KEY

    private const val PKG_BARD = "com.google.android.apps.bard"                 // app Gemini
    private const val PKG_GSA  = "com.google.android.googlequicksearchbox"      // app Google (host voice service)
    private const val GSA_ASSIST = "$PKG_GSA/com.google.android.voiceinteraction.GsaVoiceInteractionService"
    private const val GSA_RECOG  = "$PKG_GSA/com.google.android.voicesearch.serviceapi.GoogleRecognitionService"

    /** Spec này = "muốn Gemini/Google dạng ASSISTANT (nói được)" → phải đi keyevent 231, KHÔNG mở app home.
     *  Gồm: sentinel 231, VÀ khi user lỡ chọn thẳng app Gemini/Google (mở home vô dụng cho voice-key). */
    fun isGeminiVoiceSpec(spec: String): Boolean = spec == TARGET_GEMINI_KEY || spec == PKG_BARD || spec == PKG_GSA

    /** @param spec package name của app, hoặc [TARGET_ASSIST]/[TARGET_RECOGNIZER]/[TARGET_GEMINI_KEY]. */
    fun launch(ctx: Context, spec: String): Boolean {
        // Gemini/Google chỉ có nghĩa dạng ASSISTANT (voice). Mở app home = vô dụng (bug 1.19). → route keyevent 231.
        if (isGeminiVoiceSpec(spec)) return launchViaVoiceAssistKey(ctx)
        val app = ctx.applicationContext
        val candidates: List<Intent> = when (spec) {
            TARGET_ASSIST -> listOf(Intent(Intent.ACTION_ASSIST), Intent(Intent.ACTION_VOICE_COMMAND))
            TARGET_RECOGNIZER -> listOf(
                Intent(RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE),
                Intent(RecognizerIntent.ACTION_WEB_SEARCH),
            )
            else -> buildList {
                // Mở THẲNG app đã chọn: launch-intent của package; fallback ACTION_MAIN+LAUNCHER setPackage.
                app.packageManager.getLaunchIntentForPackage(spec)?.let { add(it) }
                add(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(spec))
            }
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val ok = runCatching { app.startActivity(intent); true }.getOrDefault(false)
            if (ok) {
                Log.i(TAG, "launched target=$spec via ${intent.getPackage() ?: intent.action}")
                return true
            }
        }
        Log.e(TAG, "no activity handled target=$spec")
        return false
    }

    /**
     * Phát **KEYCODE_VOICE_ASSIST (231)** qua dadb loopback (uid shell) — y như app 8hare bắt phím voice
     * rồi chạy `input keyevent 231`. Sự kiện phím-cứng này được hệ thống route tới **trợ lý hệ thống**
     * (đặt = Google/Gemini bằng [setSystemAssistant]) → mở đúng surface voice (auto-listen), KHÔNG chooser,
     * KHÔNG nhầm intent như ACTION_ASSIST. Chạy nền (dadb), degrade-safe.
     */
    private fun launchViaVoiceAssistKey(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastVoiceAssistEmitMs < VOICE_ASSIST_DEBOUNCE_MS) {
            Log.i(TAG, "keyevent 231 bỏ qua (debounce ${VOICE_ASSIST_DEBOUNCE_MS}ms — chống self-loop nếu capture==231)")
            return true
        }
        lastVoiceAssistEmitMs = now
        val app = ctx.applicationContext
        // CHẠY NỀN: phiên dadb ~1-2s — KHÔNG block onKeyEvent (nếu block sẽ trễ/ANR phím). Fire-and-forget.
        Thread {
            runCatching {
                val keys = AdbKeys.ensure(app)
                LocalDeviceShell.session(keys) { sh ->
                    val r = sh("input keyevent 231")
                    Log.i(TAG, "keyevent 231 (VOICE_ASSIST) exit=${r.exitCode} err=${r.errorOutput.trim().take(80)}")
                    r.exitCode == 0
                } ?: Log.e(TAG, "voice-assist key: dadb session null (5555 chưa mở / key chưa allow)")
            }.onFailure { Log.e(TAG, "voice-assist key launch failed: $it") }
        }.start()
        return true   // đã nhận lệnh; emit chạy nền
    }

    /**
     * [một lần] Đặt **trợ lý hệ thống = Google/Gemini** — replicate ĐẦY ĐỦ + ĐÚNG THỨ TỰ recipe app 8hare (proven trên xe BYD).
     * Trả **""** nếu OK; ngược lại trả thông báo lỗi (thiếu app / dadb fail) để hiển thị cho owner.
     * 8hare BẮT BUỘC cả Google app (googlequicksearchbox) LẪN Gemini (bard) phải cài — thiếu 1 trong 2 thì recipe vô hiệu
     * (assist route tới GsaVoiceInteractionService không tồn tại). Có `Thread.sleep(300)` giữa clear+set voice_interaction_service.
     */
    fun setSystemAssistant(ctx: Context): String {
        val app = ctx.applicationContext
        val pm = app.packageManager
        val missing = listOf(PKG_GSA to "Google (googlequicksearchbox)", PKG_BARD to "Gemini (com.google.android.apps.bard)")
            .filter { runCatching { pm.getPackageInfo(it.first, 0) }.isFailure }
            .map { it.second }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "setSystemAssistant: thiếu app bắt buộc: $missing")
            return "Thiếu app bắt buộc: ${missing.joinToString(", ")}. Cài đủ Google App + Gemini rồi bật lại."
        }
        return runCatching {
            val keys = AdbKeys.ensure(app)
            val ok = LocalDeviceShell.session(keys) { sh ->
                sh("settings put secure assistant $GSA_ASSIST")
                sh("settings put secure voice_interaction_service ''")
                Thread.sleep(300)   // như 8hare: để clear settle trước khi set lại (nếu không, set lại có thể bị bỏ qua)
                sh("settings put secure voice_interaction_service $GSA_ASSIST")
                sh("settings put secure voice_recognition_service $GSA_RECOG")
                // byd_float_app_list: APPEND (không clobber app khác) googlequicksearchbox + bard + chính mình.
                val cur = sh("settings get global byd_float_app_list").output.trim()
                val merged = (cur.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "null" } +
                    listOf(PKG_GSA, PKG_BARD, app.packageName)).distinct().joinToString(",")
                sh("settings put global byd_float_app_list $merged")
                sh("appops set $PKG_GSA SYSTEM_ALERT_WINDOW allow")
                sh("appops set $PKG_BARD SYSTEM_ALERT_WINDOW allow")
                Log.i(TAG, "system assistant → Google/Gemini (full 8hare recipe); float_app_list=$merged")
                true
            } ?: false
            if (ok) "" else "Không mở được dadb (5555 chưa bật / key chưa allow trên xe)."
        }.getOrElse { Log.e(TAG, "setSystemAssistant failed: $it"); "Lỗi đặt trợ lý: ${it.message}" }
    }
}
