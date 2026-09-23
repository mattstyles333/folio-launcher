package com.folio.launcher.data

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.hardware.display.DisplayManager
import android.net.Uri
import android.view.Display
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WallpaperRepository(private val context: Context) {
    val file: File get() = File(context.filesDir, "wallpaper.jpg")
    private val prevFile: File get() = File(context.filesDir, "wallpaper.prev.jpg")
    private val nextFile: File get() = File(context.filesDir, "wallpaper.next.jpg")
    private val bing = BingClient(File(context.filesDir, "bing-archive.json"))

    fun exists(): Boolean = file.exists() && file.length() > 0L

    fun prevExists(): Boolean = prevFile.exists() && prevFile.length() > 0L

    /** Full panel in portrait (1080×2340 on the S23), independent of bars or window insets. */
    fun panelSize(): Pair<Int, Int> {
        val mode = runCatching {
            context.getSystemService(DisplayManager::class.java)
                .getDisplay(Display.DEFAULT_DISPLAY)
                ?.mode
        }.getOrNull()
        val dm = context.resources.displayMetrics
        val a = mode?.physicalWidth ?: dm.widthPixels
        val b = mode?.physicalHeight ?: dm.heightPixels
        return min(a, b).coerceAtLeast(64) to max(a, b).coerceAtLeast(64)
    }

    private fun printSize(): Pair<Int, Int> {
        val (w, h) = panelSize()
        return (w * PARALLAX_BLEED).roundToInt() to (h * PARALLAX_BLEED).roundToInt()
    }

    // Reading another app's wallpaper needs a permission Folio can't hold on 13+; both reads
    // fail closed (false / no import) and onboarding hides "Use system" when that happens.
    @SuppressLint("MissingPermission")
    fun systemWallpaperReadable(): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context)
            (wm.peekFastDrawable() ?: wm.peekDrawable()) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Re-encodes the picked photo upright (EXIF applied) and no larger than the print needs,
     * so a 50 MP original never has to be decoded at full size again.
     */
    suspend fun importFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val (w, h) = printSize()
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val scale = coverScale(info.size.width, info.size.height, w, h)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1),
                    )
                }
            }
            nextFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            bmp.recycle()
            if (nextFile.length() <= 0L) return@runCatching false
            promoteNext()
            true
        }.getOrDefault(false)
    }

    /** Downloads a Bing print that isn't one of [avoid] (Bing identities). */
    suspend fun importBing(avoid: Set<String>): BingShot? = withContext(Dispatchers.IO) {
        runCatching {
            val (w, h) = panelSize()
            val image = BingClient.pickNext(bing.archive(), avoid) ?: return@runCatching null
            if (!bing.download(image, nextFile, w, h)) return@runCatching null
            promoteNext()
            BingShot(
                id = image.identity(),
                caption = image.caption(),
                credit = image.copyright,
            )
        }.getOrNull()
    }

    suspend fun swapWithPrev(): Boolean = withContext(Dispatchers.IO) {
        if (!prevExists()) return@withContext false
        val tmp = File(context.filesDir, "wallpaper.swap.jpg")
        if (tmp.exists()) tmp.delete()
        val hadCurrent = file.exists()
        if (hadCurrent && !file.renameTo(tmp)) return@withContext false
        if (!prevFile.renameTo(file)) {
            if (hadCurrent) tmp.renameTo(file)
            return@withContext false
        }
        if (tmp.exists()) tmp.renameTo(prevFile)
        exists()
    }

    @SuppressLint("MissingPermission")
    suspend fun importSystem(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val wm = WallpaperManager.getInstance(context)
            val drawable = wm.peekFastDrawable() ?: wm.peekDrawable() ?: wm.drawable
                ?: return@runCatching false
            val bmp = when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val w = drawable.intrinsicWidth.coerceAtLeast(1)
                    val h = drawable.intrinsicHeight.coerceAtLeast(1)
                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
                        val canvas = Canvas(out)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(canvas)
                    }
                }
            }
            nextFile.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            if (nextFile.length() <= 0L) return@runCatching false
            promoteNext()
            true
        }.getOrDefault(false)
    }

    private fun stashCurrentAsPrev() {
        if (!exists()) return
        file.copyTo(prevFile, overwrite = true)
    }

    private fun promoteNext() {
        if (!nextFile.exists() || nextFile.length() <= 0L) return
        stashCurrentAsPrev()
        if (file.exists()) file.delete()
        if (!nextFile.renameTo(file)) {
            nextFile.copyTo(file, overwrite = true)
            nextFile.delete()
        }
    }

    suspend fun load(): LoadedWallpaper? = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext null
        val (w, h) = printSize()
        val photo = decodeRegion(file, w, h) ?: decodeWhole(file, w, h) ?: return@withContext null
        LoadedWallpaper(
            photo = photo.asImageBitmap(),
            accent = AccentExtractor.extract(photo),
        )
    }

    /** Decodes only the portrait cover window — a landscape UHD never lands in memory whole. */
    private fun decodeRegion(file: File, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val win = CoverCrop.window(bounds.outWidth, bounds.outHeight, w, h)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = CoverCrop.sampleSize(win.width, win.height, w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val region = runCatching {
            val decoder = BitmapRegionDecoder.newInstance(file.absolutePath)
            try {
                decoder.decodeRegion(Rect(win.x, win.y, win.x + win.width, win.y + win.height), opts)
            } finally {
                decoder.recycle()
            }
        }.getOrNull() ?: return null
        return scaleTo(region, w, h)
    }

    /** Formats the region decoder can't read. */
    private fun decodeWhole(file: File, w: Int, h: Int): Bitmap? = runCatching {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        val win = CoverCrop.window(src.width, src.height, w, h)
        val cropped = Bitmap.createBitmap(src, win.x, win.y, win.width, win.height)
        if (cropped !== src) src.recycle()
        scaleTo(cropped, w, h)
    }.getOrNull()

    private fun scaleTo(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scaled = Bitmap.createScaledBitmap(src, w, h, true)
        if (scaled !== src) src.recycle()
        return scaled
    }

    data class LoadedWallpaper(
        val photo: ImageBitmap,
        val accent: Int,
    )

    data class BingShot(
        val id: String,
        val caption: String,
        val credit: String,
    )

    companion object {
        /** A little over the panel so parallax never shows an edge. */
        private const val PARALLAX_BLEED = 1.08f

        /** Scale (≤ 1 means shrink) at which [srcW]×[srcH] just covers [dstW]×[dstH]. */
        internal fun coverScale(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Float {
            if (srcW <= 0 || srcH <= 0) return 1f
            return max(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
        }
    }
}
