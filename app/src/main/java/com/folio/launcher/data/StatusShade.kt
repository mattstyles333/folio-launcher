package com.folio.launcher.data

import android.content.Context

object StatusShade {
    fun expand(context: Context) {
        runCatching {
            @Suppress("WrongConstant")
            val manager = context.getSystemService("statusbar") ?: return
            manager.javaClass.getMethod("expandNotificationsPanel").invoke(manager)
        }
    }
}
