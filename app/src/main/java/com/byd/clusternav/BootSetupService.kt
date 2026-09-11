package com.byd.clusternav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.byd.clusternav.modules.navaccess.NavAccessibilitySource
import com.byd.clusternav.navigation.NavigationOutputTarget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * HEADLESS boot setup (1.21 Item 1). Short-lived foreground service started by [RebindReceiver] on
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED when "Tự khởi động nền" ([Prefs.headlessAutostart]) is ON, so the
 * app performs its boot setup WITHOUT foregrounding [MainActivity] on the main display (bonus: dodges the
 * dudu size-compat letterbox — MainActivity never auto-foregrounds).
 *
 * Relocates the ONLY boot-setup that was tied to MainActivity.onCreate:
 *   1. accessibility grant + force-bind ([NavConnect.grantAccessibility] — includes the 1.20 force-bind), and
 *   2. re-assert the cluster-lane output ([NavRepository.setOutputEnabled] CLUSTER_LANE=true) — covers an
 *      OLD persisted `lane=false` pref for a user who upgraded and never opens the app in headless mode
 *      (MainActivity's Prefs.setLane(true) migration would otherwise never run for them).
 * Both are ADDITIVE and idempotent; MainActivity.onCreate keeps the same setup for the user-opens-app case.
 *
 * NOT touched here (already headless): the nav pipeline (NavNotificationListener.onListenerConnected →
 * NavRepository.setPermission(GRANTED) → connect()) and auto-cast (the cast bubble service is the sole
 * autostart driver, started by RebindReceiver.castBootWork when Cast is enabled).
 *
 * Safety:
 *  • [startForeground] is called FIRST (well within the ~5 s startForegroundService() budget) so a
 *    background start can never be killed with RemoteServiceException.
 *  • The setup runs on a background thread wrapped in runCatching → it can NEVER crash the process.
 *  • The service ALWAYS [finish]es (stopForeground + stopSelf) — the call sits OUTSIDE the runCatching, so
 *    an exception or interrupt still tears the service down.
 *  • The FGS (and therefore the process) is kept alive until the async dadb grant reports back (bounded by
 *    [GRANT_TIMEOUT_MS]) — the plan's rationale for a foreground service: the grant takes ~3–5 s, longer
 *    than a BroadcastReceiver's budget, so a pure-boot stopSelf must not kill it early.
 */
class BootSetupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() contract: go foreground within ~5 s or the system kills us. Do it FIRST,
        // before any (blocking) work; if the platform denies it, stop cleanly.
        if (!startForegroundOnce()) { stopSelf(startId); return START_NOT_STICKY }
        Thread({
            // v1.40: ghi nhận "cần khởi động lại tiến trình để nối lại accessibility" rồi chạy ở BƯỚC CUỐI —
            // KHÔNG chạy ngay lúc phát hiện, vì nó giết luôn service này giữa chuỗi (VietMap autostart + trợ lý
            // Gemini phía dưới sẽ bị bỏ). Chi tiết trạng thái kẹt: [A11yProcessRestart].
            // ATOMIC (không phải `var` thường): cờ được GHI trên main looper (callback của NavConnect.heal) và
            // ĐỌC trên thread này — một biến thường không có rào bộ nhớ nào giữa hai thread đó. Cờ này quyết định
            // có GIẾT tiến trình hay không, nên không để nó phụ thuộc vào may mắn về thứ tự nhìn thấy.
            val needA11yProcessRestart = java.util.concurrent.atomic.AtomicBoolean(false)
            runCatching {
                // Tiện nghi cabin ĐI TRƯỚC (ghế + lọc bụi): cả hai chỉ dùng HAL reflection trong tiến trình,
                // KHÔNG cần một quyền nào của bộ kiểm-tra-quyền, và mỗi hàm chỉ bung thread riêng rồi trả về
                // NGAY (đồng hồ "~5 s" của chúng bắt đầu từ lúc gọi). Đặt sau audit thì một phiên dadb câm
                // (retry BACKGROUND_READ_CAP = hạn đọc 30 s, ca xe chưa/không cấp được khoá adb) sẽ đẩy ghế +
                // lọc bụi trễ tới ~35 s sau khi nổ máy — thứ owner CẢM THẤY ngay. Không có ràng buộc thứ tự nào
                // giữa chúng và audit, nên đưa lên đầu.
                // Ghế: áp mức làm-mát/sưởi lên HAL ~5s sau boot nếu công tắc BẬT (headless boot cũng tự áp,
                // giống app tham chiếu). Gate seatComfortEnabled + degrade-safe nằm trong applyOnStart.
                com.byd.clusternav.comfort.SeatComfortApplier.applyOnStart(applicationContext)
                // Lọc bụi mịn PM2.5: bật lọc-liên-tục (không popup) ~5s sau boot nếu công tắc BẬT. Gate
                // pm25FilterEnabled + degrade-safe nằm trong applyOnStart.
                com.byd.clusternav.comfort.Pm25FilterApplier.applyOnStart(applicationContext)
                // KIỂM TRA QUYỀN (v1.39, spec permission-health-audit) — chạy TRƯỚC mọi bước CẦN quyền: đọc trạng
                // thái thật của mọi quyền mà tính năng ĐANG BẬT cần, đủ ⇒ im lặng (không phát lệnh nào), thiếu ⇒
                // tự vá + đọc lại, còn thiếu ⇒ notification "chạm để cấp lại" (boot headless KHÔNG dựng được
                // dialog; chỉ báo khi KẾT LUẬN đổi — xem PermissionPrompt.postBootNotification).
                // ĐỒNG BỘ ở đây là CỐ Ý: FGS này giữ tiến trình sống, và hai bước NGAY DƯỚI phụ thuộc kết quả —
                // accessibility grant (audit có thể đã cấp ⇒ khỏi làm hai lần) và autostart VietMap (bóng chỉ
                // dựng lúc khởi động nên quyền phải có TRƯỚC khi launch). Degrade-safe: audit tự bọc lỗi.
                val audit = com.byd.clusternav.permissions.PermissionAuditRunner.runForBoot(applicationContext)
                if (audit.needsOwner) {
                    com.byd.clusternav.permissions.PermissionPrompt.postBootNotification(applicationContext, audit)
                }
                // Accessibility grant + 1.20 force-bind: cần khi Nav+HUD (booster đọc GMaps) HOẶC voice-key
                // (nút vật lý → trợ lý) bật. Voice-key KHÔNG phụ thuộc Nav+HUD (owner 2026-09-01: hai tính năng
                // RIÊNG — trước gate chung Nav+HUD nên phím-thoại chết sau boot khi Nav+HUD tắt). Chỉ escalate khi
                // service CHƯA bound (idempotent: grantAccessibility verify dumpsys trước khi toggle → no-op/no
                // flicker nếu đã bound). Async trên thread riêng, báo về main looper → đếm latch; giữ FGS sống tới
                // khi grant xong (bounded GRANT_TIMEOUT_MS).
                if (Prefs.enabled(applicationContext) || Prefs.voiceKeyEnabled(applicationContext)) {
                    if (!NavAccessibilitySource.connected) {
                        val latch = CountDownLatch(1)
                        // v1.40: đọc kết quả CÓ PHÂN LOẠI. Nếu AMS kẹt component ở "Binding services" thì toggle
                        // vô ích (đo on-car 2026-09-11) — phải khởi động lại tiến trình. Boot headless là lúc TỐT
                        // NHẤT để làm việc đó (owner không nhìn màn hình, app tự mở lại sau ~4 s), nhưng KHÔNG làm
                        // ngay tại đây: nó giết luôn service này giữa chuỗi ⇒ ghi nhận rồi chạy ở BƯỚC CUỐI.
                        NavConnect.heal(applicationContext) { outcome ->
                            needA11yProcessRestart.set(outcome == NavConnect.HealOutcome.NEEDS_PROCESS_RESTART)
                            latch.countDown()
                        }
                        latch.await(GRANT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    }
                }
                if (Prefs.enabled(applicationContext)) {
                    // Re-assert the cluster-lane output (belt-and-suspenders for an old lane=false pref). Nav+HUD only.
                    NavRepository.setOutputEnabled(
                        applicationContext, NavigationOutputTarget.CLUSTER_LANE, true,
                    )
                }
                // BOOT headless: auto-start VietMap chạy trong FGS RIÊNG ([VietMapAutostartService]) — KHÔNG
                // block chuỗi setup này (ghế / lọc bụi / Gemini phía dưới chạy NGAY, không đợi VietMap). Nhánh
                // bóng poll tới khi VietMap vào map (tuỳ network) nên tách ra service riêng; boot → về HOME sau.
                // Gate (badge / bóng / cast) + chống-loop nằm trong runNow của service.
                VietMapAutostartService.startForBoot(applicationContext)
                // F4e boot (owner 08-25): boot headless KHÔNG mở MainActivity ⇒ onCreate không chạy ⇒ trợ lý
                // hệ thống chưa được đặt = Gemini ⇒ hold-mic → keyevent 231 route sai. Đặt luôn ở đây NẾU có
                // binding Gemini, để hold-mic → Gemini ready NGAY sau nổ máy mà KHÔNG cần mở app (owner
                // 08-25: "kể cả khởi động nền hay full app đều enable service gemini lên là OK").
                // retry NONE: boot owner KHÔNG ở màn hình để bấm "Cho phép gỡ lỗi USB" ⇒ MỘT lần, không chờ
                // ~31s (tránh treo boot — F6). Hỏng (chưa cấp quyền) ⇒ bỏ; owner mở app lần đầu thì
                // MainActivity.onCreate re-apply với AWAIT_ADB_APPROVAL (có mặt owner để cấp quyền).
                if (com.byd.clusternav.modules.voicekey.AssistantLauncher.hasGeminiBinding(applicationContext)) {
                    val err = com.byd.clusternav.modules.voicekey.AssistantLauncher.setSystemAssistant(
                        applicationContext, com.byd.clusternav.carexec.LocalShellRetry.NONE,
                    )
                    if (err.isNotEmpty()) Log.i(TAG, "boot re-apply Gemini assistant: $err (owner mở app sẽ thử lại có chờ cấp quyền)")
                }
            }.onFailure { Log.e(TAG, "headless boot setup failed", it) }
            // BƯỚC CUỐI (v1.40): accessibility kẹt ⇒ khởi động lại tiến trình. Đặt ở đây để mọi bước trên đã chạy
            // xong (ghế · lọc bụi · audit quyền · cluster-lane · autostart VietMap · trợ lý Gemini) — lệnh
            // force-stop giết chính tiến trình này sau ~1 giây. App tự mở lại sau ~4 giây và lần đó AMS đã nhả
            // nên service bind được. Cổng cooldown/một-lần nằm trong A11yProcessRestart.
            //
            // HAI TÁC DỤNG PHỤ ĐÃ BIẾT (chấp nhận, [SUY] từ đọc code — chỉ test trên xe chốt được):
            //  • [VietMapAutostartService] là FGS RIÊNG nhưng CÙNG TIẾN TRÌNH, và vừa được start ở trên: worker
            //    của nó có thể đang `pollUntilInMap` (tới 25 s) khi lệnh giết rơi xuống ⇒ bị cắt giữa đường,
            //    VietMap có thể còn nằm foreground vì bước "trả về nền" chưa chạy. Tự lành sau khi mở lại:
            //    cooldown 30 s của autostart là biến TRONG BỘ NHỚ (`VietMapAutostart.lastRunAtMs`) nên chết theo
            //    tiến trình ⇒ `MainActivity.onCreate` sau relaunch gọi `startForAppOpen` và chạy được NGAY
            //    (không bị cooldown chặn); nếu VietMap đã dựng bóng thì nhánh `hasActivityRecord` bỏ relaunch
            //    (không flash), và chính việc mở lại ClusterNav đưa app mình lên trước nên VietMap không đứng
            //    trên màn hình.
            //  • Mở lại bằng activity launcher ⇒ boot "headless" lần này KẾT THÚC bằng MainActivity hiện trên màn
            //    chính. CỐ Ý: `am force-stop` đặt gói vào trạng thái stopped, và mở activity launcher là thao tác
            //    xoá trạng thái đó (không thì lần nổ máy sau KHÔNG có BOOT_COMPLETED); ngoài ra activity ở
            //    foreground giữ tiến trình sống chắc chắn trong lúc AMS bind lại service — thứ quan trọng hơn vẻ
            //    ngoài, vì chính "tiến trình chết giữa lúc đang bind" là nguyên nhân sinh ra trạng thái kẹt này.
            if (needA11yProcessRestart.get()) {
                Log.w(TAG, "boot: accessibility kẹt ở 'Binding services' → khởi động lại tiến trình để nối lại phím-thoại")
                runCatching {
                    com.byd.clusternav.modules.navaccess.A11yProcessRestart.restart(applicationContext, reason = "boot")
                }.onFailure { Log.e(TAG, "boot: khởi động lại tiến trình lỗi", it) }
            }
            finish(startId)
        }, "boot-setup").start()
        return START_NOT_STICKY
    }

    /** ALWAYS the last step: leave the foreground state + stop the service. Safe to call once per start. */
    private fun finish(startId: Int) {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.w(TAG, "stopForeground failed", it) }
        stopSelf(startId)
    }

    private fun startForegroundOnce(): Boolean = runCatching {
        startForeground(NOTIFICATION_ID, notification())
        true
    }.getOrElse {
        Log.e(TAG, "startForeground denied", it)
        false
    }

    private fun notification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ClusterNav khởi động", NotificationManager.IMPORTANCE_MIN),
            )
        }
        @Suppress("DEPRECATION")
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ClusterNav")
            .setContentText(Lang.t("Đang khởi động nền…", "Starting in background…"))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "BootSetup"
        // Distinct from the cast bubble service (1042) / CastAutomationService so the two can coexist on boot.
        private const val NOTIFICATION_ID = 1043
        private const val CHANNEL_ID = "clusternav_boot_setup"
        // Upper bound on how long the FGS lingers waiting for the async dadb accessibility grant to report
        // back (settle 1.2 s + tối đa HAI cửa sổ xác nhận 2.5 s + toggle 0.8 s + dumpsys/dadb round-trips).
        // Bounded so we ALWAYS stop. ⚠ v1.40: PHẢI lớn hơn trần của chính NavConnect (20 s) — nếu hết giờ trước
        // nó thì boot KHÔNG nhận được kết luận "AMS kẹt" và đường cứu (khởi động lại tiến trình) im lặng không
        // bao giờ chạy, đúng cái mà bản này sinh ra để chữa. Chỉ ca CHƯA bound mới đi tới gần trần này — ca
        // thường không gọi heal (gate `!NavAccessibilitySource.connected` ở trên).
        private const val GRANT_TIMEOUT_MS = 22_000L
    }
}
