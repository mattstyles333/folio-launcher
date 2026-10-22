package com.folio.launcher.hermes

import android.content.pm.PackageInfo
import android.content.pm.PermissionInfo
import android.content.pm.Signature
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application
import com.folio.launcher.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

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
        // Out of reach (or on plain http) is still signed in, so Hermes stays the Ask provider and says
        // why; only signed out (or not installed) drops it.
        assertEquals(
            listOf(HermesAvailability.Unreachable, HermesAvailability.PlainHttp, HermesAvailability.Ready),
            HermesAvailability.entries.filter { it.signedIn },
        )
    }

    @Test
    fun signedInToPlainHttp_isNeverReady() {
        // A Tailscale name or address on plain http: HermesEndpoint takes it, Folio doesn't, token or not.
        for (base in listOf("http://box.tail1234.ts.net:9119", "http://100.101.102.103:9119")) {
            val plain = Bundle().apply {
                putBoolean("signed_in", true)
                putString("base_url", base)
                putString("access_token", "fake-token")
            }
            assertEquals(base, HermesAvailability.PlainHttp, HermesLive.availability(true, HermesConnection.from(plain)))
            plain.remove("access_token")
            assertEquals(base, HermesAvailability.PlainHttp, HermesLive.availability(true, HermesConnection.from(plain)))
            plain.putBoolean("signed_in", false)
            assertEquals(base, HermesAvailability.SignedOut, HermesLive.availability(true, HermesConnection.from(plain)))
        }
    }

    @Test
    fun resumeAsksAgain_onlyOutOfReachOrSignedOut_atMostEveryFiveMinutes() {
        val five = HermesLive.RESUME_RECHECK_MS
        assertEquals(5 * 60_000L, five)
        // Never asked yet: whatever the default says.
        for (availability in HermesAvailability.entries) {
            assertTrue("$availability", HermesLive.resumeCheckDue(availability, checkedAt = null, now = 5_000))
        }
        // Pushed when it changes (sign-in, sign-out, a package change), and asked as Settings or the overlay opens.
        for (availability in listOf(HermesAvailability.Ready, HermesAvailability.NotInstalled, HermesAvailability.PlainHttp)) {
            assertFalse("$availability", HermesLive.resumeCheckDue(availability, checkedAt = 5_000, now = 5_000 + 10 * five))
        }
        // Can end without a word: asked again, but not on every return home.
        for (availability in listOf(HermesAvailability.Unreachable, HermesAvailability.SignedOut)) {
            assertFalse("$availability", HermesLive.resumeCheckDue(availability, checkedAt = 5_000, now = 15_000))
            assertFalse("$availability", HermesLive.resumeCheckDue(availability, checkedAt = 5_000, now = 4_999 + five))
            assertTrue("$availability", HermesLive.resumeCheckDue(availability, checkedAt = 5_000, now = 5_000 + five))
        }
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

    @Test
    fun noPlainHttpAnywhere_andNoNetworkStateToRead() {
        // The network security config is the base rule alone: no domain gets plain http, ts.net included.
        val parser = app.resources.getXml(R.xml.network_security_config)
        val tags = mutableListOf<String>()
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) tags += parser.name
        }
        assertEquals(listOf("network-security-config", "base-config"), tags)
        // Nothing checks for Tailscale any more, so Folio doesn't ask to see the network.
        val info = app.packageManager.getPackageInfo(app.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
        assertFalse(info.requestedPermissions.orEmpty().contains("android.permission.ACCESS_NETWORK_STATE"))
    }

    private companion object {
        /** A stand-in for the Folio release key's certificate (hex). */
        const val FOLIO_KEY = "f0110f0110"
    }
}
