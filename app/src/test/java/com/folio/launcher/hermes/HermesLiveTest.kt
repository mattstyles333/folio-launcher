package com.folio.launcher.hermes

import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.Signature
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

    /** Installs Hermes Live signed with [key]; Folio is signed with [FOLIO_KEY], as the release key signs both. */
    @Suppress("DEPRECATION") // Robolectric's checkSignatures compares the legacy signatures field.
    private fun installHermesLive(key: String = FOLIO_KEY) {
        val pm = shadowOf(app.packageManager)
        pm.getInternalMutablePackageInfo(app.packageName).apply {
            signatures = arrayOf(Signature(FOLIO_KEY))
            signingInfo = null
        }
        pm.installPackage(
            PackageInfo().apply {
                packageName = HermesLive.PACKAGE
                signatures = arrayOf(Signature(key))
            },
        )
    }

    @Test
    fun installedWithoutAnAnswer_isSignedOut() {
        installHermesLive()
        assertTrue(HermesLive.isInstalled(app))
        val connection = HermesLive.query(app)
        assertEquals(HermesAvailability.SignedOut, HermesLive.availability(true, connection))
    }

    @Test
    fun anAppWithHermesLivesNameButAnotherKey_isNotHermes() {
        installHermesLive(key = "0badc0de")
        assertFalse(HermesLive.isInstalled(app))
        // Whatever its provider hands over, Folio treats Hermes as absent.
        val usable = HermesConnection(signedIn = true, baseUrl = "https://hermes.example.com", accessToken = "fake-token", expiresAtMs = 0)
        assertEquals(HermesAvailability.NotInstalled, HermesLive.availability(HermesLive.isInstalled(app), usable))
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
        // It guards Hermes Live's token, and Folio's declaration is the one in force when Folio is
        // installed first: signature only, or any app could ask for the token.
        val declared = info.permissions.orEmpty().single { it.name == HermesLive.PERMISSION }
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, declared.protection)
    }

    private companion object {
        /** A stand-in for the Folio release key's certificate (hex). */
        const val FOLIO_KEY = "f0110f0110"
    }
}
