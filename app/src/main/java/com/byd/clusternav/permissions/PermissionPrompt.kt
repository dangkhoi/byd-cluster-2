package com.byd.clusternav.permissions

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import com.byd.clusternav.Lang
import com.byd.clusternav.MainActivity
import com.byd.clusternav.Prefs
import com.byd.clusternav.R
import com.byd.clusternav.core.AuditReport
import com.byd.clusternav.core.Grant

/**
 * Mặt người của bộ kiểm-tra-quyền: **popup** khi còn thiếu quyền mà app không tự vá được.
 *
 * Đủ hết ⇒ [PermissionAuditRunner] pass im lặng và KHÔNG gọi vào đây (owner: *"nếu OK hết thì pass"*). Chỉ
 * khi còn thiếu sau khi đã tự vá mới hiện:
 *  • **Mở app** → [show]: `AlertDialog` liệt kê từng mục thiếu bằng tiếng người + nút "Cấp lại quyền"
 *    (chạy lại vòng vá với `AWAIT_ADB_APPROVAL` để owner bấm được "Cho phép gỡ lỗi USB") + "Để sau".
 *  • **Boot nền** → [postBootNotification]: headless, KHÔNG dựng được dialog (không Activity, và
 *    background-activity-start bị chặn) ⇒ một notification chạm-để-mở-app; lượt audit lúc mở app sẽ popup.
 *
 * Dialog dựng BẰNG CODE (framework `AlertDialog`, không AppCompat — cùng tiền lệ
 * [com.byd.clusternav.modules.clustercast.CastDeepRescueAction]) để KHÔNG chạm layout XML đang niêm phong
 * byte-seal T11 và KHÔNG đổi `strings.xml`; chữ đi qua [Lang.t] nên song ngữ VI/EN như phần còn lại của app.
 */
object PermissionPrompt {

    private const val TAG = "PermPrompt"
    private const val CHANNEL_ID = "clusternav_permission_audit"

    /** Khác FloatingBubbleService (1042) / BootSetupService (1043) / VietMapAutostartService (1044). */
    private const val NOTIFICATION_ID = 1045

    /**
     * Nhãn tiếng người cho từng quyền — nói **tính năng nào mất** chứ không đọc tên khoá kỹ thuật, vì đây là
     * chữ owner đọc lúc đang ngồi trên xe.
     */
    fun label(grant: Grant): String = when (grant) {
        Grant.NOTIF_LISTENER -> Lang.t("Quyền đọc thông báo (dẫn đường Google Maps)", "Notification access (Google Maps navigation)")
        Grant.A11Y_SERVICE -> Lang.t("Trợ năng (phím vật lý → trợ lý, đọc cự ly)", "Accessibility (physical key → assistant, distance read)")
        Grant.SELF_OVERLAY -> Lang.t("Quyền vẽ lên cụm của ClusterNav", "ClusterNav overlay permission")
        Grant.SELF_FLOAT_LIST -> Lang.t("ClusterNav trong danh sách cho phép của xe", "ClusterNav in the car's float allow-list")
        Grant.VM_FLOAT_LIST -> Lang.t("VietMap trong danh sách cho phép của xe (bóng trên cụm)", "VietMap in the car's float allow-list (cluster bubble)")
        Grant.VM_OVERLAY_OP -> Lang.t("Quyền vẽ bóng của VietMap", "VietMap overlay permission")
        Grant.ASSISTANT -> Lang.t("Trợ lý hệ thống = Google/Gemini", "System assistant = Google/Gemini")
        Grant.DOZE_SELF -> Lang.t("ClusterNav được chạy nền không bị ngủ", "ClusterNav exempt from battery doze")
        Grant.DOZE_VIETMAP -> Lang.t("VietMap được chạy nền không bị ngủ", "VietMap exempt from battery doze")
    }

    /** Vì sao chưa vá được — phần này quyết định owner phải LÀM GÌ, nên nói thẳng thao tác. */
    fun shellHint(reason: String?): String = when (reason) {
        "AWAITING_APPROVAL", "AUTH_REJECTED" -> Lang.t(
            "Xe đang hỏi \"Cho phép gỡ lỗi USB?\" — bấm Cho phép (và tích \"luôn cho phép\") rồi thử lại.",
            "The car is asking \"Allow USB debugging?\" — tap Allow (tick \"always allow\"), then retry.",
        )
        "PORT_CLOSED" -> Lang.t(
            "Cổng gỡ lỗi nội bộ của xe chưa bật nên app không tự cấp quyền được.",
            "The car's local debug port is off, so the app cannot self-grant.",
        )
        else -> Lang.t(
            "Chưa nối được vào xe để cấp quyền — thử lại sau vài giây.",
            "Could not reach the car to grant permissions — retry in a few seconds.",
        )
    }

    /** Nội dung popup: liệt kê mục thiếu + (nếu có) lý do chưa vá được. */
    fun message(report: AuditReport): String = buildString {
        append(
            Lang.t(
                "Còn thiếu quyền nên tính năng sau chưa chạy đúng:",
                "These permissions are still missing, so the following won't work properly:",
            ),
        )
        report.missingAfter.sortedBy { it.ordinal }.forEach { append("\n  • ").append(label(it)) }
        if (report.shellBlocked) append("\n\n").append(shellHint(report.shellReason))
    }

