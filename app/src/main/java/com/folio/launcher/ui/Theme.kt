package com.folio.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val PrintInk = Color(0xFFE8E0D4)
val PrintMute = Color(0xFF8A847C)
val VoidBlack = Color(0xFF050607)
val ChargeGreen = Color(0xFF6FBE6A)

/**
 * PrintInk's hard drop, no blur: 1.2 dp down, like the playback glyphs'. A text shadow's offset is
 * drawn in pixels, so it is set from the density; a fixed 1.6 px was about half a dp on the S23.
 */
fun printShadow(density: Density): Shadow = Shadow(
    color = Color.Black.copy(alpha = 0.84f),
    offset = Offset(0f, with(density) { 1.2.dp.toPx() }),
    blurRadius = 0f,
)

/** [printShadow] at the screen's density. */
@Composable
@ReadOnlyComposable
fun printShadow(): Shadow = printShadow(LocalDensity.current)

private val FolioColors = darkColorScheme(
    primary = PrintInk,
    onPrimary = VoidBlack,
    background = VoidBlack,
    onBackground = PrintInk,
    surface = Color(0xFF101216),
    onSurface = PrintInk,
)

private val CrispText = PlatformTextStyle(includeFontPadding = false)

val ClockTimeStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Light,
    fontSize = 64.sp,
    letterSpacing = 0.5.sp,
    fontFeatureSettings = "tnum",
    platformStyle = CrispText,
)

val ClockAmPmStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Light,
    fontSize = 13.sp,
    letterSpacing = 1.4.sp,
    platformStyle = CrispText,
)

val ClockDateStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    letterSpacing = 1.4.sp,
    platformStyle = CrispText,
)

val QuoteStyle = TextStyle(
    fontFamily = FontFamily.Serif,
    fontWeight = FontWeight.Normal,
    fontSize = 16.sp,
    lineHeight = 22.sp,
    letterSpacing = 0.sp,
    platformStyle = CrispText,
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both,
    ),
)

val QuoteAuthorStyle = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    letterSpacing = 0.8.sp,
    platformStyle = CrispText,
)

@Composable
fun FolioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FolioColors,
        content = content,
    )
}
