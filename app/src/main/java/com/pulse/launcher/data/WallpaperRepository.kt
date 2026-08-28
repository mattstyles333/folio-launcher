package com.pulse.launcher.data

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
    private val bing = BingClient()

    fun exists(): Boolean = file.exists() && file.length() > 0L

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
                file.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching false
            file.length() > 0L
        }.getOrDefault(false)
    }

    suspend fun importBing(index: Int): BingShot? = withContext(Dispatchers.IO) {
        runCatching {
            val image = bing.imageAt(index) ?: return@runCatching null
            if (!bing.download(image, file)) return@runCatching null
            BingShot(
                index = index,
                caption = image.caption(),
                credit = image.copyright,
            )
        }.getOrNull()
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
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            true
        }.getOrDefault(false)
    }

    suspend fun load(targetW: Int, targetH: Int): LoadedWallpaper? = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext null
        val w = targetW.coerceAtLeast(64)
        val h = targetH.coerceAtLeast(64)
        val src = decodeFile(file, w, h) ?: decodeWithCoil(file, w, h) ?: return@withContext null
        val cropped = centerCrop(src, w, h)
        val accent = AccentExtractor.extract(cropped)
        val photo = cropped.copy(Bitmap.Config.ARGB_8888, false)
        val blurred = atmosphereBlur(photo)
        LoadedWallpaper(
            photo = photo.asImageBitmap(),
            blurred = blurred.asImageBitmap(),
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

    private fun centerCrop(src: Bitmap, w: Int, h: Int): Bitmap {
        if (src.width == w && src.height == h) return src
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val scaledW = (src.width * scale).toInt().coerceAtLeast(1)
        val scaledH = (src.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(src, scaledW, scaledH, true)
        val x = ((scaledW - w) / 2).coerceAtLeast(0)
        val y = ((scaledH - h) / 2.4f).toInt().coerceAtLeast(0).coerceAtMost((scaledH - h).coerceAtLeast(0))
        return Bitmap.createBitmap(scaled, x, y, w.coerceAtMost(scaled.width), h.coerceAtMost(scaled.height))
    }

    private fun atmosphereBlur(src: Bitmap): Bitmap {
        fun pass(b: Bitmap, factor: Int): Bitmap {
            val w = (b.width / factor).coerceAtLeast(8)
            val h = (b.height / factor).coerceAtLeast(8)
            val small = Bitmap.createScaledBitmap(b, w, h, true)
            return Bitmap.createScaledBitmap(small, b.width, b.height, true)
        }
        return pass(pass(pass(src, 14), 8), 5)
    }

    data class LoadedWallpaper(
        val photo: ImageBitmap,
        val blurred: ImageBitmap,
        val accent: Int,
    )

    data class BingShot(
        val index: Int,
        val caption: String,
        val credit: String,
    )
}
