package com.pulse.launcher.data

import android.graphics.Bitmap
import android.graphics.Color
import androidx.palette.graphics.Palette

object AccentExtractor {
    fun extract(bitmap: Bitmap): Int {
        val palette = Palette.from(bitmap)
            .resizeBitmapArea(80 * 80)
            .clearFilters()
            .generate()
        val swatch = palette.mutedSwatch
            ?: palette.darkMutedSwatch
            ?: palette.lightMutedSwatch
            ?: palette.dominantSwatch
        return lift(swatch?.rgb ?: 0xFFC4B8A8.toInt())
    }

    private fun lift(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.12f, 0.42f)
        hsv[2] = hsv[2].coerceIn(0.58f, 0.86f)
        return Color.HSVToColor(hsv)
    }
}
