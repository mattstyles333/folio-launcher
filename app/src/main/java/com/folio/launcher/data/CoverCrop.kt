package com.folio.launcher.data

import kotlin.math.roundToInt

/** Portrait cover window in source pixels. Crop only — the caller scales the result. */
data class CoverWindow(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

object CoverCrop {
    fun window(
        srcW: Int,
        srcH: Int,
        dstW: Int,
        dstH: Int,
        yBias: Float = 2.4f,
    ): CoverWindow {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) {
            return CoverWindow(0, 0, srcW.coerceAtLeast(1), srcH.coerceAtLeast(1))
        }
        val dstAspect = dstW.toFloat() / dstH.toFloat()
        val srcAspect = srcW.toFloat() / srcH.toFloat()
        val cropW: Int
        val cropH: Int
        if (srcAspect > dstAspect) {
            cropH = srcH
            cropW = (srcH * dstAspect).roundToInt().coerceIn(1, srcW)
        } else {
            cropW = srcW
            cropH = (srcW / dstAspect).roundToInt().coerceIn(1, srcH)
        }
        val x = ((srcW - cropW) / 2).coerceAtLeast(0)
        val y = ((srcH - cropH) / yBias).roundToInt()
            .coerceIn(0, (srcH - cropH).coerceAtLeast(0))
        return CoverWindow(x, y, cropW, cropH)
    }
}
