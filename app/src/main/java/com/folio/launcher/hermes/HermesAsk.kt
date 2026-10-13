package com.folio.launcher.hermes

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayEvent
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesEndpoint
import com.folio.hermes.HermesGateway
import com.folio.hermes.SessionOptions
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient

/** Builds the gateway; tests swap it to prove nothing dials while Hermes is off. */
typealias GatewayFactory = (HermesConfig, AccessTokenProvider, CoroutineScope) -> HermesGateway

/**
 * Folio's text Ask with Hermes: one Hermes session of its own (its stored id kept in Prefs),
 * streamed into [state]. The gateway is connected only between [attach] and [detach], which
 * follow the overlay being on screen and Folio being started.
 *
 * Off state: nothing is built or dialled until Hermes Live hands over a usable connection.
 * Every request goes to the base URL Hermes Live gives, with its token, and nowhere else.
 *
 * Call from the main thread.
 */
class HermesAsk(
    parent: CoroutineScope,
    /** Hermes Live's connection, read off the main thread by the caller's lambda. */
    private val connection: suspend () -> HermesConnection?,
    /** The same, but Hermes Live renews its token first (after the server refused one). */
    private val renewedConnection: suspend () -> HermesConnection? = connection,
    private val loadSessionId: suspend () -> String,
    private val saveSessionId: suspend (String) -> Unit,
    private val gatewayFactory: GatewayFactory = ::defaultGateway,
) {
    private val _state = MutableStateFlow(HermesAskState())
    val state: StateFlow<HermesAskState> = _state.asStateFlow()

    // Folio is the home app: an escaped error here must not crash-loop the phone.
    private val handler = CoroutineExceptionHandler { _, e ->
        _state.update { it.copy(working = null, error = HermesAskReducer.plain(e)) }
    }
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + handler)
    private val setup = Mutex()

    private class Link(val endpoint: HermesEndpoint, val config: HermesConfig, val gateway: HermesGateway) {
        lateinit var conversation: HermesConversation
    }

    private var link: Link? = null
    private var attached = false
    private var attachJob: Job? = null
    private var turnJob: Job? = null
    private var nextId = 1L

    /** The overlay is showing: connect, listen for approvals, and re-bind a stored session. */
    fun attach() {
        if (attached) return
        attached = true
        attachJob = scope.launch {
            val l = prepare() ?: return@launch
            l.gateway.connect()
            launch {
                l.gateway.events.collect { event ->
                    when (event) {
                        is GatewayEvent.ApprovalRequested -> _state.update { HermesAskReducer.request(it, event.request) }
                        is GatewayEvent.ClarifyRequested -> _state.update { HermesAskReducer.request(it, event.request) }
                        is GatewayEvent.RequestCancelled -> _state.update { HermesAskReducer.withdrawn(it, event.id) }
                        else -> Unit
                    }
                }
            }
            launch {
                l.gateway.connection.collect { c -> _state.update { it.copy(error = HermesAskReducer.connectionLine(c)) } }
            }
            // Resuming re-delivers approvals left open when the overlay last closed.
            if (l.conversation.storedSessionId != null) {
                try {
                    l.conversation.ensureSession()
                } catch (e: IOException) {
                    // Shown by the connection line, or by the next question.
                }
            }
        }
    }

    /** The overlay went away (or Folio stopped): drop the socket. A running turn goes on server-side. */
    fun detach() {
        if (!attached) return
        attached = false
        attachJob?.cancel()
        attachJob = null
        if (turnJob?.isActive == true) _state.update(HermesAskReducer::closed)
        turnJob?.cancel()
        turnJob = null
        link?.gateway?.disconnect()
    }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        val interruptFirst = turnJob?.isActive == true
        turnJob?.cancel()
        val id = nextId++
        _state.update { HermesAskReducer.asked(it, id, question) }
        turnJob = scope.launch {
            val l = prepare() ?: run {
                _state.update { HermesAskReducer.failed(it, id, LiveTokenProvider.SIGNED_OUT) }
                return@launch
            }
            try {
                l.conversation.turn(question, interruptFirst = interruptFirst).collect { update ->
                    _state.update { HermesAskReducer.reduce(it, id, update) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { HermesAskReducer.failed(it, id, HermesAskReducer.plain(e)) }
            }
            if (_state.value.exchanges.any { it.id == id && it.status == ExchangeStatus.Running }) {
                _state.update { HermesAskReducer.failed(it, id, HermesAskReducer.REACH) }
            }
        }
    }

    /** Stop button: interrupt the turn; if Hermes can't be told, stop listening. */
    fun stop() {
        val l = link
        scope.launch {
            val sent = l?.conversation?.interrupt() == true
            if (!sent) {
                turnJob?.cancel()
                _state.update { s ->
                    s.copy(
                        working = null,
                        exchanges = s.exchanges.map {
                            if (it.status == ExchangeStatus.Running) it.copy(status = ExchangeStatus.Stopped, note = "Stopped.") else it
                        },
                    )
                }
            }
        }
    }

    /** "New": forget the stored session; the next question starts a fresh one. */
    fun newSession() {
        val l = link
        val wasRunning = turnJob?.isActive == true
        turnJob?.cancel()
        turnJob = null
        _state.value = HermesAskState()
        scope.launch {
            if (l != null) {
                if (wasRunning) l.conversation.interrupt()
                l.conversation = conversation(l, stored = null)
            }
            saveSessionId("")
        }
    }

    fun approve(choice: ApprovalChoice) {
        val request = _state.value.approval ?: return
        val l = link ?: return
        _state.update { it.copy(approval = null) }
        scope.launch {
            try {
                l.gateway.answerApproval(request, choice)
            } catch (e: IOException) {
                _state.update { it.copy(approval = request, error = "Couldn’t send that to Hermes. Try again.") }
            }
        }
    }

    /** Answers keyed by question id; null skips that question. */
    fun clarify(answers: Map<String, String?>) {
        val request: ClarifyRequest = _state.value.clarify ?: return
        val l = link ?: return
        _state.update { it.copy(clarify = null) }
        scope.launch {
            try {
                l.gateway.answerClarify(request, answers)
            } catch (e: IOException) {
                _state.update { it.copy(clarify = request, error = "Couldn’t send that to Hermes. Try again.") }
            }
        }
    }

    /** The view model is going away. */
    fun close() {
        detach()
        link?.gateway?.disconnect()
        link = null
    }

    /** The gateway for Hermes Live's current server, built on first need; null when Hermes is off. */
    private suspend fun prepare(): Link? = setup.withLock {
        val endpoint = connection()?.takeIf { it.usable }?.endpoint
        if (endpoint == null) {
            _state.update { it.copy(error = LiveTokenProvider.SIGNED_OUT) }
            return null
        }
        link?.let { existing ->
            if (existing.endpoint == endpoint) return existing
            existing.gateway.disconnect()
        }
        val config = HermesConfig(endpoint = endpoint)
        val l = Link(endpoint, config, gatewayFactory(config, LiveTokenProvider(endpoint, connection, renewedConnection), scope))
        val stored = loadSessionId().ifEmpty { null }
        l.conversation = conversation(l, stored)
        _state.update { it.copy(hasSession = stored != null, error = null) }
        link = l
        l
    }

    private fun conversation(l: Link, stored: String?) = HermesConversation(
        gateway = l.gateway,
        config = l.config,
        options = SessionOptions(title = "Folio Ask"),
        storedSessionId = stored,
        onStoredSessionChanged = { id ->
            _state.update { it.copy(hasSession = true) }
            scope.launch { saveSessionId(id) }
        },
    )

    companion object {
        private val http by lazy { OkHttpClient() }

        private fun defaultGateway(config: HermesConfig, tokens: AccessTokenProvider, scope: CoroutineScope) =
            HermesGateway(config, tokens, http, scope)
    }
}
