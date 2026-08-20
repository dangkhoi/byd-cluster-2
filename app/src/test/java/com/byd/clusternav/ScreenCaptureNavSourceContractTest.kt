package com.byd.clusternav

import com.byd.clusternav.testsupport.SourceRoots
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Contract lock for the B3 :app screen-capture layer — the parts that touch Android (Bitmap/executor/shell/
 * MediaProjection) and therefore cannot run on the stubbed JVM android.jar. Asserts the safety/gate/lifecycle
 * invariants by scanning source, the same technique as [SpeedSignSourceLifecycleTest].
 */
class ScreenCaptureNavSourceContractTest {

    private val source = SourceRoots.text("src/main/java/com/byd/clusternav/screencapture/ScreenCaptureNavSource.kt")
    private val transport = SourceRoots.text("src/main/java/com/byd/clusternav/screencapture/ScreenCaptureTransport.kt")
    private val offscreen = SourceRoots.text("src/main/java/com/byd/clusternav/screencapture/OffscreenMirrorCapturer.kt")
    private val listener = SourceRoots.text("src/main/java/com/byd/clusternav/NavNotificationListener.kt")
    private val access = SourceRoots.text("src/main/java/com/byd/clusternav/modules/navaccess/NavAccessibilityService.kt")
    private val manifest = SourceRoots.text("src/main/AndroidManifest.xml")

    @Test
    fun `gate requires master ON plus a fresh data source or foreground app (R5)`() {
        assertTrue(source.contains("if (!Prefs.enabled(ctx)) return"), "R5(a): master Nav+HUD gate")
        assertTrue(source.contains("SourceArbiter.isFresh(nowWall)"), "R5(b): data-fresh gate (WALL clock — shared arbiter)")
        assertTrue(
            source.contains("CaptureForegroundSource.isFresh(now)") ||
                source.contains("NavAccessibilitySource.foreground(now)"),
            "R5(b): foreground gate",
        )
        // navFresh flows into the router which returns an empty plan list when the gate is closed.
        assertTrue(source.contains("CaptureRouter.routePlans("), "router drives capture decision (one plan per target)")
    }

    @Test
    fun `single-in-flight + off dedicated single thread + not-fast (R-nf2 R-nf3)`() {
        assertTrue(source.contains("isProcessing.compareAndSet(false, true)"), "single-in-flight guard")
        assertTrue(source.contains("scheduleWithFixedDelay"), "fixed-delay serialization (no overlap)")
        assertTrue(source.contains("newSingleThreadScheduledExecutor"), "dedicated single-thread executor")
        assertTrue(source.contains("isDaemon = true"), "daemon thread (never blocks shutdown)")
        // ≤ 2–4 Hz: tick period must be at least 250 ms.
        val tick = Regex("""TICK_MS\s*=\s*(\d+)L""").find(source)?.groupValues?.get(1)?.toLong()
        assertTrue(tick != null && tick >= 250L, "tick must be ≤4 Hz (period ≥250 ms), was $tick")
    }

    @Test
    fun `every capture-classify path is runCatching-guarded (R-nf1 degrade-safe)`() {
        assertTrue(source.contains("runCatching { tick() }"), "tick wrapped so executor never dies")
        assertTrue(transport.contains("runCatching"), "transport degrade-safe")
        assertTrue(offscreen.contains("runCatching"), "offscreen scaffold degrade-safe")
    }

    @Test
    fun `feeds SourceArbiter on the IMAGE channel so data beats image (R6)`() {
        assertTrue(source.contains("NavChannel.IMAGE"), "image channel")
        assertTrue(source.contains("SourceArbiter.shouldFeed("), "goes through the arbiter")
        // Publish only AFTER the arbiter allows (data > image): shouldFeed guard precedes publish for both targets.
        val arrowGuard = source.indexOf("SourceArbiter.shouldFeed(pkg, Prefs.sourceMode(appContext), nowWall, NavChannel.IMAGE)) return")
        assertTrue(arrowGuard >= 0, "arbiter guard present before publish")
    }

