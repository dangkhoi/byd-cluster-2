package com.byd.clusternav.comfort

import android.content.Context
import android.util.Log
import com.byd.clusternav.Prefs
import com.byd.clusternav.modules.hal.BydHal

/**
 * BẬT/TẮT LỌC BỤI MỊN (PM2.5) TỰ ĐỘNG trên HAL — "tự bật lọc, không hiện popup gì hết" (owner). Đi ĐƯỜNG
 * REFLECTION SẴN CÓ của ClusterNav ([BydHal.device]) như [SeatComfortApplier], nhưng các method GHI nằm trên
 * `BYDAutoAcDevice` và **CHỈ có trên ROM xe, KHÔNG có trong SDK jar** ⇒ gọi bằng `getMethod(...).invoke(...)`,
 * MỖI lời gọi bọc `runCatching` riêng (ROM thiếu method phải no-op, KHÔNG crash).
 *
 * ── Cơ chế (RE ground-truth) ─────────────────────────────────────────────────────────────────────
 *  • BẬT  → `enablePurificationFunctionPrompt(0)` (tắt POPUP nhắc lọc) + `setAutoCleanAirState(1)` (xe tự
 *    theo dõi PM2.5 + lọc LIÊN TỤC, không popup). Nếu KHI ĐÓ mức đọc được ≥ [Pm25Filter.HEAVY] → thêm
 *    `setQuickCleanAirState(1)` (lọc-ngay). KHÔNG cần service polling — xe tự giám sát qua autoClean.
 *  • TẮT  → `setAutoCleanAirState(0)` + `enablePurificationFunctionPrompt(1)` (khôi phục popup mặc định).
 *  • ĐỌC  → `BYDAutoPM2p5Device.getPM2p5Level()[0]` (device 1008) cho hiển thị; off-car → [Pm25Filter.INVALID].
 *
 * ── Vòng đời ─────────────────────────────────────────────────────────────────────────────────────
 *  • [applyOnStart] — gọi lúc mở app (MainActivity.onCreate) và boot nền (BootSetupService). Công tắc TẮT ⇒
 *    no-op. BẬT ⇒ chạy NỀN, ngủ ~5 s (khớp SeatComfortApplier: chờ cabin/HAL sẵn sàng) rồi bật lọc.
 *  • [enable]/[disable] — cho công tắc UI (không delay, chạy nền).
 *
 * ── An toàn (degrade-safe) ───────────────────────────────────────────────────────────────────────
 * Toàn bộ bọc `runCatching`; off-car / không có HAL → `device()` trả null → log rồi return, KHÔNG ném. Bộ test
 * đầy đủ chạy off-car nên đường này PHẢI không ném. Mỗi thao tác có [Log] tag "Pm25Filter" (method/arg/rc) +
 * mức đọc được, để owner xác minh trên xe bằng logcat.
 */
object Pm25FilterApplier {

    const val TAG = "Pm25Filter"

    /** Trễ ~5 s sau khi start (khớp [SeatComfortApplier.START_DELAY_MS]) để cabin/HAL sẵn sàng trước khi ghi. */
    const val START_DELAY_MS = 5_000L

    /** Gọi lúc mở app / boot nền. Công tắc TẮT ⇒ no-op. BẬT ⇒ bật lọc sau ~5 s trên thread nền. */
    fun applyOnStart(ctx: Context) {
        if (!Prefs.pm25FilterEnabled(ctx)) return
        val app = ctx.applicationContext
        Thread({
            runCatching { Thread.sleep(START_DELAY_MS) }
            enableNow(app)
        }, "pm25-filter-start").start()
    }

