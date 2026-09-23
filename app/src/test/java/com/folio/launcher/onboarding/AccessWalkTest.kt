package com.folio.launcher.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessWalkTest {
    private val none = AccessGrants(dnd = false, usage = false, media = false)

    @Test
    fun startsAtFirstMissing() {
        assertEquals(AccessScreen.Dnd, AccessWalk.next(null, none))
        assertEquals(AccessScreen.Usage, AccessWalk.next(null, none.copy(dnd = true)))
        assertEquals(AccessScreen.Media, AccessWalk.next(null, AccessGrants(dnd = true, usage = true, media = false)))
        assertNull(AccessWalk.next(null, AccessGrants(dnd = true, usage = true, media = true)))
    }

    @Test
    fun movesForwardEvenIfUserSkippedAScreen() {
        // Came back from DND without granting it: go on to usage, don't reopen DND.
        assertEquals(AccessScreen.Usage, AccessWalk.next(AccessScreen.Dnd, none))
        assertEquals(AccessScreen.Media, AccessWalk.next(AccessScreen.Usage, none))
        assertNull(AccessWalk.next(AccessScreen.Media, none))
    }

    @Test
    fun skipsScreensAlreadyGranted() {
        assertEquals(
            AccessScreen.Media,
            AccessWalk.next(AccessScreen.Dnd, AccessGrants(dnd = true, usage = true, media = false)),
        )
        assertNull(AccessWalk.next(AccessScreen.Dnd, AccessGrants(dnd = true, usage = true, media = true)))
    }
}
