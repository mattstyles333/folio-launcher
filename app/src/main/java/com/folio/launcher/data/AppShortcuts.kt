package com.folio.launcher.data

import android.os.UserHandle
import androidx.compose.ui.graphics.ImageBitmap

/** One of an app's own shortcuts (Chrome's New incognito tab, a recent chat), for its long-press menu. */
data class AppShortcut(
    val packageName: String,
    val id: String,
    /** The short label: shown when [longLabel] doesn't fit on the row, or there is none. */
    val label: String,
    val user: UserHandle,
    val longLabel: String? = null,
    /** Drawn at [AppShortcuts.ICON_DP]; null when the app gave it none, or it couldn't be drawn. */
    val icon: ImageBitmap? = null,
)

object AppShortcuts {
    /** Rows above App info. */
    const val MAX = 4

    /** The menu's icon size, drawn 1:1. */
    const val ICON_DP = 24

    /**
     * The shortcuts a long-press shows, in Launcher3's order: enabled ones only, those the app declares
     * in its manifest first, then the ones it publishes as it runs, each by the rank the app gave it
     * (ties keep the system's order); four at most.
     */
    fun <T> pick(
        shortcuts: List<T>,
        enabled: (T) -> Boolean,
        declaredInManifest: (T) -> Boolean,
        rank: (T) -> Int,
    ): List<T> = shortcuts
        .filter(enabled)
        .sortedWith(compareBy<T>({ !declaredInManifest(it) }, { rank(it) }))
        .take(MAX)
}