    /**
     * Popup khi owner đang ở trong app. No-op nếu [AuditReport.allOk] (không quấy rầy khi đủ) hoặc Activity
     * đang chết. Nút "Cấp lại quyền" chạy lại audit với cờ force (bỏ cooldown) rồi báo kết quả bằng Toast.
     */
    fun show(activity: Activity, report: AuditReport) {
        if (report.allOk) return
        if (activity.isFinishing || activity.isDestroyed) return
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle(Lang.t("Thiếu quyền", "Missing permissions"))
                .setMessage(message(report))
                .setCancelable(true)
                .setPositiveButton(Lang.t("Cấp lại quyền", "Grant again")) { _, _ ->
                    Toast.makeText(
                        activity,
                        Lang.t("Đang cấp lại quyền…", "Granting permissions…"),
                        Toast.LENGTH_SHORT,
                    ).show()
                    PermissionAuditRunner.retryFromPrompt(activity.applicationContext) { again ->
                        if (activity.isFinishing || activity.isDestroyed) return@retryFromPrompt
                        Toast.makeText(
                            activity,
                            when {
                                // Lượt bị bỏ (single-flight: một lượt khác đang chạy dở) ⇒ CHƯA chạy lệnh nào.
                                // Nói "đã cấp đủ quyền" ở đây là nói sai — [AuditReport.skipped] tách hai ca đó.
                                again.skipped -> Lang.t(
                                    "Đang có lượt kiểm tra khác chạy — thử lại sau vài giây.",
                                    "Another check is still running — try again in a few seconds.",
                                )
                                again.allOk -> Lang.t("Đã cấp đủ quyền.", "All permissions granted.")
                                else -> message(again)
                            },
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                .setNegativeButton(Lang.t("Để sau", "Later"), null)
                .show()
        }.onFailure { Log.w(TAG, "không hiện được popup quyền: ${it.message}") }
    }

    /**
     * Boot nền: đăng notification "thiếu quyền — chạm để cấp lại". No-op khi đủ. Chạm ⇒ mở [MainActivity],
     * và lượt audit lúc mở app sẽ hiện dialog với danh sách mới nhất (không truyền state qua Intent — đọc lại
     * bao giờ cũng đúng hơn state đã cũ).
     *
     * **CHỈ báo khi KẾT LUẬN ĐỔI** ([AuditReport.nagSignature]). Boot chạy retry `BACKGROUND_READ_CAP`, nên một
     * xe chưa cấp được khoá adb (`PORT_CLOSED` / `AWAITING_APPROVAL`) sẽ `shellBlocked` ⇒ mọi mục cần shell vào
     * "còn thiếu" ⇒ nếu báo vô điều kiện thì notification bung ra MỖI LẦN NỔ MÁY, mãi mãi, dù không có gì mới.
     * Đó là quấy rầy (R8) chứ không phải thông tin: owner đang khởi động xe, và bề mặt thật để cấp lại là dialog
     * lúc mở app (có `AWAIT_ADB_APPROVAL` nên hộp thoại "Cho phép gỡ lỗi USB" mới bung ra được). Xe lành lại ⇒
     * xoá chữ ký, nên nếu sự cố CŨ quay lại thì vẫn được báo tiếp một lần nữa.
     */
    fun postBootNotification(ctx: Context, report: AuditReport) {
        val app = ctx.applicationContext
        if (report.allOk) {
            // Lành (hoặc lượt bị bỏ) ⇒ quên chữ ký cũ để lần sau hỏng LẠI vẫn báo được đúng một lần.
            runCatching { Prefs.setPermNagSignature(app, "") }
            return
        }
        val signature = report.nagSignature()
        if (runCatching { Prefs.permNagSignature(app) }.getOrDefault("") == signature) {
            Log.i(TAG, "boot: kết luận không đổi ($signature) — KHÔNG báo lại (chống quấy rầy mỗi lần nổ máy)")
            return
        }
        // Tiến trình boot headless chưa Activity nào gọi [Lang.load] ⇒ cache ngôn ngữ rỗng ⇒ Lang.t rơi về VI dù
        // owner chọn EN. Nạp trước khi dựng chữ (đọc pref, không đụng UI).
        runCatching { Lang.load(app) }
        runCatching {
            val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        Lang.t("ClusterNav quyền", "ClusterNav permissions"),
                        NotificationManager.IMPORTANCE_DEFAULT,
                    ),
                )
            }
            val open = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            @Suppress("DEPRECATION")
            val n = android.app.Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(Lang.t("ClusterNav thiếu quyền", "ClusterNav is missing permissions"))
                .setContentText(
                    Lang.t("Chạm để cấp lại — ", "Tap to grant — ") +
                        report.missingAfter.size + Lang.t(" mục", " item(s)"),
                )
                .setStyle(android.app.Notification.BigTextStyle().bigText(message(report)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
            Prefs.setPermNagSignature(app, signature)   // chỉ ghim SAU khi đã báo được
        }.onFailure { Log.w(TAG, "không đăng được notification quyền: ${it.message}") }
    }
}
