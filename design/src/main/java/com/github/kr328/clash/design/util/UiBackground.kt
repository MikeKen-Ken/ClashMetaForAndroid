package com.github.kr328.clash.design.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.github.kr328.clash.design.model.WallpaperPlaybackMode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.random.Random

@Serializable
private data class WallpaperItem(
    val id: String,
    val fileName: String,
)

@Serializable
private data class WallpaperManifest(
    val version: Int = 1,
    val playback: String = "fixed",
    val intervalSeconds: Int = UiBackground.DEFAULT_INTERVAL_SECONDS,
    val activeId: String = "",
    val items: List<WallpaperItem> = emptyList(),
)

/** 应用自定义背景图库：多图、随机轮换、WebDAV 包，行为对齐看板 Wallpaper library。 */
object UiBackground {
    const val FILE_NAME = "ui_background"
    const val DIR_NAME = "ui_backgrounds"
    const val MANIFEST_NAME = "manifest.json"
    const val PACK_FILE_NAME = "clash-ui-wallpapers.zip"
    const val REMOTE_DIR = "clash-verge-rev-backup"
    const val DEFAULT_OVERLAY_PERCENT = 40
    const val MAX_OVERLAY_PERCENT = 70
    const val DEFAULT_CARD_OPACITY_PERCENT = 100
    const val MIN_CARD_OPACITY_PERCENT = 35
    const val DEFAULT_INTERVAL_SECONDS = 300
    private const val MAX_PACK_BYTES = 20 * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    private const val MAX_PACK_FILES = 40

    val overlayPercents = arrayOf(0, 20, DEFAULT_OVERLAY_PERCENT, 60)
    val cardOpacityPercents = arrayOf(DEFAULT_CARD_OPACITY_PERCENT, 85, 70, 50)
    val intervalOptions = arrayOf(30, 60, 300, 900, 3600)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val imageViews = mutableListOf<WeakReference<ImageView>>()
    private val chromeViews = mutableListOf<WeakReference<View>>()
    private val rotateRunnable = Runnable { rotateIfNeeded() }

    @Volatile
    private var cachedCover: Bitmap? = null

    @Volatile
    private var cachedFrost: Bitmap? = null

    @Volatile
    private var cachedCoverId: String = ""

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = loadManifest(context).items.isNotEmpty()

    fun count(context: Context): Int = loadManifest(context).items.size

