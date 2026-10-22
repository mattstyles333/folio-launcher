package com.folio.launcher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap

@Composable
fun AppIcon(
    bitmap: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    saturation: Float = 1f,
    /** Paused by an app timer, Focus mode or an admin: drawn faded, which still reads in Silent's grey. */
    paused: Boolean = false,
) {
    val sat by animateFloatAsState(
        targetValue = saturation.coerceIn(0f, 1f),
        animationSpec = tween(280),
        label = "iconSat",
    )
    val filter = remember(sat) {
        if (sat >= 0.995f) {
            null
        } else {
            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(sat) })
        }
    }
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
        alpha = if (paused) PausedAlpha else 1f,
        colorFilter = filter,
    )
}

private const val PausedAlpha = 0.4f
