package com.byd.clusternav

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service RIÊNG cho auto-start VietMap (bóng-trên-cụm / badge tốc độ).
 *
 * VÌ SAO TÁCH RIÊNG (owner 2026-09-07): trước đây boot headless gọi [VietMapAutostart.runNow] **ĐỒNG BỘ**
 * ngay trong chuỗi setup của [BootSetupService] (grant accessibility → cluster-lane → **VietMap** → ghế →
 * lọc bụi → Gemini). Từ B3, nhánh bóng POLL tới khi VietMap thật sự vào map (tuỳ network, có thể vài giây →
 * hàng chục giây) — nếu vẫn nằm trong chuỗi đó thì nó CHẶN các tính năng chung (ghế/lọc bụi/Gemini) và giữ
 * FGS boot sống rất lâu. Owner: *"tách riêng cái vietmap ra, không liên quan đến app chung của mình đâu"*.
 *
 * Service này tự quản vòng đời độc lập: [startForeground] NGAY (trong ngân sách ~5 s của
 * `startForegroundService`), chạy [VietMapAutostart.runNow] trên thread nền, rồi [finish] (stopForeground +
 * stopSelf). Tiến trình được FGS giữ sống tới khi poll xong — thứ mà một thread rời không đảm bảo.
 *
 * CHỐNG LOOP giữ NGUYÊN ở tầng [VietMapAutostart.runNow] (in-flight CAS + cooldown 30 s): dù service bị start
 * nhiều lần (boot + mở app + bật toggle trong 30 s) thì chỉ MỘT lần `runNow` đi qua gate, các lần còn lại
 * return ngay và service tự dừng. NGOẠI LỆ duy nhất là start **force** ([startForPermissionFix]): nó nghĩa là
 * "VietMap vừa bị `am force-stop` sau khi vá quyền, phải mở lại NGAY" nên không được bỏ — đến lúc worker đang
 * chạy thì được HOÃN rồi chạy nối tiếp (xem `pendingForce`), chứ không rơi vào cooldown của lượt trước.
 *
 * Never exported. Start từ context đang foreground/FGS ([BootSetupService], [MainActivity]) nên hợp lệ mọi
 * phiên bản (không dính hạn background-FGS-start của Android 12+).
 */
class VietMapAutostartService : Service() {

    // MỘT worker tại một thời điểm. Boot + mở app + bật toggle có thể start service này gần nhau; nếu mỗi lần
    // đều spawn thread + stopSelf(startId) thì một start THỪA (startId mới hơn) sẽ stopSelf → xé FGS ngay giữa
    // lúc worker THẬT còn đang poll-vào-map ⇒ process mất trạng thái foreground, có thể bị kill giữa chừng. Vá:
    // chỉ worker đầu tiên chạy (CAS), start thừa chỉ cập nhật [latestStartId]; worker khi xong NHẢ CỜ trước rồi
    // finish() theo latestStartId (xem lý do chống-rò-FGS ở finally). runNow tự có cooldown/in-flight nên bỏ một
    // start THƯỜNG bị trùng là đúng (không mất gì — lần trigger sau / vòng refresh bóng phủ tiếp). Start FORCE thì
    // KHÔNG được bỏ — xem [pendingForce] ngay dưới.
    private val workerActive = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var latestStartId = 0

