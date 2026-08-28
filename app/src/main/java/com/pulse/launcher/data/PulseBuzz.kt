package com.pulse.launcher.data

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager

object PulseBuzz {
    fun play(context: Context) {
        runCatching {
            val vibrator = context.getSystemService(VibratorManager::class.java)?.defaultVibrator
                ?: return
            if (!vibrator.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(
                longArrayOf(0, 34, 42, 34),
                intArrayOf(0, 220, 0, 180),
                -1,
            )
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(
                    effect,
                    VibrationAttributes.Builder()
                        .setUsage(VibrationAttributes.USAGE_TOUCH)
                        .build(),
                )
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(
                    effect,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            }
        }
    }
}
