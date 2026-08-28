package com.pulse.launcher.data

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore

object DefaultApps {
    private val PHONE = listOf(
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.android.dialer",
        "com.android.phone",
    )
    private val MESSAGES = listOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms",
        "org.thoughtcrime.securesms",
    )
    private val CAMERA = listOf(
        "com.google.android.GoogleCamera",
        "com.google.android.apps.camera",
        "com.sec.android.app.camera",
        "com.android.camera2",
        "com.android.camera",
        "org.codeaurora.snapcam",
    )
    private val BROWSER = listOf(
        "com.android.chrome",
        "com.brave.browser",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.duckduckgo.mobile.android",
        "com.android.browser",
    )

    fun pick(apps: List<LaunchableApp>, pm: PackageManager): List<LaunchableApp> {
        val byPkg = apps.groupBy { it.packageName }
        val out = mutableListOf<LaunchableApp>()

        fun add(pkg: String?) {
            val app = pkg?.let { byPkg[it]?.firstOrNull() } ?: return
            if (out.none { it.packageName == app.packageName }) out += app
        }

        add(PHONE.firstOrNull { it in byPkg })
        add(resolve(pm, Intent(Intent.ACTION_DIAL)))

        add(MESSAGES.firstOrNull { it in byPkg })
        add(resolve(pm, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))))

        add(CAMERA.firstOrNull { it in byPkg })
        add(resolve(pm, Intent(MediaStore.ACTION_IMAGE_CAPTURE)))

        add(BROWSER.firstOrNull { it in byPkg })
        add(resolve(pm, Intent(Intent.ACTION_VIEW, Uri.parse("https://"))))

        if (out.size < 4) {
            val fallbackLabels = listOf("phone", "dialer", "messages", "messaging", "sms", "camera", "chrome", "browser")
            for (app in apps) {
                if (out.size >= 4) break
                if (out.any { it.packageName == app.packageName }) continue
                if (fallbackLabels.any { app.label.lowercase().contains(it) }) out += app
            }
        }
        if (out.size < 4) {
            for (app in apps.sortedBy { it.label.lowercase() }) {
                if (out.size >= 4) break
                if (out.none { it.packageName == app.packageName }) out += app
            }
        }
        return out.take(4)
    }

    private fun resolve(pm: PackageManager, intent: Intent): String? {
        return try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
