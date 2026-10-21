package com.folio.launcher.data

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

        // One app per category, so a second messenger can't push the browser off the rail.
        fun pickOne(known: List<String>, intent: Intent) = add(choose(byPkg.keys, resolve(pm, intent), known))

        pickOne(PHONE, Intent(Intent.ACTION_DIAL))
        pickOne(MESSAGES, Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        pickOne(CAMERA, Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        pickOne(BROWSER, Intent(Intent.ACTION_VIEW, Uri.parse("https://")))

        if (out.size < 4) {
            val fallbackLabels = listOf("phone", "dialer", "messages", "messaging", "sms", "camera", "chrome", "browser")
            for (app in apps) {
                if (out.size >= 4) break
                if (out.any { it.packageName == app.packageName }) continue
                if (fallbackLabels.any { app.label.lowercase().contains(it) }) out += app
            }
        }
        if (out.size < 4) {
            for (app in apps.sortedWith(compareBy(Ranking.labelOrder()) { it.label })) {
                if (out.size >= 4) break
                if (out.none { it.packageName == app.packageName }) out += app
            }
        }
        return out.take(4)
    }

    /**
     * The phone's default app for a category ([resolved]) when it's on the home screen, else the
     * first [known] one installed. With no default set, [resolved] is the system chooser.
     */
    internal fun choose(installed: Set<String>, resolved: String?, known: List<String>): String? =
        resolved?.takeIf { it in installed } ?: known.firstOrNull { it in installed }

    private fun resolve(pm: PackageManager, intent: Intent): String? {
        return try {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (_: Exception) {
            null
        }
    }
}
