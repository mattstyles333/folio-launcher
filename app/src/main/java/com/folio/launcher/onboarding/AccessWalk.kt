package com.folio.launcher.onboarding

/** System settings screens the one-Allow walk visits, in order. */
enum class AccessScreen { Dnd, Usage, Media }

data class AccessGrants(
    val dnd: Boolean,
    val usage: Boolean,
    val media: Boolean,
) {
    fun has(screen: AccessScreen): Boolean = when (screen) {
        AccessScreen.Dnd -> dnd
        AccessScreen.Usage -> usage
        AccessScreen.Media -> media
    }

    val all: Boolean get() = dnd && usage && media
}

object AccessWalk {
    /**
     * The next screen still missing a grant, strictly after [after] (or from the start).
     * Never goes back: skipping a screen in Settings doesn't loop the walk.
     */
    fun next(after: AccessScreen?, grants: AccessGrants): AccessScreen? {
        val from = after?.let { it.ordinal + 1 } ?: 0
        return AccessScreen.entries.drop(from).firstOrNull { !grants.has(it) }
    }
}
