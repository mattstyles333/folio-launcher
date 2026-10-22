package com.folio.launcher.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** PrefsStore on a store file of its own: a damaged file, and a blob this build can't wholly read. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PrefsStoreTest {
    @get:Rule
    val dir = TemporaryFolder()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val blob = stringPreferencesKey("prefs_json")
    private val kept = stringPreferencesKey("prefs_json_bad")
    private val key = "sk-or-v1-" + "a".repeat(64)

    @After
    fun close() = scope.cancel()

    /** Handled as Folio's own prefs file is, when it can't be parsed. */
    private fun dataStore(file: File = File(dir.root, "folio_prefs.preferences_pb")): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            corruptionHandler = startOver("prefs.corrupt"),
            scope = scope,
            produceFile = { file },
        )

    @Test
    fun aDamagedFile_startsOverInsteadOfCrashingHome() = runBlocking {
        // Flash damage, say: the file isn't a preferences proto any more.
        val file = File(dir.root, "folio_prefs.preferences_pb").apply { writeText("not a proto") }
        val prefs = PrefsStore.on(dataStore(file))
        assertEquals(Prefs(), prefs.data.first())
        // And it takes writes again.
        prefs.update { it.copy(showClock = false) }
        assertFalse(prefs.data.first().showClock)
    }

    @Test
    fun aFieldThisBuildCannotRead_keepsTheRest_andTheBlobAsItWas() = runBlocking {
        val ds = dataStore()
        // A later Folio stores the accent as hex; this one reads an Int.
        val raw = """{"accent":"#FFC4B8A8","openRouterKey":"$key","hiddenPackages":["com.hidden"],""" +
            """"slots":[{"packageName":"com.pin","activityName":"com.pin.Main","pinned":true}],"bingId":"LakeMagadi"}"""
        ds.edit { it[blob] = raw }
        val prefs = PrefsStore.on(ds)

        val read = prefs.data.first()
        assertEquals(key, read.openRouterKey)
        assertEquals(listOf("com.hidden"), read.hiddenPackages)
        assertEquals(DEFAULT_ACCENT, read.accent)

        // The first write after start (first-seen apps, say) used to save the defaults over all of it.
        prefs.update { it.copy(quoteSalt = 3) }
        val after = prefs.data.first()
        assertEquals(key, after.openRouterKey)
        assertEquals(listOf("com.hidden"), after.hiddenPackages)
        assertEquals(SlotPref("com.pin", "com.pin.Main", pinned = true), after.slots.first())
        assertEquals("LakeMagadi", after.bingId)
        assertEquals(3, after.quoteSalt)
        // The blob as it was, for a build that can read it.
        assertEquals(raw, ds.data.first()[kept])
    }

    @Test
    fun anExplicitNull_readsAsTheDefault_withNothingKept() = runBlocking {
        val ds = dataStore()
        ds.edit { it[blob] = """{"aiPackage":null,"openRouterKey":"$key"}""" }
        val prefs = PrefsStore.on(ds)
        prefs.update { it.copy(quoteSalt = 1) }
        val after = prefs.data.first()
        assertEquals("", after.aiPackage)
        assertEquals(key, after.openRouterKey)
        assertNull(ds.data.first()[kept])
    }

    @Test
    fun removingTheKey_removesTheKeptBlobsCopyToo() = runBlocking {
        val ds = dataStore()
        ds.edit { it[blob] = """{"accent":"#FFC4B8A8","openRouterKey":"$key"}""" }
        val prefs = PrefsStore.on(ds)
        prefs.update { it }
        assertTrue(ds.data.first()[kept].orEmpty().contains(key))
        // Settings › Jev, hold to remove: no copy of the key stays behind.
        prefs.update { it.copy(openRouterKey = "") }
        assertNull(ds.data.first()[kept])
    }
}
