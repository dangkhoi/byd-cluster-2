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
 * ── Cơ chế (RE ground-truth + sửa on-car 2026-09-06) ────────────────────────────────────────────
 *  • BẬT  → `setAutoCleanAirState(1)` (xe tự theo dõi PM2.5 + lọc LIÊN TỤC, không popup) — ĐƯỜNG CHÍNH,
 *    proven on-car rc=0. RỒI best-effort `enablePurificationFunctionPrompt(0)` (tắt POPUP nhắc lọc). Nếu KHI
 *    ĐÓ mức đọc được ≥ [Pm25Filter.HEAVY] → thêm `setQuickCleanAirState(1)` (lọc-ngay). KHÔNG cần polling.
 *  • TẮT  → `setAutoCleanAirState(0)` (đường chính) + best-effort `enablePurificationFunctionPrompt(1)` (khôi phục popup).
 *  • ĐỌC  → `BYDAutoPM2p5Device.getPM2p5Level()[0]` (device 1008) cho hiển thị; off-car → [Pm25Filter.INVALID].
 *
 * ⚠ `enablePurificationFunctionPrompt` là **BEST-EFFORT**: on-car (2026-09-06) trả `rc=-2147482645` (sentinel
 *   TỪ CHỐI, KHÁC `NOT_PROVISIONED`) và KHÔNG xuất hiện trong app OEM `com.byd.airconditioning` (không có bằng
 *   chứng arg đúng) ⇒ trim này không hỗ trợ. Nó KHÔNG BAO GIỜ được chặn đường `setAutoCleanAirState` đang chạy
 *   — nên gọi SAU đường chính + log **DEBUG** (không phải lỗi). Lọc vẫn chạy; chỉ popup nhắc lọc có thể còn hiện.
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
            acInt(acDev, "setAutoCleanAirState", 1)               // ĐƯỜNG CHÍNH: bật lọc-liên-tục (proven on-car rc=0)
            acInt(acDev, "enablePurificationFunctionPrompt", 0, bestEffort = true)   // best-effort tắt popup (trim có thể không hỗ trợ)
            val level = readLevel(app)
            Log.i(TAG, "read level=$level (${Pm25Filter.levelLabelEn(level)})")
            if (Pm25Filter.isDirty(level)) acInt(acDev, "setQuickCleanAirState", 1)   // đang bẩn → lọc ngay
            Log.i(TAG, "bật lọc PM2.5 xong (autoClean=1, prompt=best-effort)")
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
            acInt(acDev, "setAutoCleanAirState", 0)               // ĐƯỜNG CHÍNH: tắt lọc-liên-tục
            acInt(acDev, "enablePurificationFunctionPrompt", 1, bestEffort = true)   // best-effort khôi phục popup mặc định
            Log.i(TAG, "tắt lọc PM2.5 xong (autoClean=0, prompt=best-effort)")
        }.onFailure { Log.w(TAG, "tắt lọc thất bại (degrade-safe, bỏ qua)", it) }
    }

    /**
     * Gọi 1 method int-arg trên AC device qua REFLECTION (`getMethod(name, int).invoke(dev, arg)`), bọc
     * `runCatching` RIÊNG: ROM thiếu method / HAL từ chối → log rồi bỏ qua, KHÔNG ném. Method GHI này KHÔNG có
     * trong SDK jar nên KHÔNG gọi biên dịch được — chỉ reflection.
     *
     * [bestEffort] = true cho lời gọi KHÔNG-thiết-yếu (vd `enablePurificationFunctionPrompt` tắt popup): trim
     * không hỗ trợ → rc sentinel từ chối (on-car 2026-09-06: `-2147482645`). Đó KHÔNG PHẢI lỗi và KHÔNG được
     * chặn đường lọc chính ([enableNow] gọi `setAutoCleanAirState` TRƯỚC) — nên log ở mức **DEBUG** (mặc định
     * logcat không hiện), tránh làm owner tưởng lọc hỏng. best-effort=false ⇒ log INFO như cũ (đường chính).
     */
    private fun acInt(acDev: Any, method: String, arg: Int, bestEffort: Boolean = false) {
        val rc = runCatching {
            acDev.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(acDev, arg)
        }.getOrElse { BydHal.root(it) }
        if (bestEffort) {
            Log.d(TAG, "$method($arg) rc=$rc (best-effort; trim không hỗ trợ ⇒ bỏ qua, KHÔNG ảnh hưởng lọc)")
        } else {
            Log.i(TAG, "$method($arg) rc=$rc")
        }
    }
}
