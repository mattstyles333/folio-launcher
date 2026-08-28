package com.folio.launcher.home

import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.folio.launcher.R
import com.folio.launcher.data.RingerVisual
import com.folio.launcher.ui.VoidBlack
import kotlin.math.hypot
import kotlin.math.max

/** Circular develop of [target] from [origin], [progress] 0→1. */
data class GradeReveal(
    val origin: Offset,
    val progress: Float,
    val target: RingerVisual,
)

@Composable
fun WallpaperLayer(
    photo: ImageBitmap?,
    blurred: ImageBitmap?,
    mode: RingerVisual,
    accent: Color,
    parallax: ParallaxState? = null,
    reveal: GradeReveal? = null,
    modifier: Modifier = Modifier,
) {
    val resources = LocalContext.current.resources
    val grain = remember(resources) {
        BitmapFactory.decodeResource(resources, R.drawable.grain).asImageBitmap()
    }
    val halfColor = remember { ColorFilter.colorMatrix(saturationMatrix(0.5f)) }
    val blackWhite = remember { ColorFilter.colorMatrix(saturationMatrix(0f)) }
    val developing = reveal?.takeIf { it.progress > 0.001f }
    Box(modifier.fillMaxSize().background(VoidBlack)) {
        if (developing != null) {
            PrintForMode(
                photo = photo,
                accent = accent,
                mode = mode,
                grain = grain,
                halfColor = halfColor,
                blackWhite = blackWhite,
                parallax = parallax,
            )
            DevelopingPrint(
                photo = photo,
                accent = accent,
                mode = developing.target,
                grain = grain,
                halfColor = halfColor,
                blackWhite = blackWhite,
                parallax = parallax,
                origin = developing.origin,
                progress = developing.progress,
            )
            DevelopIris(
                origin = developing.origin,
                progress = developing.progress,
                accent = accent,
            )
        } else {
            Crossfade(targetState = mode, animationSpec = tween(320), label = "mode") { current ->
                PrintForMode(
                    photo = photo,
                    accent = accent,
                    mode = current,
                    grain = grain,
                    halfColor = halfColor,
                    blackWhite = blackWhite,
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
private fun PrintForMode(
    photo: ImageBitmap?,
    accent: Color,
    mode: RingerVisual,
    grain: ImageBitmap,
    halfColor: ColorFilter,
    blackWhite: ColorFilter,
    parallax: ParallaxState?,
) {
    when (mode) {
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

@Composable
private fun DevelopingPrint(
    photo: ImageBitmap?,
    accent: Color,
    mode: RingerVisual,
    grain: ImageBitmap,
    halfColor: ColorFilter,
    blackWhite: ColorFilter,
    parallax: ParallaxState?,
    origin: Offset,
    progress: Float,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val radius = farthestCorner(origin, size) * progress
        Box(
            Modifier
                .fillMaxSize()
                .clip(ExpandingCircle(origin, radius))
                .graphicsLayer {
                    val w = this.size.width.coerceAtLeast(1f)
                    val h = this.size.height.coerceAtLeast(1f)
                    transformOrigin = TransformOrigin(origin.x / w, origin.y / h)
                    val bloom = 1f + 0.04f * (1f - progress)
                    scaleX = bloom
                    scaleY = bloom
                },
        ) {
            PrintForMode(
                photo = photo,
                accent = accent,
                mode = mode,
                grain = grain,
                halfColor = halfColor,
                blackWhite = blackWhite,
                parallax = parallax,
            )
        }
    }
}

@Composable
private fun DevelopIris(
    origin: Offset,
    progress: Float,
    accent: Color,
) {
    val density = LocalDensity.current
    Canvas(Modifier.fillMaxSize()) {
        if (progress <= 0.001f) return@Canvas
        val radius = farthestCorner(origin, size) * progress
        val edge = (1f - progress).coerceIn(0f, 1f)
        if (progress < 0.22f) {
            val spark = 1f - progress / 0.22f
            drawCircle(
                color = Color.White.copy(alpha = 0.32f * spark),
                radius = with(density) { 22.dp.toPx() } * (0.6f + progress * 3.4f),
                center = origin,
            )
        }
        if (progress < 0.97f) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f + 0.18f * edge),
                radius = radius,
                center = origin,
                style = Stroke(width = with(density) { 7.dp.toPx() } * (0.45f + edge)),
            )
            drawCircle(
                color = accent.copy(alpha = 0.22f + 0.50f * edge),
                radius = radius,
                center = origin,
                style = Stroke(width = with(density) { 1.6.dp.toPx() }),
            )
        }
    }
}

private data class ExpandingCircle(
    val origin: Offset,
    val radius: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val path = Path().apply {
            addOval(
                Rect(
                    left = origin.x - radius,
                    top = origin.y - radius,
                    right = origin.x + radius,
                    bottom = origin.y + radius,
                ),
            )
        }
        return Outline.Generic(path)
    }
}

private fun farthestCorner(origin: Offset, size: Size): Float {
    val dx = max(origin.x, size.width - origin.x)
    val dy = max(origin.y, size.height - origin.y)
    return hypot(dx, dy)
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