    // Một start **FORCE** (đường "vừa vá quyền bóng", [startForPermissionFix]) đến trong lúc worker đang chạy
    // KHÔNG được bỏ như start thường. Ca thật gây bug: mở app → [MainActivity.onCreate] start autostart NGAY
    // (worker #1 vào `pollUntilInMap`, tới 25 s) → vài giây sau bộ kiểm-tra-quyền vá xong appop của bản mod,
    // `am force-stop` VietMap rồi gọi force-start; nếu force-start bị bỏ vì worker #1 còn chạy thì VietMap nằm
    // chết, BÓNG KHÔNG LÊN, và cooldown 30 s đẩy việc dựng lại sang lần mở app SAU — đúng triệu chứng v1.38 mà
    // đợt này sửa. Nên nhớ nó lại và chạy tiếp NGAY khi worker hiện tại nhả cờ.
    private val pendingForce = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var pendingForceReturnToSelfPkg: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService() contract: lên foreground trong ~5 s hoặc bị kill. Làm TRƯỚC mọi việc nền.
        if (!startForegroundOnce()) { stopSelf(startId); return START_NOT_STICKY }
        latestStartId = startId
        // null (boot headless) = sau khi vào map thì VỀ HOME; có giá trị (mở app / toggle) = trả gói đó lên trước.
        val returnToSelfPkg = intent?.getStringExtra(EXTRA_RETURN_TO_SELF)
        val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
        if (workerActive.compareAndSet(false, true)) {
            startWorker(returnToSelfPkg, force)
        } else if (force) {
            // Xem ghi chú [pendingForce]: force = "VietMap vừa bị force-stop, PHẢI mở lại" ⇒ hoãn, không bỏ.
            pendingForceReturnToSelfPkg = returnToSelfPkg
            pendingForce.set(true)
            Log.i(TAG, "force-start đến lúc worker đang chạy (startId=$startId) — HOÃN, chạy ngay sau worker hiện tại")
        } else {
            Log.i(TAG, "VietMap autostart đang chạy — bỏ qua start trùng (startId=$startId); runNow có cooldown riêng")
        }
        return START_NOT_STICKY
    }

    /**
     * Chạy một lượt autostart trên thread nền. Gọi CHỈ khi đã giành được [workerActive].
     *
     * Worker nhả cờ TRƯỚC khi kết thúc — chống RÒ FGS. Nếu nhả SAU: một start rơi vào khe giữa finish() và
     * set(false) sẽ (a) re-promote FGS + cập nhật [latestStartId] nhưng (b) TRƯỢT CAS (workerActive còn true)
     * ⇒ KHÔNG có worker; mà stopSelf(cũ) bị start mới hơn vô hiệu ⇒ service + notification KẸT LẠI mãi. Nhả
     * TRƯỚC: start trùng trong khe này CAS-được ⇒ spawn worker mới sở hữu vòng đời riêng; runNow của nó chắc
     * chắn no-op (in-flight/cooldown 30 s vẫn giữ), finish() của worker cũ chỉ thành stopSelf thừa (idempotent).
     *
     * Có [pendingForce] ⇒ **nối tiếp** một worker nữa và KHÔNG finish() ở đây (finish() = stopForeground +
     * stopSelf, gọi lúc này sẽ xé FGS ngay giữa lượt force). Vòng nối hữu hạn: mỗi mắt cần MỘT force-start mới
     * từ bên ngoài, và `runNow` vẫn tự chặn trùng lặp.
     */
    private fun startWorker(returnToSelfPkg: String?, force: Boolean) {
        Thread({
            try {
                runCatching { VietMapAutostart.runNow(applicationContext, returnToSelfPkg, force) }
                    .onFailure { Log.e(TAG, "VietMap autostart service failed", it) }
            } finally {
                workerActive.set(false)
                // Nhận suất force đã hoãn. Hai CAS: (1) lấy cờ force, (2) giành lại suất worker. Nếu (2) TRƯỢT
                // (một start khác vừa giành được trong khe này) thì phải TRẢ cờ force về — worker kia sẽ chạy
                // đúng khối này lúc nó xong và nhận tiếp; nếu không trả, một force-start (nghĩa là "VietMap vừa
                // bị force-stop, phải mở lại") có thể bốc hơi và bóng nằm chết tới lần mở app sau.
                val tookForce = pendingForce.compareAndSet(true, false)
                val chained = tookForce && workerActive.compareAndSet(false, true)
                if (chained) {
                    Log.i(TAG, "chạy force-start đã hoãn (bóng VietMap vừa được vá quyền)")
                    startWorker(pendingForceReturnToSelfPkg, force = true)
                } else {
                    if (tookForce) {
                        pendingForce.set(true)
                        Log.i(TAG, "force-start hoãn: nhường lại cho worker vừa giành cờ (không mất suất)")
                    }
                    finish(latestStartId)     // dừng theo startId mới nhất (kể cả start đến giữa lúc chạy)
                }
            }
        }, "vietmap-autostart").start()
    }

    /** ALWAYS the last step: rời foreground + dừng service. */
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
                NotificationChannel(CHANNEL_ID, "ClusterNav VietMap", NotificationManager.IMPORTANCE_MIN),
            )
        }
        @Suppress("DEPRECATION")
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("ClusterNav")
            .setContentText(Lang.t("Đang mở VietMap…", "Starting VietMap…"))
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "VMAutostartSvc"
        // Distinct from FloatingBubbleService (1042) / BootSetupService (1043) so all can coexist.
        private const val NOTIFICATION_ID = 1044
        private const val CHANNEL_ID = "clusternav_vietmap_autostart"
        private const val EXTRA_RETURN_TO_SELF = "return_to_self_pkg"
        private const val EXTRA_FORCE = "force_bypass_cooldown"

        /** Boot headless: sau khi VietMap vào map thì VỀ HOME (returnToSelf = null). */
        fun startForBoot(ctx: Context) = start(ctx, null)

        /** Mở app / bật toggle bóng: sau khi VietMap vào map thì đưa ClusterNav lại TRƯỚC. */
        fun startForAppOpen(ctx: Context) = start(ctx, ctx.packageName)

        /**
         * **Vừa vá quyền overlay của VietMap** ([com.byd.clusternav.permissions.PermissionAuditRunner]): bản mod
         * chỉ dựng bóng LÚC KHỞI ĐỘNG, nên vá list/appop sau đó KHÔNG hồi tố — VietMap vừa bị `am force-stop`
         * và phải mở lại NGAY. Truyền `force` để bỏ cooldown 30 s (lượt autostart vài giây trước sẽ chặn mất),
         * single-flight vẫn giữ.
         *
         * @param returnToSelf true = mở app (trả ClusterNav lên trước) · false = boot headless (về HOME).
         */
        fun startForPermissionFix(ctx: Context, returnToSelf: Boolean) =
            start(ctx, if (returnToSelf) ctx.packageName else null, force = true)

        private fun start(ctx: Context, returnToSelfPkg: String?, force: Boolean = false) {
            val app = ctx.applicationContext
            val i = Intent(app, VietMapAutostartService::class.java)
            if (returnToSelfPkg != null) i.putExtra(EXTRA_RETURN_TO_SELF, returnToSelfPkg)
            if (force) i.putExtra(EXTRA_FORCE, true)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
            }.onFailure { Log.w(TAG, "start VietMapAutostartService failed: ${it.message}") }
        }
    }
}