    fun overlayColor(context: Context, percent: Int): Int {
        val clamped = percent.coerceIn(0, MAX_OVERLAY_PERCENT)
        val alpha = (clamped * 255 / 100f).toInt().coerceIn(0, 255)
        val base = context.resolveThemedColor(android.R.attr.windowBackground)
        return Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))
    }

    fun cardSurfaceAlpha(context: Context, opacityPercent: Int): Int {
        if (!exists(context)) return 255
        val clamped = opacityPercent.coerceIn(MIN_CARD_OPACITY_PERCENT, DEFAULT_CARD_OPACITY_PERCENT)
        return (clamped * 255 / 100f).toInt().coerceIn(0, 255)
    }

    fun playbackMode(context: Context): WallpaperPlaybackMode {
        return if (loadManifest(context).playback.equals("random", ignoreCase = true)) {
            WallpaperPlaybackMode.Random
        } else {
            WallpaperPlaybackMode.Fixed
        }
    }

    fun setPlaybackMode(context: Context, mode: WallpaperPlaybackMode) {
        val current = loadManifest(context)
        saveManifest(
            context,
            current.copy(playback = if (mode == WallpaperPlaybackMode.Random) "random" else "fixed"),
        )
        scheduleRotation(context)
    }

    fun intervalSeconds(context: Context): Int {
        val value = loadManifest(context).intervalSeconds
        return if (intervalOptions.contains(value)) value else DEFAULT_INTERVAL_SECONDS
    }

    fun setIntervalSeconds(context: Context, seconds: Int) {
        val clamped = if (intervalOptions.contains(seconds)) seconds else DEFAULT_INTERVAL_SECONDS
        saveManifest(context, loadManifest(context).copy(intervalSeconds = clamped))
        scheduleRotation(context)
    }

    fun wrap(context: Context, content: View, overlayPercent: Int): View {
        val image = decodeCover(context) ?: return content
        val frame = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val imageView = ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setImageBitmap(image)
        }
        watchImage(imageView)
        frame.addView(imageView)
        if (overlayPercent > 0) {
            frame.addView(View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setBackgroundColor(overlayColor(context, overlayPercent))
            })
        }
        content.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        (content.parent as? ViewGroup)?.removeView(content)
        frame.addView(content)
        WallpaperReadability.install(content)
        scheduleRotation(context)
        return frame
    }

    fun import(context: Context, uri: Uri): Boolean {
        val destDir = dir(context).apply { mkdirs() }
        val id = UUID.randomUUID().toString().replace("-", "")
        val tmp = File(context.cacheDir, "$DIR_NAME-$id.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                tmp.delete()
                return false
            }
            val ext = guessExt(bounds.outMimeType)
            val fileName = "$id.$ext"
            tmp.copyTo(File(destDir, fileName), overwrite = true)
            val current = loadManifest(context)
            val items = current.items + WallpaperItem(id = id, fileName = fileName)
            saveManifest(
                context,
                current.copy(
                    items = items,
                    activeId = current.activeId.ifBlank { id },
                ),
            )
            invalidateCache()
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    fun clear(context: Context) {
        dir(context).deleteRecursively()
        file(context).delete()
        invalidateCache()
        mainHandler.removeCallbacks(rotateRunnable)
        notifyViews(null)
    }

    fun encodePack(context: Context): ByteArray? {
        val manifest = loadManifest(context)
        if (manifest.items.isEmpty()) return null
        val destDir = dir(context)
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(json.encodeToString(WallpaperManifest.serializer(), manifest).toByteArray())
            zip.closeEntry()
            for (item in manifest.items) {
                val source = File(destDir, item.fileName)
                if (!source.isFile) continue
                zip.putNextEntry(ZipEntry("images/${item.fileName}"))
                source.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    fun applyPack(context: Context, bytes: ByteArray): Boolean {
        return try {
            val destDir = dir(context)
            destDir.deleteRecursively()
            destDir.mkdirs()
            var manifest: WallpaperManifest? = null
            ZipInputStream(bytes.inputStream()).use { zip ->
                var total = 0
                var files = 0
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.replace('\\', '/').trimStart('/')
                    if (name.contains("..")) continue
                    when {
                        name == MANIFEST_NAME -> {
                            val payload = zip.readBytes()
                            total += payload.size
                            if (total > MAX_PACK_BYTES || payload.size > MAX_ENTRY_BYTES) {
                                destDir.deleteRecursively()
                                return false
                            }
                            manifest = json.decodeFromString(
                                WallpaperManifest.serializer(),
                                payload.decodeToString(),
                            )
                        }
                        name.startsWith("images/") && !entry.isDirectory -> {
                            val fileName = safeWallpaperFileName(name.substringAfterLast('/'))
                                ?: continue
                            files += 1
                            if (files > MAX_PACK_FILES) {
                                destDir.deleteRecursively()
                                return false
                            }
                            val dest = File(destDir, fileName)
                            dest.outputStream().use { output ->
                                val buf = ByteArray(8192)
                                var written = 0
                                while (true) {
                                    val n = zip.read(buf)
                                    if (n < 0) break
                                    written += n
                                    total += n
                                    if (written > MAX_ENTRY_BYTES || total > MAX_PACK_BYTES) {
                                        destDir.deleteRecursively()
                                        return false
                                    }
                                    output.write(buf, 0, n)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            val resolved = manifest?.let { loaded ->
                loaded.copy(
                    items = loaded.items.mapNotNull { item ->
                        val fileName = safeWallpaperFileName(item.fileName) ?: return@mapNotNull null
                        val file = File(destDir, fileName)
                        if (!file.isFile) return@mapNotNull null
                        item.copy(fileName = fileName)
                    },
                )
            }
            if (resolved == null || resolved.items.isEmpty()) {
                destDir.deleteRecursively()
                return false
            }
            saveManifest(context, resolved)
            invalidateCache()
            true
        } catch (_: Exception) {
            false
        }
    }

    fun frostedCover(context: Context): Bitmap? {
        decodeCover(context) ?: return null
        return cachedFrost
    }

    fun watchChrome(view: View) {
        pruneGone(chromeViews)
        chromeViews.add(WeakReference(view))
    }

    fun scheduleRotation(context: Context) {
        mainHandler.removeCallbacks(rotateRunnable)
        val manifest = loadManifest(context)
        if (!manifest.playback.equals("random", ignoreCase = true) || manifest.items.size < 2) {
            return
        }
        val delay = (intervalSeconds(context) * 1000L).coerceAtLeast(5_000L)
        mainHandler.postDelayed(rotateRunnable, delay)
    }

    private fun rotateIfNeeded() {
        val context = imageViews.firstNotNullOfOrNull { it.get()?.context?.applicationContext }
            ?: chromeViews.firstNotNullOfOrNull { it.get()?.context?.applicationContext }
            ?: return
        val manifest = loadManifest(context)
        if (!manifest.playback.equals("random", ignoreCase = true) || manifest.items.size < 2) {
            return
        }
        val current = resolveActive(manifest)
        var next = current
        var guard = 0
        while (next.id == current.id && guard < 8) {
            next = manifest.items[Random.nextInt(manifest.items.size)]
            guard++
        }
        saveManifest(context, manifest.copy(activeId = next.id))
        invalidateCache()
        notifyViews(decodeCover(context))
        scheduleRotation(context)
    }

    private fun watchImage(view: ImageView) {
        pruneGone(imageViews)
        imageViews.add(WeakReference(view))
    }

    private fun notifyViews(bitmap: Bitmap?) {
        pruneGone(imageViews)
        pruneGone(chromeViews)
        imageViews.forEach { ref ->
            ref.get()?.let { view ->
                if (bitmap != null) view.setImageBitmap(bitmap)
            }
        }
        chromeViews.forEach { it.get()?.invalidate() }
    }

    private fun <T> pruneGone(list: MutableList<WeakReference<T>>) {
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) iterator.remove()
        }
    }

    private fun decodeCover(context: Context): Bitmap? {
        migrateLegacy(context)
        val manifest = loadManifest(context)
        val active = resolveActive(manifest)
        if (active.id.isEmpty()) return null
        val fileName = safeWallpaperFileName(active.fileName) ?: return null
        if (cachedCover != null && cachedCoverId == active.id) return cachedCover
        val destDir = dir(context)
        val target = File(destDir, fileName)
        if (target.canonicalFile.parentFile?.canonicalFile != destDir.canonicalFile) return null
        if (!target.isFile || target.length() <= 0L) return null
        val metrics = context.resources.displayMetrics
        val reqW = metrics.widthPixels.coerceAtLeast(1)
        val reqH = metrics.heightPixels.coerceAtLeast(1)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(target.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > reqW * 2 || bounds.outHeight / sample > reqH * 2) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            target.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        cachedCover = decoded
        cachedFrost = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width / 8).coerceAtLeast(1),
            (decoded.height / 8).coerceAtLeast(1),
            true,
        )
        cachedCoverId = active.id
        return decoded
    }

    private fun invalidateCache() {
        cachedCover = null
        cachedFrost = null
        cachedCoverId = ""
    }

    private fun safeWallpaperFileName(name: String): String? {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
        if (base.isBlank() || base.contains("..")) return null
        if (!base.matches(Regex("[A-Za-z0-9._-]+"))) return null
        return base
    }

    private fun loadManifest(context: Context): WallpaperManifest {
        migrateLegacy(context)
        val file = File(dir(context), MANIFEST_NAME)
        if (!file.isFile) return WallpaperManifest()
        return try {
            json.decodeFromString(WallpaperManifest.serializer(), file.readText())
        } catch (_: Exception) {
            WallpaperManifest()
        }
    }

    private fun saveManifest(context: Context, manifest: WallpaperManifest) {
        val destDir = dir(context).apply { mkdirs() }
        File(destDir, MANIFEST_NAME).writeText(
            json.encodeToString(WallpaperManifest.serializer(), manifest),
        )
    }

    private fun resolveActive(manifest: WallpaperManifest): WallpaperItem {
        if (manifest.items.isEmpty()) return WallpaperItem("", "")
        return manifest.items.find { it.id == manifest.activeId } ?: manifest.items.first()
    }

    private fun migrateLegacy(context: Context) {
        val legacy = file(context)
        if (!legacy.isFile || legacy.length() <= 0L) return
        val destDir = dir(context)
        if (File(destDir, MANIFEST_NAME).isFile) {
            legacy.delete()
            return
        }
        destDir.mkdirs()
        val id = "legacy"
        val fileName = "$id.jpg"
        legacy.copyTo(File(destDir, fileName), overwrite = true)
        saveManifest(
            context,
            WallpaperManifest(
                items = listOf(WallpaperItem(id = id, fileName = fileName)),
                activeId = id,
            ),
        )
        legacy.delete()
    }

    private fun guessExt(mime: String?): String {
        return when (mime?.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "image/bmp", "image/x-ms-bmp" -> "bmp"
            else -> "jpg"
        }
    }
}