    @Test
    fun `routes and classifies EACH requested target in one tick (B3-8 multi-target, degrade-safe per target)`() {
        // VietMap needs BOTH the top-left arrow banner AND the map camera icon → routePlans returns one plan per
        // target; the source captures the display ONCE then iterates and crops/classifies each, guarded per target
        // so one target failing (or having no bounds) never drops the other.
        assertTrue(source.contains("CaptureRouter.routePlans("), "multi-target routing (one plan per target)")
        assertTrue(source.contains("for (plan in plans)"), "iterates every requested target in one tick")
        assertTrue(source.contains("CaptureTarget.ARROW -> handleArrow"), "arrow target handled")
        assertTrue(source.contains("CaptureTarget.CAMERA -> handleCamera"), "camera target handled")
        // per-target runCatching so a failure in one target does not abort the other.
        val loopIdx = source.indexOf("for (plan in plans)")
        assertTrue(loopIdx >= 0, "multi-target loop present")
        val loopBody = source.substring(loopIdx, (loopIdx + 500).coerceAtMost(source.length))
        assertTrue(loopBody.contains("runCatching {"), "each target guarded by runCatching (degrade-safe)")
    }

    @Test
    fun `transport maps each case to the proven fission display or the offscreen scaffold`() {
        assertTrue(source.contains("FISSION_MAIN"), "case 1/2 → fission main")
        assertTrue(source.contains("FISSION_CLUSTER"), "case 3 → fission cluster")
        assertTrue(source.contains("offscreen.capture()"), "case 4 → offscreen MediaProjection scaffold")
        // fission id mapping is the PROVEN one (opposite Android): -d1 MAIN, -d0 CLUSTER.
        assertTrue(transport.contains("FISSION_MAIN = 1"), "fission -d1 = MAIN")
        assertTrue(transport.contains("FISSION_CLUSTER = 0"), "fission -d0 = CLUSTER")
        assertTrue(transport.contains("fission_screencap -d"), "reuses proven fission_screencap path")
    }

    @Test
    fun `diagnostic image save is verbose-gated and honours the storage cap (R-nf4)`() {
        assertTrue(source.contains("if (NavLog.verbose) saveDiag"), "diag save gated by verbose (default OFF)")
        assertTrue(source.contains("DiagStorageCap.enforce"), "respects the A8 storage cap")
    }

    @Test
    fun `lifecycle starts on listener connect and stops on disconnect and destroy`() {
        assertTrue(source.contains("fun start()") && source.contains("fun stop()"), "start/stop lifecycle")
        // connect (enabled) starts; disconnect + destroy stop.
        assertTrue(listener.contains("ScreenCaptureNavSource.get(applicationContext).start()"), "start wired on connect")
        val disc = functionBody(listener, "override fun onListenerDisconnected()")
        val dest = functionBody(listener, "override fun onDestroy()")
        assertTrue(disc.contains("ScreenCaptureNavSource.get(applicationContext).stop()"), "stop on disconnect")
        assertTrue(dest.contains("ScreenCaptureNavSource.get(applicationContext).stop()"), "stop on destroy")
    }

    @Test
    fun `a11y publishes foreground + best-effort capture bounds for all nav packages`() {
        assertTrue(access.contains("CaptureForegroundSource.publish(pkg, b3Now)"), "publishes foreground pkg")
        assertTrue(access.contains("maybePublishCaptureBounds(event, pkg, b3Now)"), "publishes capture bounds")
        assertTrue(access.contains("CaptureBoundsSource.publish("), "bounds reach the router tier-1 holder")
    }

    @Test
    fun `no exported MediaProjection consent surface (R-nf6)`() {
        // The consent activity MUST be exported=false in the manifest.
        val idx = manifest.indexOf(".screencapture.CaptureRequestActivity")
        assertTrue(idx >= 0, "CaptureRequestActivity registered")
        val window = manifest.substring((idx - 200).coerceAtLeast(0), (idx + 200).coerceAtMost(manifest.length))
        assertTrue(window.contains("android:exported=\"false\""), "consent activity must be non-exported")
        // And no new exported test/receiver surface was added by this slice.
        assertFalse(source.contains("exported = true"), "no exported surface in the capture source")
    }

    private fun functionBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        require(start >= 0) { "missing $signature" }
        var depth = 0
        var opened = false
        for (i in start until text.length) {
            when (text[i]) {
                '{' -> { depth++; opened = true }
                '}' -> if (opened && --depth == 0) return text.substring(start, i + 1)
            }
        }
        error("unterminated $signature")
    }
}
