package com.folio.launcher.hermes

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import com.folio.hermes.HermesEndpoint

/** Is Hermes usable from Folio: only with Hermes Live installed and signed in. */
enum class HermesAvailability {
    NotInstalled,
    SignedOut,

    /** Signed in, but Hermes Live can't reach the server (down, or off the tailnet). */
    Unreachable,
    Ready,
    ;

    /** Installed and signed in: Hermes stays the Ask provider while its server is out of reach. */
    val signedIn: Boolean get() = this == Ready || this == Unreachable
}

/**
 * What Hermes Live's connection provider hands Folio. Lives in memory only: Folio never stores
 * the token. [toString] never prints it.
 */
class HermesConnection(
    val signedIn: Boolean,
    val baseUrl: String,
    val accessToken: String,
    val expiresAtMs: Long,
) {
    /** The server Hermes Live points at, if its address passes [HermesEndpoint.parse]. */
    val endpoint: HermesEndpoint? by lazy {
        (HermesEndpoint.parse(baseUrl) as? HermesEndpoint.Parsed.Valid)?.endpoint
    }

    /** Signed in, with a token and an address Folio may talk to. */
    val usable: Boolean get() = signedIn && accessToken.isNotEmpty() && endpoint != null

    override fun toString(): String =
        "HermesConnection(signedIn=$signedIn, baseUrl=$baseUrl, accessToken=<${accessToken.length} chars>, expiresAtMs=$expiresAtMs)"

    companion object {
        /** Read the provider's reply; null for no reply. */
        fun from(bundle: Bundle?): HermesConnection? {
            bundle ?: return null
            return HermesConnection(
                signedIn = bundle.getBoolean(HermesLive.KEY_SIGNED_IN, false),
                baseUrl = bundle.getString(HermesLive.KEY_BASE_URL).orEmpty(),
                accessToken = bundle.getString(HermesLive.KEY_ACCESS_TOKEN).orEmpty(),
                expiresAtMs = bundle.getLong(HermesLive.KEY_EXPIRES_AT_MS, 0L),
            )
        }
    }
}

/**
 * The Folio ↔ Hermes Live interface (contract `hermes-voice-contract-2026-10-12.md`).
 * Hermes credentials come only from Hermes Live's signature-guarded provider.
 */
object HermesLive {
    const val PACKAGE = "com.folio.hermeslive"
    const val PERMISSION = "com.folio.permission.HERMES"
    const val AUTHORITY = "com.folio.hermeslive.connection"
    const val METHOD_CONNECTION = "connection"
    const val ACTION_CALL = "com.folio.hermeslive.action.CALL"
    const val EXTRA_PROMPT = "com.folio.hermeslive.extra.PROMPT"

    const val KEY_SIGNED_IN = "signed_in"
    const val KEY_BASE_URL = "base_url"
    const val KEY_ACCESS_TOKEN = "access_token"
    const val KEY_EXPIRES_AT_MS = "expires_at_ms"
    const val EXTRA_FORCE_REFRESH = "force_refresh"

    /** Where the Folio release (and Hermes Live's APK) is published. */
    const val RELEASES_URL = "https://github.com/mattstyles333/folio-launcher/releases"

    val CONNECTION_URI: Uri = "content://$AUTHORITY".toUri()

    fun availability(installed: Boolean, connection: HermesConnection?): HermesAvailability = when {
        !installed -> HermesAvailability.NotInstalled
        connection?.usable == true -> HermesAvailability.Ready
        connection?.signedIn == true -> HermesAvailability.Unreachable
        else -> HermesAvailability.SignedOut
    }

    /**
     * Hermes Live is installed and signed with Folio's own key (the release key signs both). An
     * app that only takes its package name is not Hermes: its provider could name any server.
     */
    fun isInstalled(context: Context): Boolean =
        context.packageManager.checkSignatures(context.packageName, PACKAGE) == PackageManager.SIGNATURE_MATCH

    /**
     * Ask Hermes Live for the connection. IPC that may start Hermes Live's process and refresh its
     * token: call off the main thread, and only after [isInstalled] (this call trusts whichever app
     * answers). Null when it is absent, refuses or answers nothing; never throws.
     */
    fun query(context: Context, forceRefresh: Boolean = false): HermesConnection? = try {
        val extras = if (forceRefresh) Bundle().apply { putBoolean(EXTRA_FORCE_REFRESH, true) } else null
        HermesConnection.from(context.contentResolver.call(CONNECTION_URI, METHOD_CONNECTION, null, extras))
    } catch (e: Exception) {
        // IllegalArgumentException (unknown authority), SecurityException (Hermes Live turned the
        // caller down), or Hermes Live dying mid-call. All mean "not available now".
        null
    }

    /**
     * Get [observer] called when Hermes Live signs in or out. False when Android refuses: it
     * throws SecurityException for a provider that isn't installed, isn't signed by our key, or
     * that Folio may not read. Never throws.
     */
    fun observe(context: Context, observer: ContentObserver): Boolean = try {
        context.contentResolver.registerContentObserver(CONNECTION_URI, true, observer)
        true
    } catch (e: SecurityException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    /** Start a Hermes Live voice call, with [prompt] as its first request when not blank. */
    fun callIntent(prompt: String): Intent = Intent(ACTION_CALL)
        .setPackage(PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .apply {
            val text = prompt.trim()
            if (text.isNotEmpty()) putExtra(EXTRA_PROMPT, text)
        }

    fun startCall(context: Context, prompt: String): Boolean = start(context, callIntent(prompt))

    /** Open Hermes Live itself (to sign in, or see its settings). */
    fun open(context: Context): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        return start(context, launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Where to get Hermes Live: the Folio release page, in the browser. */
    fun openReleases(context: Context): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, RELEASES_URL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun start(context: Context, intent: Intent): Boolean =
        runCatching { context.startActivity(intent); true }.getOrDefault(false)
}
