package com.byd.clusternav.comfort

import android.content.Context
import android.util.Log
import com.byd.clusternav.Prefs
import com.byd.clusternav.modules.hal.BydHal

/**
 * Áp mức làm-mát/sưởi ghế lên HAL (device 1023) — bản sao hành vi app tham chiếu `com.byd.mecanum.dashboard`,
 * nhưng đi ĐƯỜNG REFLECTION SẴN CÓ của ClusterNav ([BydHal.device] + [BydHal.setInt]) thay vì binder proxy.
 *
 * ── Vòng đời ─────────────────────────────────────────────────────────────────────────────────────
 *  • [applyOnStart] — gọi lúc mở app (MainActivity.onCreate) và lúc boot nền (BootSetupService). Nếu công
 *    tắc ghế TẮT → no-op. Nếu BẬT → chạy NỀN, ngủ ~5 s (khớp `Handler.postDelayed 5000` của app tham chiếu:
 *    chờ HAL/cabin sẵn sàng sau khi khởi động) rồi ghi từng ghế có mức ≠ Tắt.
 *  • [applyNow] — cho nút "Áp dụng ngay" trong app (không delay).
 *
 * ── An toàn (degrade-safe) ───────────────────────────────────────────────────────────────────────
 * Toàn bộ bọc `runCatching`. Off-car / không có HAL → `device()` trả null → log rồi return, KHÔNG ném, KHÔNG
 * crash. Bộ test đầy đủ chạy off-car nên đường này PHẢI không ném. Mỗi lần ghi có [Log] tag "SeatComfort"
 * (seat/mode/featureId/value/rc) để owner xác minh trên xe bằng logcat.
 */
object SeatComfortApplier {

    const val TAG = "SeatComfort"

    /** Trễ ~5 s sau khi start (khớp app tham chiếu) để cabin/HAL sẵn sàng trước khi ghi. */
    const val START_DELAY_MS = 5_000L

    /** Gọi lúc mở app / boot nền. Công tắc TẮT ⇒ no-op. BẬT ⇒ ghi ghế sau ~5 s trên thread nền. */
    fun applyOnStart(ctx: Context) = launch(ctx, START_DELAY_MS, "seat-comfort-start")

    /** Nút "Áp dụng ngay" — ghi ngay (không delay). Công tắc TẮT ⇒ no-op. */
    fun applyNow(ctx: Context) = launch(ctx, 0L, "seat-comfort-now")

    private fun launch(ctx: Context, delayMs: Long, threadName: String) {
        if (!Prefs.seatComfortEnabled(ctx)) return
        val app = ctx.applicationContext
        Thread({
            if (delayMs > 0) runCatching { Thread.sleep(delayMs) }
            apply(app)
        }, threadName).start()
    }

    /** Ghi thật lên HAL. Chỉ gọi từ thread nền của [launch]. Toàn bộ degrade-safe. */
    private fun apply(app: Context) {
        runCatching {
            if (!Prefs.seatComfortEnabled(app)) return   // owner tắt trong lúc chờ delay
            val mode = if (Prefs.seatComfortMode(app) == SeatComfort.SeatMode.HEAT.ordinal) {
                SeatComfort.SeatMode.HEAT
            } else {
                SeatComfort.SeatMode.COOL
            }
            val seats = SeatComfort.seatsForModel(isHanModel(app))
            val acDev = BydHal.device(BydHal.AC, BydHal.systemBypassContext(), BydHal.bypass(app))
            if (acDev == null) {
                Log.i(TAG, "AcDevice null (off-car / no HAL) — bỏ áp ghế, mode=$mode seats=$seats")
                return@runCatching
            }
            var applied = 0
            for (seat in seats) {
                val level = Prefs.seatComfortLevel(app, seat)
                if (level == SeatComfort.LEVEL_OFF) continue
                val fid = SeatComfort.featureId(seat, mode)
                val value = SeatComfort.halValue(level)
                val rc = runCatching { BydHal.setInt(acDev, fid, value) }.getOrElse { BydHal.root(it) }
                Log.i(TAG, "ghi ghế=$seat mode=$mode featureId=0x${Integer.toHexString(fid)} value=$value rc=$rc")
                applied++
            }
            Log.i(TAG, "áp xong: $applied ghế, mode=$mode, seats=$seats")
        }.onFailure { Log.w(TAG, "áp ghế thất bại (degrade-safe, bỏ qua)", it) }
    }

    /** Số ghế theo mẫu xe (2 = Seal / 4 = Han). Off-car / không rõ → 2. Cho UI hiện đúng 2 hay 4 ghế. */
    fun detectSeatCount(ctx: Context): Int = SeatComfort.seatCountForModel(isHanModel(ctx))

    /**
     * Best-effort: xe có phải Han (4 ghế tiện-nghi) không? Thử theo thứ tự, gộp mọi manh mối rồi so [SeatComfort.isHanModel]:
     *  1) BYD HAL statistic device — vài getter mẫu-xe (nếu ROM có);
     *  2) `android.os.SystemProperties.get` vài khoá mẫu/sản-phẩm;
     *  3) `android.os.Build.MODEL`.
     * KHÔNG chặn, KHÔNG ném (mọi bước bọc runCatching). Không manh mối nào chứa "han" ⇒ false ⇒ mặc định
     * Seal (2 ghế) — off-car luôn trả 2.
     */
    fun isHanModel(ctx: Context): Boolean {
        val clues = mutableListOf<String>()
        // 1) BYD HAL statistic/vehicle-type getters (probe tên khả dĩ; ROM không có → bỏ).
        runCatching {
            val dev = BydHal.device(BydHal.STATISTIC, BydHal.systemBypassContext(), BydHal.bypass(ctx.applicationContext))
            if (dev != null) {
                for (g in listOf("getVehicleType", "getCarType", "getVehicleModel", "getCarModel", "getModel")) {
                    BydHal.callGetter(dev, g)?.let { clues.add(it) }
                }
            }
        }
        // 2) System properties (khoá mẫu/sản-phẩm khả dĩ trên IVI BYD).
        for (k in listOf(
            "ro.product.model", "ro.product.name", "ro.product.device",
            "persist.sys.vehicle.model", "ro.byd.vehicle.type", "ro.byd.product.model",
        )) {
            systemProp(k)?.let { clues.add(it) }
        }
        // 3) Build.MODEL.
        runCatching { android.os.Build.MODEL?.let { clues.add(it) } }
        return clues.any { SeatComfort.isHanModel(it) }
    }

    private fun systemProp(key: String): String? = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
