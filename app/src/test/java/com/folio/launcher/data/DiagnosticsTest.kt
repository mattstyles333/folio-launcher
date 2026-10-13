package com.folio.launcher.data

import com.folio.launcher.BuildConfig
import com.folio.launcher.FolioApp
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test fun onByDefaultIncludingOldPrefs() {
        assertTrue(Prefs().diagnostics)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString<Prefs>("""{"showClock":false}""")
        assertTrue(old.diagnostics)
    }

    @Test fun offWithoutABuiltInUrlAndPrefsAreNotRead() {
        var asked = false
        assertNull(FolioApp.diagnosticsEndpoint("") { asked = true; true })
        assertFalse(asked)
        assertEquals("debug builds and unit tests carry no endpoint", "", BuildConfig.DIAGNOSTICS_URL)
    }

    @Test fun offWhenSwitchedOffEvenWithAUrl() {
        assertNull(FolioApp.diagnosticsEndpoint("https://logs.example.com") { false })
    }

    @Test fun onWithAUrlAndTheSwitch() {
        assertEquals("https://logs.example.com", FolioApp.diagnosticsEndpoint("https://logs.example.com") { true })
    }
}
