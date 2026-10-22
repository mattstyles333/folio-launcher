package com.folio.launcher.hermes

import com.folio.hermes.AccessTokenProvider
import com.folio.hermes.ApprovalChoice
import com.folio.hermes.ClarifyRequest
import com.folio.hermes.GatewayConnection
import com.folio.hermes.GatewayEvent
import com.folio.hermes.HermesAuthException
import com.folio.hermes.HermesConfig
import com.folio.hermes.HermesConversation
import com.folio.hermes.HermesEndpoint
import com.folio.hermes.HermesGateway
import com.folio.hermes.HermesRpcException
import com.folio.hermes.SessionOptions
import com.folio.hermes.TurnStatus
import com.folio.hermes.TurnUpdate
import com.folio.hermes.cascade.ports.GatewaySessionRecovery
import com.folio.hermes.cascade.registry.SessionProbe
import com.folio.hermes.telemetry.Telemetry
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.transformWhile
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
 * Every request goes to the base URL Hermes Live gives, with its token, and nowhere else; never to
 * one on plain http.
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
        Telemetry.error("hermes.ask", if (e is IOException) null else e, askAttrs(e, "uncaught"))
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

    /** The link whose events the overlay hears while attached ([listen]). */
    private var listening: Link? = null
    private var listenJob: Job? = null
    private var turnJob: Job? = null

    /** The exchange [detach] cut off mid-turn: the next attach reads how it ended. */
    private var cut: HermesExchange? = null
    private var nextId = 1L

    /** The overlay is showing: connect, listen for approvals, and re-bind a stored session. */
    fun attach() {
        if (attached) return
        attached = true
        // prepare() starts listening once Hermes Live hands over a connection: now, or at the next question.
        attachJob = scope.launch { prepare() }
    }

    /** The overlay went away (or Folio stopped): drop the socket. A running turn goes on server-side. */
    fun detach() {
        if (!attached) return
        attached = false
        attachJob?.cancel()
        attachJob = null
        listenJob?.cancel()
        listenJob = null
        listening = null
        if (turnJob?.isActive == true) {
            _state.value.exchanges.lastOrNull { it.status == ExchangeStatus.Running }?.let { cut = it }
            _state.update(HermesAskReducer::closed)
        }
        turnJob?.cancel()
        turnJob = null
        // Cards are answered through this socket; the next attach's resume re-delivers those still open.
        _state.update { it.copy(approval = null, clarify = null) }
        link?.gateway?.disconnect()
    }

    fun ask(text: String) {
        val question = text.trim()
        if (question.isEmpty()) return
        val interruptFirst = turnJob?.isActive == true
        turnJob?.cancel()
        cut = null
        val id = nextId++
        _state.update { HermesAskReducer.asked(it, id, question) }
        turnJob = scope.launch {
            val l = prepare { line, type ->
                Telemetry.warn("hermes.ask", null, mapOf("op" to "turn", "type" to type))
                _state.update { HermesAskReducer.failed(it, id, line) }
            } ?: return@launch
            try {
                l.conversation.turn(question, interruptFirst = interruptFirst).collect { update ->
                    if (update is TurnUpdate.Failed) Telemetry.warn("hermes.ask", null, mapOf("op" to "turn", "type" to "turn_failed"))
                    _state.update { HermesAskReducer.reduce(it, id, update) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Telemetry.warn("hermes.ask", if (e is IOException) null else e, askAttrs(e, "turn"))
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
        // What was tapped: an interrupt that fails late must not stop a question asked after it.
        val job = turnJob
        val id = _state.value.exchanges.lastOrNull { it.status == ExchangeStatus.Running }?.id
        scope.launch {
            val sent = l?.conversation?.interrupt() == true
            if (!sent) {
                job?.cancel()
                _state.update { HermesAskReducer.stopped(it, id) }
            }
        }
    }

    /** "New": forget the stored session; the next question starts a fresh one. */
    fun newSession() {
        val l = link
        val wasRunning = turnJob?.isActive == true
        turnJob?.cancel()
        turnJob = null
        cut = null
        _state.value = HermesAskState()
        // Swap now, so a question asked straight after New never lands in the old session. The old
        // id is forgotten before the new session can save its own.
        val old = l?.conversation
        if (l != null) l.conversation = conversation(l, stored = null)
        scope.launch { saveSessionId("") }
        if (wasRunning && old != null) scope.launch { old.interrupt() }
    }

    fun approve(choice: ApprovalChoice) {
        val request = _state.value.approval ?: return
        val l = link ?: return
        _state.update { it.copy(approval = null) }
        scope.launch {
            try {
                l.gateway.answerApproval(request, choice)
            } catch (e: IOException) {
                Telemetry.warn("hermes.ask", null, askAttrs(e, "approval"))
                // Closed meanwhile: the card went with the socket, and the next attach re-delivers it if still open.
                if (attached && link === l) {
                    _state.update { it.copy(approval = request, error = "Couldn’t send that to Hermes. Try again.") }
                }
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
                Telemetry.warn("hermes.ask", null, askAttrs(e, "clarify"))
                if (attached && link === l) {
                    _state.update { it.copy(clarify = request, error = "Couldn’t send that to Hermes. Try again.") }
                }
            }
        }
    }

    /** The view model is going away. */
    fun close() {
        detach()
        link?.gateway?.disconnect()
        link = null
    }

    /**
     * The gateway for Hermes Live's current server, built on first need; null when Hermes is off,
     * with [off] told the line shown and its telemetry type.
     */
    private suspend fun prepare(off: (line: String, type: String) -> Unit = { _, _ -> }): Link? = setup.withLock {
        val c = connection()
        val endpoint = c?.takeIf { it.usable }?.endpoint
        if (endpoint == null) {
            val (line, type) = when {
                // Folio talks to Hermes over https only: for plain http nothing is built or dialled.
                c != null && c.signedIn && c.plainHttp -> LiveTokenProvider.PLAIN_HTTP to "plain_http"
                // Signed in without a token or a usable address: out of reach for now, not signed out.
                c?.signedIn == true -> HermesAskReducer.REACH to "unreachable"
                else -> LiveTokenProvider.SIGNED_OUT to "signed_out"
            }
            _state.update { it.copy(error = line) }
            off(line, type)
            return null
        }
        link?.let { existing ->
            if (existing.endpoint == endpoint) {
                listen(existing)
                return existing
            }
            existing.gateway.disconnect()
        }
        val config = HermesConfig(endpoint = endpoint)
        val tokens = LiveTokenProvider(endpoint, connection, renewedConnection)
        val l = Link(endpoint, config, gatewayFactory(config, tokens, scope))
        val stored = loadSessionId().ifEmpty { null }
        l.conversation = conversation(l, stored)
        _state.update { it.copy(hasSession = stored != null, error = null) }
        link = l
        listen(l)
        l
    }

    /**
     * While attached: connect [l], show its connection line and requests, and re-bind a stored
     * session. Started by whichever [prepare] first finds the link, so an attach that got no
     * connection still listens once a question builds one.
     */
    private fun listen(l: Link) {
        if (!attached || (listening === l && listenJob?.isActive == true)) return
        listenJob?.cancel()
        listening = l
        listenJob = scope.launch {
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
            val stored = l.conversation.storedSessionId ?: return@launch
            try {
                l.conversation.ensureSession()
            } catch (e: IOException) {
                // Shown by the connection line, or by the next question.
                return@launch
            }
            if (l.conversation.storedSessionId == stored) recover(l, stored)
        }
    }

    /**
     * Back after [detach] cut a turn off: read how it went, sending nothing. A finished turn's reply
     * replaces the closed note; one still running is followed to its end.
     */
    private fun recover(l: Link, stored: String) {
        val exchange = cut ?: return
        if (turnJob?.isActive == true) return
        turnJob = scope.launch {
            val probe = try {
                GatewaySessionRecovery(l.gateway, l.config).probe(RECOVERY_TASK, stored)
            } catch (e: IOException) {
                return@launch // The closed note stays; the next attach asks again.
            }
            cut = null
            // The cut question must be the session's last prompt, or the reply found answers another.
            if (probe.prompts?.lastOrNull()?.trim() != exchange.question) return@launch
            when (probe) {
                is SessionProbe.Ended -> if (probe.status == TurnStatus.Complete) {
                    val done = TurnUpdate.Completed(probe.text, probe.status, null)
                    _state.update { HermesAskReducer.reduce(HermesAskReducer.resumed(it, exchange.id), exchange.id, done) }
                }
                is SessionProbe.Running -> follow(l, exchange, probe.updates)
                else -> Unit
            }
        }
    }

    /** A turn still running on return, followed on this socket to its end. */
    private suspend fun follow(l: Link, exchange: HermesExchange, updates: Flow<TurnUpdate>) {
        _state.update { HermesAskReducer.resumed(it, exchange.id) }
        // Its events come through this socket only: a drop ends the follow.
        val generation = (l.gateway.connection.value as? GatewayConnection.Ready)?.generation
        val dropped = l.gateway.connection.filter { !(it is GatewayConnection.Ready && it.generation == generation) }.map { null }
        try {
            merge<TurnUpdate?>(updates, dropped)
                .transformWhile<TurnUpdate?, TurnUpdate> { update ->
                    if (update != null) emit(update)
                    update != null && update !is TurnUpdate.Completed && update !is TurnUpdate.Failed
                }
                .collect { update ->
                    // Deltas sent while Folio was away never arrive: keep the partial reply until Completed.
                    if (update !is TurnUpdate.Text) _state.update { HermesAskReducer.reduce(it, exchange.id, update) }
                }
        } catch (e: IOException) {
            // Lost on the way: back to the closed note below.
        }
        if (_state.value.exchanges.any { it.id == exchange.id && it.status == ExchangeStatus.Running }) {
            cut = exchange
            _state.update(HermesAskReducer::closed)
        }
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

        /** Names the probe; Folio has one session, so one task. */
        private const val RECOVERY_TASK = "folio-ask"

        /** Type and codes only: Hermes's error text can echo the question. */
        internal fun askAttrs(e: Throwable, op: String): Map<String, Any?> = mapOf(
            "op" to op,
            "type" to e.javaClass.simpleName,
            "code" to (e as? HermesRpcException)?.code,
            "http_status" to (e as? HermesAuthException.Unavailable)?.httpCode,
        )

        private fun defaultGateway(config: HermesConfig, tokens: AccessTokenProvider, scope: CoroutineScope) =
            HermesGateway(config, tokens, http, scope)
    }
}
