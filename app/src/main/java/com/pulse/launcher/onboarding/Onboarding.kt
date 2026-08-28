package com.pulse.launcher.onboarding

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
    onSetDefault: () -> Unit,
    onSkipRole: () -> Unit,
    onPickPhoto: () -> Unit,
    onUseBing: () -> Unit,
    onUseSystem: () -> Unit,
    onSkipWallpaper: () -> Unit,
    onOpenDnd: () -> Unit,
    onOpenUsage: () -> Unit,
    onSkipAccess: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(VoidBlack.copy(alpha = 0.88f))
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
                Text(
                    text = when (step) {
                        OnboardingStep.Role -> "Idle is a print. Make it Home."
                        OnboardingStep.Wallpaper -> "Choose a print."
                        OnboardingStep.Access -> "Two grants. Skip either — they're in Settings."
                    },
                    color = PrintInk.copy(alpha = 0.55f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Light,
                )
            }
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                when (step) {
                    OnboardingStep.Role -> {
                        PrimaryButton("Set as default launcher", accent, onSetDefault)
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "Skip",
                            color = PrintInk.copy(alpha = 0.45f),
                            modifier = Modifier.clickable(onClick = onSkipRole),
                            fontSize = 14.sp,
                        )
                    }
                    OnboardingStep.Wallpaper -> {
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
                    OnboardingStep.Access -> {
                        PrimaryButton(
                            if (hasDndAccess) "Silent can mute" else "Let Silent actually mute",
                            accent,
                            onOpenDnd,
                            filled = !hasDndAccess,
                        )
                        Spacer(Modifier.height(12.dp))
                        PrimaryButton(
                            if (hasUsageAccess) "Ranking from last 30 days" else "Rank apps from how you use them",
                            accent.copy(alpha = 0.85f),
                            onOpenUsage,
                            filled = !hasUsageAccess,
                        )
                        Spacer(Modifier.height(14.dp))
                        Text(
                            if (hasDndAccess && hasUsageAccess) "Continue" else "Skip",
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