    /** Công tắc BẬT: bật lọc NGAY (nền, không delay). Công tắc phải đã BẬT trong Prefs (guard trong [enableNow]). */
    fun enable(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ enableNow(app) }, "pm25-filter-enable").start()
    }

    /** Công tắc TẮT: tắt lọc + khôi phục popup NGAY (nền). KHÔNG gate theo Prefs (pref vừa bị đặt về false). */
    fun disable(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ disableNow(app) }, "pm25-filter-disable").start()
    }

    /**
     * Đọc mức PM2.5 hiện tại = `getPM2p5Level()[0]` (device 1008) qua reflection. Off-car / không có HAL /
     * mảng rỗng → [Pm25Filter.INVALID] (0). KHÔNG ném. GỌI TỪ THREAD NỀN (reflection + HAL, không dùng ở main).
     */
    fun readLevel(ctx: Context): Int = runCatching {
        val app = ctx.applicationContext
        val dev = BydHal.device(BydHal.PM2P5, BydHal.systemBypassContext(), BydHal.bypass(app))
            ?: return@runCatching Pm25Filter.INVALID
        val arr = dev.javaClass.getMethod("getPM2p5Level").invoke(dev) as? IntArray
        arr?.getOrNull(0) ?: Pm25Filter.INVALID
    }.getOrDefault(Pm25Filter.INVALID)

    /** Bật lọc thật lên HAL (nền của [enable]/[applyOnStart]). Toàn bộ degrade-safe. */
    private fun enableNow(app: Context) {
        runCatching {
            if (!Prefs.pm25FilterEnabled(app)) return   // owner tắt trong lúc chờ delay
            val acDev = BydHal.device(BydHal.AC, BydHal.systemBypassContext(), BydHal.bypass(app))
            if (acDev == null) {
                Log.i(TAG, "AcDevice null (off-car / no HAL) — bỏ bật lọc")
                return@runCatching
            }
            acInt(acDev, "enablePurificationFunctionPrompt", 0)   // tắt popup nhắc lọc
            acInt(acDev, "setAutoCleanAirState", 1)               // bật lọc-liên-tục (xe tự theo dõi)
            val level = readLevel(app)
            Log.i(TAG, "read level=$level (${Pm25Filter.levelLabelEn(level)})")
            if (Pm25Filter.isDirty(level)) acInt(acDev, "setQuickCleanAirState", 1)   // đang bẩn → lọc ngay
            Log.i(TAG, "bật lọc PM2.5 xong (autoClean=1, prompt=0)")
        }.onFailure { Log.w(TAG, "bật lọc thất bại (degrade-safe, bỏ qua)", it) }
    }

    /** Tắt lọc + khôi phục popup (nền của [disable]). Toàn bộ degrade-safe. */
    private fun disableNow(app: Context) {
        runCatching {
            val acDev = BydHal.device(BydHal.AC, BydHal.systemBypassContext(), BydHal.bypass(app))
            if (acDev == null) {
                Log.i(TAG, "AcDevice null (off-car / no HAL) — bỏ tắt lọc")
                return@runCatching
            }
            acInt(acDev, "setAutoCleanAirState", 0)               // tắt lọc-liên-tục
            acInt(acDev, "enablePurificationFunctionPrompt", 1)   // khôi phục popup mặc định
            Log.i(TAG, "tắt lọc PM2.5 xong (autoClean=0, prompt=1)")
        }.onFailure { Log.w(TAG, "tắt lọc thất bại (degrade-safe, bỏ qua)", it) }
    }

    /**
     * Gọi 1 method int-arg trên AC device qua REFLECTION (`getMethod(name, int).invoke(dev, arg)`), bọc
     * `runCatching` RIÊNG: ROM thiếu method / HAL từ chối → log lỗi rồi bỏ qua, KHÔNG ném. Method GHI này
     * KHÔNG có trong SDK jar nên KHÔNG gọi biên dịch được — chỉ reflection.
     */
    private fun acInt(acDev: Any, method: String, arg: Int) {
        val rc = runCatching {
            acDev.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(acDev, arg)
        }.getOrElse { BydHal.root(it) }
        Log.i(TAG, "$method($arg) rc=$rc")
    }
}
