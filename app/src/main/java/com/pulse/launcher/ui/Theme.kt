package com.pulse.launcher.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

val PrintInk = Color(0xFFE8E0D4)
val PrintMute = Color(0xFF8A847C)
val VoidBlack = Color(0xFF050607)

private val PulseColors = darkColorScheme(
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
fun PulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = PulseColors,
        content = content,
    )
}
