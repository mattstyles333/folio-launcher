package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which of an app's shortcuts its long-press menu shows, and in what order: Launcher3's. */
class AppShortcutsTest {
    /** A shortcut as the system hands it to Home, down to what the menu orders it by. */
    private data class Published(
        val id: String,
        val manifest: Boolean,
        val rank: Int,
        val enabled: Boolean = true,
    )

    private fun menu(vararg published: Published): List<String> = AppShortcuts.pick(
        published.toList(),
        enabled = { it.enabled },
        declaredInManifest = { it.manifest },
        rank = { it.rank },
    ).map { it.id }

    @Test
    fun manifestShortcutsComeFirst_thenTheAppsOwn_eachByRank() {
        assertEquals(
            listOf("compose", "inbox", "chat:ann", "chat:bo"),
            menu(
                Published("chat:bo", manifest = false, rank = 1),
                Published("inbox", manifest = true, rank = 1),
                Published("chat:ann", manifest = false, rank = 0),
                Published("compose", manifest = true, rank = 0),
            ),
        )
    }

    @Test
    fun fourAtMost_theLowestRanksStay() {
        assertEquals(
            listOf("m0", "m1", "d0", "d1"),
            menu(
                Published("d2", manifest = false, rank = 2),
                Published("d1", manifest = false, rank = 1),
                Published("m1", manifest = true, rank = 1),
                Published("d0", manifest = false, rank = 0),
                Published("m0", manifest = true, rank = 0),
            ),
        )
    }

    @Test
    fun disabledOnesAreDropped_beforeTheFourAreChosen() {
        // A disabled shortcut (the app removed it, or turned it off) never takes a row from one that works.
        assertEquals(
            listOf("m1", "d0", "d1", "d2"),
            menu(
                Published("m0", manifest = true, rank = 0, enabled = false),
                Published("m1", manifest = true, rank = 1),
                Published("d0", manifest = false, rank = 0),
                Published("d1", manifest = false, rank = 1),
                Published("d2", manifest = false, rank = 2),
                Published("d3", manifest = false, rank = 3, enabled = false),
            ),
        )
    }

    @Test
    fun equalRanks_keepTheSystemsOrder() {
        assertEquals(
            listOf("a", "b", "c"),
            menu(
                Published("a", manifest = false, rank = 0),
                Published("b", manifest = false, rank = 0),
                Published("c", manifest = false, rank = 0),
            ),
        )
    }

    @Test
    fun noneWorking_isNone() {
        assertEquals(emptyList<String>(), menu())
        assertEquals(emptyList<String>(), menu(Published("gone", manifest = true, rank = 0, enabled = false)))
    }
}
