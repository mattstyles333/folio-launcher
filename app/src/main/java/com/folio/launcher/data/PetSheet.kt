package com.folio.launcher.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The moves in a petdex sheet (what Hermes `/hatch` writes): an 8-column atlas, one row per move.
 * Rows and hold times are petdex's own (`packages/petdex-desktop-native/src/sprite.zig`).
 */
enum class PetMove(val row: Int, val holdsMs: IntArray) {
    Idle(0, intArrayOf(280, 110, 110, 140, 140, 320)),
    Waving(3, even(4, 140, 280)),
    Jumping(4, even(5, 140, 280)),
    Failed(5, even(8, 140, 240)),
    Waiting(6, even(6, 150, 260)),
    Running(7, even(6, 120, 220)),
    Review(8, even(6, 150, 280)),
    ;

    val loopMs: Int get() = holdsMs.sum()
    val lastColumn: Int get() = holdsMs.lastIndex
}

private fun even(count: Int, each: Int, last: Int) = IntArray(count) { if (it == count - 1) last else each }

/** Frame size and row count of a sheet, from its pixel size. */
data class PetGeometry(val frameW: Int, val frameH: Int, val rows: Int)

class PetSheet(val image: ImageBitmap, val geometry: PetGeometry)

object PetFrames {
    const val COLUMNS = 8
    private const val FRAME_W = 192
    private const val FRAME_H = 208

    /** v1 sheets have 9 rows, v2 have 11 (two left for the client). */
    private val ROWS = 9..11

    /** Column of [move] to show [elapsedMs] in, looping. */
    fun column(move: PetMove, elapsedMs: Long): Int {
        var t = (elapsedMs % move.loopMs).toInt()
        move.holdsMs.forEachIndexed { i, hold ->
            if (t < hold) return i
            t -= hold
        }
        return move.lastColumn
    }

    /** Null unless [width]×[height] is an 8-column petdex atlas (at any scale). */
    fun geometry(width: Int, height: Int): PetGeometry? {
        if (width < COLUMNS || height <= 0 || width % COLUMNS != 0) return null
        val frameW = width / COLUMNS
        val frameH = (frameW * FRAME_H / FRAME_W.toFloat()).roundToInt()
        val rows = (height / frameH.toFloat()).roundToInt()
        if (rows !in ROWS || abs(height - rows * frameH) > rows) return null
        return PetGeometry(frameW, frameH, rows)
    }
}

/** The imported sheet, kept as a file in app storage. */
class PetStore(context: Context) {
    private val file = File(context.filesDir, "pet/spritesheet")
    private val resolver = context.contentResolver

    fun exists(): Boolean = file.isFile

    /** Copies a picked petdex sheet in; false if it isn't one. */
    suspend fun import(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching false
            if (bytes.size > MAX_BYTES) return@runCatching false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (PetFrames.geometry(bounds.outWidth, bounds.outHeight) == null) return@runCatching false
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(file) || run {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }.getOrDefault(false)
    }

    suspend fun load(): PetSheet? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: return@runCatching null
            val geometry = PetFrames.geometry(bitmap.width, bitmap.height) ?: return@runCatching null
            PetSheet(bitmap.asImageBitmap(), geometry)
        }.getOrNull()
    }

    fun remove() {
        file.delete()
    }

    private companion object {
        const val MAX_BYTES = 24 * 1024 * 1024
    }
}
