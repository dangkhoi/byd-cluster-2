package com.byd.clusternav

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WIRING contract for the data-collection logging toggle (owner 2026-08-18): normal use must collect NO data.
 *
 * Locks the boundary across the whole change so the specific regression that shipped in the logging-off stage
 * — the visible "Thu thập dữ liệu chẩn đoán" switch was added to MainActivity.onCreate (bound NON-null) and to
 * the NARROW layout, but NOT to the wide (`layout-w960dp`) variant — can never recur. That gap NPE-crashed
 * MainActivity on any wide-display (≥ w960dp) head unit. Like [HeadlessAutostartContractTest], it reads the
 * source across the boundary: Prefs (default OFF) → NavLog (pref-only, no BuildConfig.DEBUG) → MainActivity
 * (null-safe bind + hidden long-press routes through the same switch) → BOTH layouts (the visible switch).
 */
class DiagLoggingToggleContractTest {

    // ── source helpers (mirror HeadlessAutostartContractTest) ────────────────
    private fun app(relative: String): Path {
        val current = Path.of(System.getProperty("user.dir"))
        return if (Files.exists(current.resolve("src"))) current.resolve(relative)
        else current.resolve("app").resolve(relative)
    }

    private fun read(path: Path): String = path.toFile().readText()

    private val prefs by lazy { read(app("src/main/java/com/byd/clusternav/Prefs.kt")) }
    private val navLog by lazy { read(app("src/main/java/com/byd/clusternav/NavLog.kt")) }
    private val mainActivity by lazy { read(app("src/main/java/com/byd/clusternav/MainActivity.kt")) }
    private val buildGradle by lazy { read(app("build.gradle.kts")) }
    private val layoutNarrow by lazy { read(app("src/main/res/layout/activity_main.xml")) }
    private val layoutWide by lazy { read(app("src/main/res/layout-w960dp/activity_main.xml")) }

    // ── Prefs: verbose flag now defaults to the DIAG_LOG build flag (release still OFF) ──────────
    @Test
    fun `prefs declares nav verbose log defaulting to the diag-build flag`() {
        assertTrue(prefs.contains("fun navVerboseLog(ctx: Context): Boolean"), "getter declared")
        assertTrue(
            prefs.contains("getBoolean(K_NAV_VERBOSE_LOG, BuildConfig.DIAG_LOG)"),
            "verbose default is the DIAG_LOG build flag — normal build = false (A8/D3 preserved), diag build = pre-ON",
        )
        assertTrue(prefs.contains("fun setNavVerboseLog(ctx: Context, v: Boolean)"), "setter declared")
    }

    // ── DIAG_LOG gates the Prefs default: RELEASE default stays OFF (privacy guard NOT weakened) ──
    @Test
    fun `diag flag defaults OFF so a normal build keeps verbose OFF unless -PdiagLog=true`() {
        // Prefs' verbose default is BuildConfig.DIAG_LOG (asserted above). DIAG_LOG is declared in
        // app/build.gradle.kts as the value of the gradle property `diagLog == "true"`, which is FALSE for a
        // normally-built release/debug APK. So the privacy default is UNCHANGED (A8/D3: normal use collects no
        // data); ONLY `-PdiagLog=true` flips DIAG_LOG true to pre-ON verbose for a teammate's drive-test.
        assertTrue(
            buildGradle.contains("buildConfigField(\"boolean\", \"DIAG_LOG\""),
            "DIAG_LOG buildConfigField is declared in app/build.gradle.kts",
        )
        assertTrue(
            buildGradle.contains("project.findProperty(\"diagLog\") == \"true\""),
            "DIAG_LOG = (diagLog gradle property == \"true\") → defaults FALSE, so RELEASE verbose stays OFF unless -PdiagLog=true",
        )
        assertTrue(
            buildGradle.contains("buildConfig = true"),
            "buildConfig generation is on so BuildConfig.DIAG_LOG exists at compile time",
        )
    }

    // ── NavLog: gate is pref-only, no BuildConfig.DEBUG auto-on ───────────────
    @Test
    fun `navlog init reads only the persisted pref and never auto-enables on debug`() {
        assertTrue(navLog.contains("verbose = Prefs.navVerboseLog(ctx)"), "init mirrors the persisted pref")
        // NOTE: the KDoc DOCUMENTS the removed '|| BuildConfig.DEBUG' expression, so we can't assert the token
        // is absent from the whole file. Instead assert the ASSIGNMENT never re-appends an OR — the only way the
        // auto-on could come back is 'verbose = Prefs.navVerboseLog(ctx) || ...'.
        assertFalse(
            navLog.contains("navVerboseLog(ctx) ||"),
            "the auto-on OR was REMOVED — the verbose assignment must be the pref alone, never OR'd with anything",
        )
        assertTrue(navLog.contains("var verbose = false"), "the in-memory gate defaults OFF")
    }

    // ── MainActivity: null-safe bind + single source of truth ─────────────────
    @Test
    fun `main activity binds the diag-logging switch null-safely and through setDiagLogging`() {
        assertTrue(
            mainActivity.contains("findViewById<Switch>(R.id.switch_diag_logging)?.also"),
            "the switch bind MUST be null-safe (?.also) so a layout variant lacking the id can't crash onCreate",
        )
        assertTrue(mainActivity.contains("setDiagLogging(on)"), "the switch listener routes through setDiagLogging")
        assertTrue(
            mainActivity.contains("NavLog.verbose = on"),
            "setDiagLogging mirrors the live in-memory gate",
        )
    }

    // ── BOTH layouts carry the visible switch (the P0 regression guard) ───────
    @Test
    fun `both layouts carry the diag-logging switch`() {
        for ((name, xml) in listOf("narrow" to layoutNarrow, "wide" to layoutWide)) {
            assertTrue(
                xml.contains("@+id/switch_diag_logging"),
                "$name: diagnostic-logging switch present (MainActivity binds it in every layout variant)",
            )
        }
    }
}
