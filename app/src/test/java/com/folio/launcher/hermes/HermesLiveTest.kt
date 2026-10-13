package com.folio.launcher.hermes

import android.content.pm.PackageInfo
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class HermesLiveTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun notInstalled_isOffAndAsksNothing() {
        assertFalse(HermesLive.isInstalled(app))
        assertNull(HermesLive.query(app))
        assertEquals(HermesAvailability.NotInstalled, HermesLive.availability(false, null))
    }

    @Test
    fun installedWithoutAnAnswer_isSignedOut() {
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = HermesLive.PACKAGE })
        assertTrue(HermesLive.isInstalled(app))
        val connection = HermesLive.query(app)
        assertEquals(HermesAvailability.SignedOut, HermesLive.availability(true, connection))
    }

    @Test
    fun readsTheProviderBundle() {
        val bundle = Bundle().apply {
            putBoolean("signed_in", true)
            putString("base_url", "https://hermes.example.com")
            putString("access_token", "fake-token")
            putLong("expires_at_ms", 42L)
        }
        val c = HermesConnection.from(bundle)!!
        assertTrue(c.signedIn)
        assertEquals("fake-token", c.accessToken)
        assertEquals(42L, c.expiresAtMs)
        assertEquals("hermes.example.com", c.endpoint?.host)
        assertEquals(HermesAvailability.Ready, HermesLive.availability(true, c))
        assertEquals(HermesAvailability.SignedOut, HermesLive.availability(true, HermesConnection.from(Bundle())))
        // Hermes Live's answer when signed in but the server is down: an error, no token.
        val down = Bundle().apply {
            putBoolean("signed_in", true)
            putString("base_url", "https://hermes.example.com")
            putString("error", "Can't reach Hermes")
        }
        assertEquals(HermesAvailability.Unreachable, HermesLive.availability(true, HermesConnection.from(down)))
        assertNull(HermesConnection.from(null))
    }

    @Test
    fun callIntent_isExplicitAndCarriesTheTypedPrompt() {
        val bare = HermesLive.callIntent("  ")
        assertEquals("com.folio.hermeslive.action.CALL", bare.action)
        assertEquals("com.folio.hermeslive", bare.`package`)
        assertFalse(bare.hasExtra(HermesLive.EXTRA_PROMPT))
        val typed = HermesLive.callIntent(" book a table ")
        assertEquals("book a table", typed.getStringExtra("com.folio.hermeslive.extra.PROMPT"))
    }

    @Test
    fun manifest_declaresAndUsesTheHermesPermission() {
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertTrue(info.requestedPermissions.orEmpty().contains(HermesLive.PERMISSION))
    }
}
