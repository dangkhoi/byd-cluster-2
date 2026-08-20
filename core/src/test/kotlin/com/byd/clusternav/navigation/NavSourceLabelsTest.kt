package com.byd.clusternav.navigation

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pure labels for the nav-source menu (T3, spec b3-full-nav-capture R2), plus a boundary check that the
 * "active source" labels cover exactly the packages [SourceArbiter] publishes as [SourceArbiter.activeSource].
 */
class NavSourceLabelsTest {

    @BeforeEach fun setup() = SourceArbiter.clear()
    @AfterEach fun tearDown() = SourceArbiter.clear()

    @Test fun `modeLabel maps every menu option`() {
        assertEquals("Auto", NavSourceLabels.modeLabel(NavSourceMode.AUTO))
        assertEquals("Google Maps", NavSourceLabels.modeLabel(NavSourceMode.PREFER_GMAPS))
        assertEquals("Waze", NavSourceLabels.modeLabel(NavSourceMode.PREFER_WAZE))
        assertEquals("VietMap", NavSourceLabels.modeLabel(NavSourceMode.PREFER_VIETMAP))
        assertEquals("Auto", NavSourceLabels.modeLabel(999))   // unknown → Auto
    }

    @Test fun `sourceLabel brands known packages and passes through unknown`() {
        assertEquals("", NavSourceLabels.sourceLabel(null))
        assertEquals("Google Maps", NavSourceLabels.sourceLabel("com.google.android.apps.maps"))
        assertEquals("Google Maps", NavSourceLabels.sourceLabel("app.revanced.android.apps.maps"))
        assertEquals("Waze", NavSourceLabels.sourceLabel("com.chisadin.wazemod"))
        assertEquals("Waze", NavSourceLabels.sourceLabel("com.waze"))
        assertEquals("VietMap", NavSourceLabels.sourceLabel("vn.vietmap.live"))
        assertEquals("com.some.other.nav", NavSourceLabels.sourceLabel("com.some.other.nav"))
    }

    /**
     * BOUNDARY (W5.3): the exact package string the arbiter records as activeSource must map to a branded
     * label (not fall through to the raw package). Feeds the arbiter each known nav package and asserts the
     * label of the resulting activeSource is branded.
     */
    @Test fun `active-source labels cover the packages SourceArbiter publishes`() {
        val cases = mapOf(
            "com.google.android.apps.maps" to "Google Maps",
            "com.chisadin.wazemod" to "Waze",
            "vn.vietmap.live" to "VietMap",
        )
        for ((pkg, expected) in cases) {
            SourceArbiter.clear()
            SourceArbiter.shouldFeed(pkg, NavSourceMode.AUTO, 1_000L)
            assertEquals(pkg, SourceArbiter.activeSource, "arbiter should hold $pkg")
            assertEquals(expected, NavSourceLabels.sourceLabel(SourceArbiter.activeSource), "label for $pkg")
        }
    }
}
