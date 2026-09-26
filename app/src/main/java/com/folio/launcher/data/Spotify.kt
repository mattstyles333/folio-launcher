package com.folio.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri

object Spotify {
    const val PACKAGE = "com.spotify.music"

    /** Spotify's search for [term]; the app itself if the deep link doesn't resolve. */
    fun search(host: Context, term: String): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val deep = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(term)))
            .setPackage(PACKAGE)
            .addFlags(flags)
        if (start(host, deep)) return true
        val launch = host.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(flags) ?: return false
        return start(host, launch)
    }

    private fun start(host: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(host.packageManager) == null) return false
        return runCatching { host.startActivity(intent); true }.getOrDefault(false)
    }
}
