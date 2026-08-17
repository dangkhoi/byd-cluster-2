package com.byd.clusternav.speedbadge

/**
 * PURE placement math for the cluster speed-limit badge overlay.
 *
 * Lives in :core (JVM-only, no Android) so the corner→gravity mapping and the size clamp are unit-testable
 * off-device. The Android `WindowManager.LayoutParams.gravity` field is just an `Int` bitmask, and
 * `android.view.Gravity`'s edge bits are frozen platform constants (they are persisted / serialized, so they
 * can never change): TOP=0x30, BOTTOM=0x50, LEFT=0x03, RIGHT=0x05. Mirroring those four values here lets
 * [gravityForCorner] return a value that [com.byd.clusternav.speedbadge.SpeedBadgeOverlay] can assign
 * straight to `lp.gravity` — with zero Android import in :core (enforced by LayeringRulesTest).
 */
object BadgeLayout {

    // ─── Corner ids (persisted in Prefs.badgeCorner — STABLE, do not renumber) ───────────────────
    const val CORNER_TOP_LEFT = 0
    const val CORNER_TOP_RIGHT = 1
    const val CORNER_BOTTOM_LEFT = 2
    const val CORNER_BOTTOM_RIGHT = 3

    /** Default corner = TOP-RIGHT (matches the original hard-coded gravity TOP|END and Prefs default). */
    const val CORNER_DEFAULT = CORNER_TOP_RIGHT

    // ─── android.view.Gravity edge bits (frozen platform values, mirrored so :core stays Android-free) ──
    const val GRAVITY_TOP = 0x30
    const val GRAVITY_BOTTOM = 0x50
    const val GRAVITY_LEFT = 0x03
    const val GRAVITY_RIGHT = 0x05

    // ─── Badge size bounds (dp) ──────────────────────────────────────────────────────────────────
    const val SIZE_MIN_DP = 60
    const val SIZE_MAX_DP = 240
    const val SIZE_DEFAULT_DP = 120

    /** True for the two bottom corners. Unknown ids fall through to top (the safe default edge). */
    fun isBottom(corner: Int): Boolean = corner == CORNER_BOTTOM_LEFT || corner == CORNER_BOTTOM_RIGHT

    /** True for the two left corners. Unknown ids fall through to right (the safe default edge). */
    fun isLeft(corner: Int): Boolean = corner == CORNER_TOP_LEFT || corner == CORNER_BOTTOM_LEFT

    /**
     * Map a corner id (0..3) to the combined Android gravity bitmask usable as `lp.gravity`.
     *
     * Any out-of-range id degrades to TOP-RIGHT (the default corner) rather than throwing, so a corrupt
     * stored pref can never crash the overlay — it just lands in the default corner.
     */
    fun gravityForCorner(corner: Int): Int {
        val vertical = if (isBottom(corner)) GRAVITY_BOTTOM else GRAVITY_TOP
        val horizontal = if (isLeft(corner)) GRAVITY_LEFT else GRAVITY_RIGHT
        return vertical or horizontal
    }

    /** Clamp a corner id into 0..3, falling back to the default corner for anything out of range. */
    fun clampCorner(corner: Int): Int =
        if (corner in CORNER_TOP_LEFT..CORNER_BOTTOM_RIGHT) corner else CORNER_DEFAULT

    /** Clamp a badge size (dp) into [SIZE_MIN_DP]..[SIZE_MAX_DP]. Applied on both read and write in Prefs. */
    fun clampSizeDp(sizeDp: Int): Int = sizeDp.coerceIn(SIZE_MIN_DP, SIZE_MAX_DP)
}
