package com.folio.launcher.data

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Scale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WallpaperRepository(private val context: Context) {
    val file: File get() = File(context.filesDir, "wallpaper.jpg")
    private val prevFile: File get() = File(context.filesDir, "wallpaper.prev.jpg")
    private val nextFile: File get() = File(context.filesDir, "wallpaper.next.jpg")
    private val bing = BingClient()

    fun exists(): Boolean = file.exists() && file.length() > 0L

    fun prevExists(): Boolean = prevFile.exists() && prevFile.length() > 0L

    fun systemWallpaperReadable(): Boolean {
        return try {
            val wm = WallpaperManager.getInstance(context)
            (wm.peekFastDrawable() ?: wm.peekDrawable()) != null
        } catch (_: Exception) {
            false
        }
    }

    suspend fun importFromUri(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                nextFile.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching false
            if (nextFile.length() <= 0L) return@runCatching false
            promoteNext()
            true
        }.getOrDefault(false)
    }

    suspend fun importBing(index: Int): BingShot? = withContext(Dispatchers.IO) {
        runCatching {
            val dm = context.resources.displayMetrics
            val image = bing.imageAt(
                index = index,
                width = dm.widthPixels,
                height = dm.heightPixels,
            ) ?: return@runCatching null
            if (!bing.download(image, nextFile, dm.widthPixels, dm.heightPixels)) return@runCatching null
            promoteNext()
            BingShot(
                index = index,
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

    suspend fun bingCount(): Int = withContext(Dispatchers.IO) {
        runCatching { bing.archive().size }.getOrDefault(0)
    }

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

    suspend fun load(targetW: Int, targetH: Int): LoadedWallpaper? = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext null
        val bleed = 1.08f
        val w = (targetW.coerceAtLeast(64) * bleed).toInt().coerceAtLeast(64)
        val h = (targetH.coerceAtLeast(64) * bleed).toInt().coerceAtLeast(64)
        val src = decodeFile(file, w, h) ?: decodeWithCoil(file, w, h) ?: return@withContext null
        val cropped = cover(src, w, h)
        val accent = AccentExtractor.extract(cropped)
        val photo = cropped.copy(Bitmap.Config.ARGB_8888, false)
        val image = photo.asImageBitmap()
        LoadedWallpaper(
            photo = image,
            accent = accent,
        )
    }

    private fun decodeFile(file: File, reqW: Int, reqH: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > reqW * 2 && bounds.outHeight / sample > reqH * 2) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    private suspend fun decodeWithCoil(file: File, w: Int, h: Int): Bitmap? {
        return runCatching {
            val request = ImageRequest.Builder(context)
                .data(file)
                .size(w, h)
                .scale(Scale.FILL)
                .allowHardware(false)
                .bitmapConfig(Bitmap.Config.ARGB_8888)
                .build()
            val drawable = context.imageLoader.execute(request).drawable ?: return null
            when (drawable) {
                is BitmapDrawable -> drawable.bitmap
                else -> {
                    val bw = drawable.intrinsicWidth.coerceAtLeast(1)
                    val bh = drawable.intrinsicHeight.coerceAtLeast(1)
                    Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { out ->
                        val canvas = Canvas(out)
                        drawable.setBounds(0, 0, bw, bh)
                        drawable.draw(canvas)
                    }
                }
            }
        }.getOrNull()
    }

    private fun cover(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val win = CoverCrop.window(src.width, src.height, w, h)
        val cropped = Bitmap.createBitmap(src, win.x, win.y, win.width, win.height)
        if (cropped.width == w && cropped.height == h) return cropped
        return Bitmap.createScaledBitmap(cropped, w, h, true)
    }

    data class LoadedWallpaper(
        val photo: ImageBitmap,
        val accent: Int,
    )

    data class BingShot(
        val index: Int,
        val caption: String,
        val credit: String,
    )
}
