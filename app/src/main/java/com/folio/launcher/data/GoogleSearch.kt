package com.folio.launcher.data

import android.content.Context
import android.content.Intent
import android.net.Uri

object GoogleSearch {
    const val PACKAGE = "com.google.android.googlequicksearchbox"

    fun open(host: Context): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        val launch = host.packageManager.getLaunchIntentForPackage(PACKAGE)?.addFlags(flags)
        if (launch != null && start(host, launch)) return true
        val web = Intent(Intent.ACTION_WEB_SEARCH).addFlags(flags)
        if (start(host, web)) return true
        val site = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).addFlags(flags)
        return start(host, site)
    }

    private fun start(host: Context, intent: Intent): Boolean {
        if (intent.resolveActivity(host.packageManager) == null) return false
        return runCatching { host.startActivity(intent); true }.getOrDefault(false)
    }
}
