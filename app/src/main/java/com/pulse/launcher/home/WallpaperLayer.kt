package com.pulse.launcher.home

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pulse.launcher.R
import com.pulse.launcher.data.RingerVisual
import com.pulse.launcher.ui.VoidBlack

@Composable
fun WallpaperLayer(
    photo: ImageBitmap?,
    blurred: ImageBitmap?,
    mode: RingerVisual,
    accent: Color,
    parallax: ParallaxState? = null,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val grain = remember(resources) {
        BitmapFactory.decodeResource(resources, R.drawable.grain).asImageBitmap()
    }
    val halfColor = remember { ColorFilter.colorMatrix(saturationMatrix(0.5f)) }
    val blackWhite = remember { ColorFilter.colorMatrix(saturationMatrix(0f)) }
    Box(modifier.fillMaxSize().background(VoidBlack)) {
        Crossfade(targetState = mode, animationSpec = tween(320), label = "mode") { current ->
            when (current) {
                RingerVisual.Sound -> GradedPrint(
                    photo = photo,
                    accent = accent,
                    filter = null,
                    grain = grain,
                    parallax = parallax,
                )
                RingerVisual.Vibrate -> GradedPrint(
                    photo = photo,
                    accent = accent,
                    filter = halfColor,
                    grain = null,
                    parallax = parallax,
                )
                RingerVisual.Silent -> GradedPrint(
                    photo = photo,
                    accent = accent,
                    filter = blackWhite,
                    grain = null,
                    parallax = parallax,
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.48f)),
                    ),
                ),
        )
    }
}

@Composable
private fun GradedPrint(
    photo: ImageBitmap?,
    accent: Color,
    filter: ColorFilter?,
    grain: ImageBitmap?,
    parallax: ParallaxState?,
) {
    Box(Modifier.fillMaxSize()) {
        Crossfade(targetState = photo, animationSpec = tween(520), label = "printPhoto") { current ->
            if (current != null) {
                Image(
                    bitmap = current,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = parallax?.x ?: 0f
                            translationY = parallax?.y ?: 0f
                            scaleX = 1.08f
                            scaleY = 1.08f
                        },
                    contentScale = ContentScale.Crop,
                    colorFilter = filter,
                )
            } else {
                PhotoOrGradient(null, accent)
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.28f)),
                        center = Offset.Unspecified,
                        radius = 1500f,
                    ),
                ),
        )
        if (grain != null) {
            val brush = remember(grain) {
                ShaderBrush(ImageShader(grain, TileMode.Repeated, TileMode.Repeated))
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        onDrawWithContent {
                            drawRect(brush, alpha = 0.055f, blendMode = BlendMode.Overlay)
                        }
                    },
            )
        }
    }
}

@Composable
private fun PhotoOrGradient(photo: ImageBitmap?, accent: Color) {
    if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF141821),
                            Color(0xFF0B0E14),
                            Color(0xFF07080B),
                        ),
                    ),
                ),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .background(
                    Brush.radialGradient(
                        colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                    ),
                ),
        )
    }
}

private fun saturationMatrix(amount: Float): ColorMatrix {
    return ColorMatrix().apply { setToSaturation(amount) }
}
