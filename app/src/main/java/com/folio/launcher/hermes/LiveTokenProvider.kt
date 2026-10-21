package com.folio.launcher.hermes

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.GatewayUnavailableException
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesEndpoint

/**
 * The bearer for [endpoint], fetched from Hermes Live on every request (nothing cached, nothing on
 * disk). Hermes Live refreshes its token when it has under five minutes left.
 *
 * A token is only handed out while Hermes Live still points at [endpoint]: if the user moved it
 * to another server, that server's token is never sent to this one.
 */
class LiveTokenProvider(
    private val endpoint: HermesEndpoint,
    private val source: suspend () -> HermesConnection?,
    /** [source] with Hermes Live renewing its token first (`force_refresh`). */
    private val renewed: suspend () -> HermesConnection? = source,
) : AccessTokenProvider {
    override suspend fun accessToken(): String = tokenFrom(source())

    private fun tokenFrom(found: HermesConnection?): String {
        // No answer (Hermes Live restarting), or signed in with no token right now (offline, server
        // down): out of reach, so the gateway backs off and tries again. Only signed out stops it.
        val connection = found ?: throw GatewayUnavailableException(NO_TOKEN)
        if (!connection.signedIn) throw HermesAuthException.SignInRequired(SIGNED_OUT)
        if (connection.accessToken.isEmpty()) throw GatewayUnavailableException(NO_TOKEN)
        if (connection.endpoint != endpoint) throw HermesAuthException.SignInRequired(MOVED)
        return connection.accessToken
    }

    /**
     * Ask Hermes Live to renew its token; if it still hands out the token the server refused,
     * stop (the gateway then stops instead of looping).
     */
    override suspend fun refreshAfterUnauthorized(rejected: String): String {
        val token = tokenFrom(renewed())
        if (token == rejected) throw HermesAuthException.SignInRequired(REFUSED)
        return token
    }

    companion object {
        const val SIGNED_OUT = "Hermes Live is signed out. Open it to sign in."
        const val MOVED = "Hermes Live now points at another server. Close Ask and open it again."
        const val REFUSED = "Your Hermes server turned down Hermes Live's session. Open Hermes Live to renew it."
        private const val NO_TOKEN = "Hermes Live has no token for Hermes right now."
    }
}
