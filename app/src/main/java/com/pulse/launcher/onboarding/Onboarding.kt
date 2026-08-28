package com.pulse.launcher.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulse.launcher.data.OnboardingStep
import com.pulse.launcher.ui.PrintInk
import com.pulse.launcher.ui.VoidBlack

@Composable
fun Onboarding(
    step: OnboardingStep,
    accent: Color,
    systemWallpaperReadable: Boolean,
    hasDndAccess: Boolean,
    hasUsageAccess: Boolean,
    hasNowPlayingAccess: Boolean = false,
    wallpaperBusy: Boolean = false,
    onSetDefault: () -> Unit,
    onSkipRole: () -> Unit,
    onPickPhoto: () -> Unit,
    onUseBing: () -> Unit,
    onUseSystem: () -> Unit,
    onSkipWallpaper: () -> Unit,
    onAllowAccess: () -> Unit,
    onSkipAccess: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(VoidBlack.copy(alpha = 0.72f))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 36.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    "Pulse",
                    color = PrintInk,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Light,
                    fontSize = 36.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(10.dp))
                AnimatedContent(
                    targetState = step,
                    transitionSpec = { fadeIn(tween(240)) togetherWith fadeOut(tween(160)) },
                    label = "onboardCopy",
                ) { current ->
                    Text(
                        text = when (current) {
                            OnboardingStep.Role -> "Idle is a print. Make it Home."
                            OnboardingStep.Wallpaper ->
                                if (wallpaperBusy) "Today's print is on the way." else "Choose a print."
                            OnboardingStep.Access ->
                                "Find Pulse in the next list, switch it on, press Back. Silent, ranking, then Spotify."
                        },
                        color = PrintInk.copy(alpha = 0.58f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Light,
                    )
                }
            }
            AnimatedContent(
                targetState = step,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
                label = "onboardActions",
                modifier = Modifier.fillMaxWidth(),
            ) { current ->
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    when (current) {
                        OnboardingStep.Role -> {
                            PrimaryButton("Set as Home", accent, onSetDefault)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                "Skip",
                                color = PrintInk.copy(alpha = 0.45f),
                                modifier = Modifier.clickable(onClick = onSkipRole),
                                fontSize = 14.sp,
                            )
                        }
                        OnboardingStep.Wallpaper -> {
                            if (wallpaperBusy) {
                                Text(
                                    "This only takes a moment.",
                                    color = PrintInk.copy(alpha = 0.42f),
                                    fontSize = 14.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    "Skip",
                                    color = PrintInk.copy(alpha = 0.45f),
                                    modifier = Modifier.clickable(onClick = onSkipWallpaper),
                                    fontSize = 14.sp,
                                )
                            } else {
                                PrimaryButton("Today's Bing print", accent, onUseBing)
                                Spacer(Modifier.height(12.dp))
                                PrimaryButton("Pick a photo", accent.copy(alpha = 0.85f), onPickPhoto, filled = false)
                                if (systemWallpaperReadable) {
                                    Spacer(Modifier.height(12.dp))
                                    PrimaryButton("Use current wallpaper", accent.copy(alpha = 0.85f), onUseSystem, filled = false)
                                }
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Skip",
                                    color = PrintInk.copy(alpha = 0.45f),
                                    modifier = Modifier.clickable(onClick = onSkipWallpaper),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                        OnboardingStep.Access -> {
                            val all = hasDndAccess && hasUsageAccess && hasNowPlayingAccess
                            PrimaryButton(
                                when {
                                    all -> "Continue"
                                    !hasDndAccess -> "Let Silent actually mute"
                                    !hasUsageAccess -> "Rank apps from how you use them"
                                    !hasNowPlayingAccess -> "Show Spotify on the print"
                                    else -> "Continue"
                                },
                                accent,
                                onClick = if (all) onSkipAccess else onAllowAccess,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(
                                accessStatus(hasDndAccess, hasUsageAccess, hasNowPlayingAccess),
                                color = PrintInk.copy(alpha = 0.38f),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                            )
                            if (!all) {
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    "Skip",
                                    color = PrintInk.copy(alpha = 0.45f),
                                    modifier = Modifier.clickable(onClick = onSkipAccess),
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun accessStatus(dnd: Boolean, usage: Boolean, media: Boolean): String {
    val mute = if (dnd) "Silent can mute" else "Silent needs Do Not Disturb"
    val rank = if (usage) "Ranking uses the last 30 days" else "Ranking starts from Pulse until usage access"
    val spotify = if (media) "Spotify can sit on the print" else "Spotify needs notification access"
    return "$mute. $rank. $spotify."
}

@Composable
private fun PrimaryButton(
    label: String,
    accent: Color,
    onClick: () -> Unit,
    filled: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (filled) accent else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (filled) VoidBlack else PrintInk,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}
