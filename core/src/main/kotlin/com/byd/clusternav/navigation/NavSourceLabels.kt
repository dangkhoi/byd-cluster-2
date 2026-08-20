package com.byd.clusternav.navigation

/**
 * PURE labels for the nav-source MENU (T3, spec `b3-full-nav-capture` R2). Lives in :core (JVM only, no Android)
 * next to [NavSourceMode] and [SourceArbiter], so it is unit-testable off-device (`LayeringRulesTest` keeps pure
 * logic out of :app). Two mappings kept in ONE place so the menu (mode → label) and the "active source" status
 * line ([SourceArbiter.activeSource] package → label) never drift apart.
 *
 * The package groups MUST stay in sync with [SourceArbiter] (its GMAPS/WAZE/VIETMAP package sets) — those are
 * the exact strings the arbiter publishes as `activeSource`. They are duplicated here (the arbiter's sets are
 * private) and covered by `NavSourceLabelsTest` so a divergence is caught off-car.
 */
object NavSourceLabels {

    // Mirror of SourceArbiter's private package groups (keep in sync — verified by NavSourceLabelsTest).
    private val GMAPS_PKGS = setOf("com.google.android.apps.maps", "app.revanced.android.apps.maps")
    private val WAZE_PKGS = setOf("com.chisadin.wazemod", "com.waze")
    private val VIETMAP_PKGS = setOf("vn.vietmap.live")

    /** Brand label for a nav-source MODE ([NavSourceMode.AUTO]/`PREFER_*`). Unknown ⇒ Auto. */
    fun modeLabel(mode: Int): String = when (mode) {
        NavSourceMode.PREFER_GMAPS -> "Google Maps"
        NavSourceMode.PREFER_WAZE -> "Waze"
        NavSourceMode.PREFER_VIETMAP -> "VietMap"
        else -> "Auto"
    }

    /**
     * Brand label for the currently active source package ([SourceArbiter.activeSource]). Null ⇒ "" (caller
     * renders a "none" placeholder). An unrecognised package is returned verbatim so the status line is never
     * blank for a real, if unknown, navigator.
     */
    fun sourceLabel(activePkg: String?): String = when (activePkg) {
        null -> ""
        in GMAPS_PKGS -> "Google Maps"
        in WAZE_PKGS -> "Waze"
        in VIETMAP_PKGS -> "VietMap"
        else -> activePkg
    }
}
