package com.byd.clusternav.vietmapwidget

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

private const val VIETMAP_PACKAGE = "vn.vietmap.live"
private const val MAX_HASH_EDGE = 256
private const val TAG = "WidgetExtract"

/**
 * Data extraction from RemoteViews host views. Separated from VietMapWidgetBridge
 * to keep binding lifecycle code clean and ensure drawable hashing runs off main thread.
 */
internal class VietMapWidgetExtraction(context: Context) {
    private val appContext = context.applicationContext
    private val hashExecutor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "widget-hash").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    @Volatile var remoteResources: Resources? = null
        private set

    fun reloadRemoteResources() {
        remoteResources = try {
            appContext.packageManager.getResourcesForApplication(VIETMAP_PACKAGE)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun releaseResources() {
        remoteResources = null
    }

    fun extractSpeed(root: AppWidgetHostView): VietMapWidgetRawValues? {
        val names = VietMapWidgetViewNames.speedRequired
        if (!VietMapWidgetTextParser.supportsSpeedShape(resolvedNames(names))) return null
        val current = text(root, VietMapWidgetViewNames.CURRENT_SPEED) ?: return null
        val limit = text(root, VietMapWidgetViewNames.SPEED_LIMIT) ?: return null
        return VietMapWidgetRawValues(
            currentSpeedText = current.text.toString().takeIf { effectivelyVisible(current, root) },
            speedLimitText = limit.text.toString().takeIf { effectivelyVisible(limit, root) },
        )
    }

    fun extractAlerts(root: AppWidgetHostView): VietMapWidgetRawValues? {
        // The sticky-alert widget's STABLE anchor = the two alert images (present even in the no-alert
        // placeholder state, e.g. `place_holder_textView`='--'). The per-alert TEXT views
        // (limit/distance) exist only WHILE an alert is active, so they are OPTIONAL — their absence
        // means "no active alert", NOT an unsupported shape. Requiring them (old behaviour) made the
        // idle placeholder report UNSUPPORTED_SHAPE, which dragged the whole VietMap snapshot to
        // UNAVAILABLE and masked a perfectly working speed slot (proven by on-car widget dump 2026-08-06).
        val firstImage = view(root, VietMapWidgetViewNames.FIRST_ALERT_IMAGE) as? ImageView ?: return null
        val secondImage = view(root, VietMapWidgetViewNames.SECOND_ALERT_IMAGE) as? ImageView ?: return null
        fun optText(name: String): String? {
            val tv = view(root, name) as? TextView ?: return null
            return tv.text.toString().takeIf { effectivelyVisible(tv, root) }
        }
        return VietMapWidgetRawValues(
            firstAlertSpeedLimitText = optText(VietMapWidgetViewNames.FIRST_ALERT_LIMIT),
            firstAlertDistanceText = optText(VietMapWidgetViewNames.FIRST_ALERT_DISTANCE),
            firstAlertImageVisible = effectivelyVisible(firstImage, root),
            firstAlertImageHash = null, // hash computed asynchronously
            secondAlertSpeedLimitText = optText(VietMapWidgetViewNames.SECOND_ALERT_LIMIT),
            secondAlertDistanceText = optText(VietMapWidgetViewNames.SECOND_ALERT_DISTANCE),
            secondAlertImageVisible = effectivelyVisible(secondImage, root),
            secondAlertImageHash = null, // hash computed asynchronously
        )
    }

    /**
     * Submit drawable hashing work to background thread. Returns a future pair of (firstHash, secondHash).
     * Caller must capture the bitmap data on main thread (drawable → pixel array) then hash off-thread.
     */
    fun hashAlertsAsync(root: AppWidgetHostView): Future<Pair<String?, String?>> {
        val firstImage = view(root, VietMapWidgetViewNames.FIRST_ALERT_IMAGE) as? ImageView
        val secondImage = view(root, VietMapWidgetViewNames.SECOND_ALERT_IMAGE) as? ImageView
        // Capture pixel arrays on main thread (drawable access requires it), hash off-thread.
        val firstPixels = firstImage?.let { capturePixels(it) }
        val secondPixels = secondImage?.let { capturePixels(it) }
        return hashExecutor.submit<Pair<String?, String?>> {
            val h1 = firstPixels?.let { computeHash(it) }
            val h2 = secondPixels?.let { computeHash(it) }
            h1 to h2
        }
    }

    fun close() {
        hashExecutor.shutdownNow()
    }

    /** Resolve the two alert [ImageView]s from the applied RemoteViews tree (verbose PNG capture). */
    fun alertImageViews(root: AppWidgetHostView): Pair<ImageView?, ImageView?> {
        val first = view(root, VietMapWidgetViewNames.FIRST_ALERT_IMAGE) as? ImageView
        val second = view(root, VietMapWidgetViewNames.SECOND_ALERT_IMAGE) as? ImageView
        return first to second
    }

    /**
     * Verbose discovery (spec §4.4 C2): recursively walk the applied RemoteViews hierarchy and surface
     * EVERY leaf we might care about — including fields the app does NOT yet parse — as flat strings:
     *   • `TV:<resEntryNameOrHex>=<text>`      for every [TextView] (and subclasses)
     *   • `IV:<resEntryNameOrHex>=visible|gone` for every [ImageView] (and subclasses)
     * The id name is resolved via the VietMap package [remoteResources] ([Resources.getResourceEntryName]);
     * ids without a name (or id==0) fall back to a `0x`-prefixed hex. Pure read of the view tree — must be
     * called on the thread that owns the views (main); the caller hands the result off-thread for the write.
     */
    fun dumpAllViews(root: View): List<String> {
        val out = ArrayList<String>()
        walkViews(root, out)
        return out
    }

    private fun walkViews(v: View, out: MutableList<String>) {
        when (v) {
            is TextView -> out.add("TV:${resEntryName(v.id)}=${v.text?.toString().orEmpty()}")
            is ImageView -> out.add("IV:${resEntryName(v.id)}=${if (v.visibility == View.VISIBLE) "visible" else "gone"}")
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkViews(v.getChildAt(i), out)
        }
    }

    private fun resEntryName(id: Int): String {
        if (id == 0 || id == View.NO_ID) return "0x0"
        return try {
            remoteResources?.getResourceEntryName(id) ?: "0x%08x".format(id)
        } catch (_: Resources.NotFoundException) {
            "0x%08x".format(id)
        }
    }

    /**
     * Verbose discovery (spec §4.4 C3): write the alert icon bitmap to `dir/vietmap-alert-<hash>.png`
     * ONCE per unique [hash] (skip if the file already exists) so off-car we can eyeball what each
     * camera/police/… icon actually looks like. Pixels are captured on the calling (main) thread — drawable
     * access requires it, reusing the same capture used for hashing — then the PNG encode + write are
     * deferred to [hashExecutor]. Degrade-safe: every failure is swallowed.
     */
    fun saveAlertImagePng(image: ImageView, hash: String, dir: File) {
        val target = File(dir, "vietmap-alert-$hash.png")
        if (target.exists()) return
        val captured = capturePixelsSized(image) ?: return
        hashExecutor.execute {
            runCatching {
                if (target.exists()) return@runCatching
                if (!dir.exists()) dir.mkdirs()
                val bitmap = Bitmap.createBitmap(captured.width, captured.height, Bitmap.Config.ARGB_8888)
                bitmap.setPixels(captured.pixels, 0, captured.width, 0, 0, captured.width, captured.height)
                val part = File(dir, "vietmap-alert-$hash.png.part")
                FileOutputStream(part).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
                if (!part.renameTo(target)) part.delete()
            }
        }
    }

    // --- Private helpers ---

    private class CapturedPixels(val pixels: IntArray, val width: Int, val height: Int)

    private fun capturePixels(image: ImageView): IntArray? = capturePixelsSized(image)?.pixels

    private fun capturePixelsSized(image: ImageView): CapturedPixels? {
        val drawable = image.drawable ?: return null
        return try {
            val width = drawable.intrinsicWidth.takeIf { it > 0 }?.coerceAtMost(MAX_HASH_EDGE) ?: 1
            val height = drawable.intrinsicHeight.takeIf { it > 0 }?.coerceAtMost(MAX_HASH_EDGE) ?: 1
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val copy = drawable.constantState?.newDrawable()?.mutate() ?: drawable.mutate()
            copy.setBounds(0, 0, width, height)
            copy.draw(Canvas(bitmap))
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            bitmap.recycle()
            CapturedPixels(pixels, width, height)
        } catch (error: RuntimeException) {
            Log.w(TAG, "pixel capture failed: ${error.javaClass.simpleName}")
            null
        }
    }

    private fun computeHash(pixels: IntArray): String {
        val bytes = ByteBuffer.allocate(pixels.size * Int.SIZE_BYTES)
        pixels.forEach(bytes::putInt)
        return MessageDigest.getInstance("SHA-256").digest(bytes.array())
            .joinToString("") { "%02x".format(it) }
    }

    private fun resolvedNames(names: Set<String>): Set<String> =
        names.filterTo(linkedSetOf()) { id(it) != 0 }

    private fun view(root: View, name: String): View? =
        id(name).takeIf { it != 0 }?.let(root::findViewById)

    private fun text(root: View, name: String): TextView? = view(root, name) as? TextView

    private fun id(name: String): Int =
        remoteResources?.getIdentifier(name, "id", VIETMAP_PACKAGE) ?: 0

    private fun effectivelyVisible(view: View, root: View): Boolean {
        var current: View? = view
        while (current != null) {
            if (current.visibility != View.VISIBLE) return false
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }
}
