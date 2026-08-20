package com.byd.clusternav.navigation

import com.byd.clusternav.navigation.NavSourceMode

import java.util.concurrent.ConcurrentHashMap

/**
 * Trọng tài chọn nguồn khi mở >1 app dẫn đường. Máy trạng thái THUẦN (không Android Context) —
 * caller truyền mode (NavSourceMode.AUTO/PREFER_*). UI đọc snapshot [activeSource] thay vì chọc vào listener.
 *
 * AUTO: app nào dẫn TRƯỚC giữ khoá; app khác bị bỏ qua tới khi nguồn giữ DỪNG (release) hoặc IM > STALE.
 * PREFER_*: app/nhóm được ưu tiên CÒN dẫn (tươi) thì luôn lên; app kia chỉ lên khi nó ngừng.
 */
object SourceArbiter {
    const val STALE_MS = 6000L
    private val GMAPS_PKGS = setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")
    private val WAZE_PKGS = setOf("com.chisadin.wazemod", "com.waze")
    private val VIETMAP_PKGS = setOf("vn.vietmap.live")

    @Volatile var activeSource: String? = null; private set
    @Volatile private var activeSeen: Long = 0L
    private val lastSeenByPkg = ConcurrentHashMap<String, Long>()
    private val lastDataByPkg = ConcurrentHashMap<String, Long>()

    /**
     * Có nên nuôi cụm bằng khung [pkg] không.
     *
     * Hai tầng gate:
     *   1. NGUỒN (app): AUTO giữ-khoá-app-dẫn-trước / PREFER_* ưu tiên nhóm (hành vi cũ, không đổi).
     *   2. KÊNH (B3, R6): kênh [NavChannel.IMAGE] (screen-capture) bị CHẶN khi kênh [NavChannel.DATA] của
     *      CÙNG app còn tươi (≤ [STALE_MS]) — data-channel luôn thắng ảnh; ảnh chỉ lên khi data im.
     *
     * [channel] mặc định [NavChannel.DATA] để caller cũ (HLP/1, widget, a11y) giữ nguyên hành vi + tự ghi
     * mốc data. Nguồn ảnh gọi với [NavChannel.IMAGE].
     */
    fun shouldFeed(pkg: String, mode: Int, now: Long, channel: NavChannel = NavChannel.DATA): Boolean {
        lastSeenByPkg[pkg] = now
        if (channel == NavChannel.DATA) lastDataByPkg[pkg] = now
        val allow = when (mode) {
            NavSourceMode.PREFER_GMAPS -> pkg in GMAPS_PKGS || !isGroupFresh(GMAPS_PKGS, now)
            NavSourceMode.PREFER_WAZE -> pkg in WAZE_PKGS || !isGroupFresh(WAZE_PKGS, now)
            NavSourceMode.PREFER_VIETMAP -> pkg in VIETMAP_PKGS || !isGroupFresh(VIETMAP_PKGS, now)
            else -> {
                val h = activeSource
                h == null || h == pkg || now - activeSeen > STALE_MS
            }
        }
        if (!allow) return false
        // Tầng kênh: ảnh là FALLBACK — data tươi của cùng app thì bỏ frame ảnh (KHÔNG chiếm khoá nguồn).
        if (channel == NavChannel.IMAGE && isDataFresh(pkg, now)) return false
        activeSource = pkg; activeSeen = now
        return true
    }

    /** Kênh DATA của [pkg] còn tươi không (≤ [STALE_MS]) — UI/nguồn ảnh hỏi để biết data có đang thắng. */
    fun isDataFresh(pkg: String, now: Long): Boolean {
        val t = lastDataByPkg[pkg] ?: return false
        return t > 0 && now - t <= STALE_MS
    }

    /** Gọi khi noti dẫn đường của [pkg] bị gỡ. true nếu [pkg] đang giữ khoá (caller nên dừng cụm). */
    fun release(pkg: String): Boolean {
        if (pkg == activeSource) { activeSource = null; activeSeen = 0L; return true }
        return false
    }

    /** Nhả khoá hoàn toàn (vd nav stale -> idle). */
    fun clear() { activeSource = null; activeSeen = 0L; lastDataByPkg.clear() }

    /** Nguồn đang giữ còn tươi không (UI hiện trạng thái). */
    fun isFresh(now: Long): Boolean {
        activeSource ?: return false
        return now - activeSeen <= STALE_MS
    }

    private fun isGroupFresh(group: Set<String>, now: Long): Boolean =
        group.any { val t = lastSeenByPkg[it] ?: 0L; t > 0 && now - t <= STALE_MS }
}
