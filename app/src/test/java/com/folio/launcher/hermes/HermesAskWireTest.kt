package com.folio.launcher.hermes

import com.folio.hermes.ApprovalChoice
import com.folio.hermes.GatewayConnection
import com.folio.hermes.HermesGateway
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HermesAsk against a scripted Hermes dashboard (wire shapes copied from the :hermes library's
 * FakeHermesServer): Hermes Live's token reaches only Hermes Live's base URL, a turn streams into
 * the overlay state, an approval waits for the user's tap, and closing the overlay drops the socket
 * (and its cards); reopening reads how a cut-off turn ended without sending anything again.
 * The base URL is a `*.ts.net` name (the one cleartext HermesEndpoint allows) resolved to loopback.
 */
class HermesAskWireTest {
    private val server = MockWebServer()
    private val bearers = CopyOnWriteArrayList<String?>()
    private val frames = LinkedBlockingQueue<JsonObject>()
    private val closed = LinkedBlockingQueue<Int>()
    private val responders = ConcurrentHashMap<String, (WebSocket, JsonElement, JsonObject) -> Unit>()
    private val executor = Executors.newSingleThreadExecutor()
    private val main = executor.asCoroutineDispatcher()
    private val parent = CoroutineScope(main + SupervisorJob())
    private var gateway: HermesGateway? = null
    private val saved = CopyOnWriteArrayList<String>()

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.url.encodedPath) {
                "/api/auth/ws-ticket" -> {
                    bearers += request.headers["Authorization"]
                    MockResponse.Builder().code(200).addHeader("Content-Type", "application/json")
                        .body("""{"ticket":"t-1","ttl_seconds":30}""").build()
                }
                "/api/ws" -> MockResponse.Builder().webSocketUpgrade(listener()).build()
                else -> MockResponse.Builder().code(404).build()
            }
        }
        server.start()
    }

    @After fun tearDown() {
        gateway?.disconnect()
        parent.cancel()
        server.close()
        executor.shutdownNow()
    }

    private lateinit var socket: WebSocket

    private fun listener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            socket = webSocket
            webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"replay_epoch":"epoch-1"}}}""")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val obj = Json.parseToJsonElement(text).jsonObject
            frames.add(obj)
            val method = (obj["method"] as? JsonPrimitive)?.content
            val id = obj["id"] ?: return
            when (method) {
                null -> Unit
                "gateway.ping" -> reply(webSocket, id, """{"ok":true}""")
                "client.capabilities" -> reply(webSocket, id, """{"server_requests":["approval","clarify"]}""")
                else -> responders[method]?.invoke(webSocket, id, obj["params"]?.jsonObject ?: JsonObject(emptyMap()))
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closed.add(code)
            webSocket.close(1000, null)
        }
    }

    private fun reply(ws: WebSocket, id: JsonElement, result: String) =
        check(ws.send("""{"jsonrpc":"2.0","result":$result,"id":$id}"""))

    private fun event(ws: WebSocket, type: String, sid: String, payload: String) =
        check(ws.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"$type","session_id":"$sid","payload":$payload}}"""))

    private fun live() = HermesConnection(true, "http://hermes.test.ts.net:${server.port}", "fake-token", expiresAtMs = Long.MAX_VALUE)

    /** [connectTimeoutMs]: how long a call waits for a socket, shortened where a test waits it out. */
    private fun hermesAsk(
        connection: suspend () -> HermesConnection? = { live() },
        connectTimeoutMs: Long? = null,
    ) = HermesAsk(
        parent = parent,
        connection = connection,
        loadSessionId = { "" },
        saveSessionId = { saved += it },
        gatewayFactory = { config, tokens, scope ->
            val http = OkHttpClient.Builder().dns { listOf(InetAddress.getLoopbackAddress()) }.build()
            val tuned = connectTimeoutMs?.let { config.copy(connectTimeoutMs = it) } ?: config
            HermesGateway(tuned, tokens, http, scope).also { gateway = it }
        },
    )

    private suspend fun until(what: String, check: () -> Boolean) {
        withTimeout(10_000) { while (!check()) delay(10) }
        assertTrue(what, check())
    }

    private val created = AtomicInteger()

    /** `session.create` makes rt1/stored-1, then rt2/stored-2, … */
    private fun createsSessions() {
        responders["session.create"] = { ws, id, _ ->
            val n = created.incrementAndGet()
            reply(ws, id, """{"session_id":"rt$n","stored_session_id":"stored-$n","message_count":0,"messages":[],"info":{}}""")
        }
    }

    /** `prompt.submit` starts a turn that streams [first], if any, and then keeps running. */
    private fun startsTurns(first: String? = null) {
        responders["prompt.submit"] = { ws, id, params ->
            val sid = params["session_id"]!!.jsonPrimitive.content
            reply(ws, id, """{"status":"streaming"}""")
            event(ws, "message.start", sid, "{}")
            first?.let { event(ws, "message.delta", sid, """{"text":"$it"}""") }
        }
    }

    /**
     * `session.resume` of stored-1 (rt1), shaped like the deployed server's: the user's [question],
     * then Hermes's [answer] once there is one; [running] says whether the turn goes on.
     */
    private fun resumes(question: String, running: () -> Boolean, answer: () -> String?, count: AtomicInteger = AtomicInteger()) {
        responders["session.resume"] = { ws, id, params ->
            count.incrementAndGet()
            val rows = listOfNotNull("user" to question, answer()?.let { "assistant" to it })
            val omit = params["omit_messages"].toString() == "true"
            val messages = if (omit) "[]" else rows.joinToString(",", "[", "]") { (role, text) -> """{"role":"$role","text":"$text"}""" }
            reply(ws, id, """{"session_id":"rt1","resumed":"stored-1","session_key":"stored-1","messages":$messages,"running":${running()}}""")
        }
    }

    private fun sent(method: String) = frames.filter { (it["method"] as? JsonPrimitive)?.content == method }

    @Test
    fun streamsApprovesOnTapAndDisconnectsOnDetach() = runBlocking {
        responders["session.create"] = { ws, id, _ ->
            reply(ws, id, """{"session_id":"rt1","stored_session_id":"stored-1","message_count":0,"messages":[],"info":{}}""")
        }
        responders["prompt.submit"] = { ws, id, params ->
            val sid = params["session_id"]!!.jsonPrimitive.content
            reply(ws, id, """{"status":"streaming"}""")
            event(ws, "message.start", sid, "{}")
            event(ws, "message.delta", sid, """{"text":"Dry "}""")
            event(ws, "tool.start", sid, """{"tool_id":"c1","name":"web_search"}""")
            event(ws, "tool.complete", sid, """{"tool_id":"c1","name":"web_search"}""")
            event(ws, "message.delta", sid, """{"text":"until four."}""")
            event(ws, "message.complete", sid, """{"text":"Dry until four.","status":"complete"}""")
        }
        val hermes = hermesAsk()
        withContext(main) {
            hermes.attach()
            hermes.ask("Weather this afternoon?")
        }
        until("turn done") { hermes.state.value.exchanges.singleOrNull()?.status == ExchangeStatus.Done }
        assertEquals("Dry until four.", hermes.state.value.exchanges.single().reply)
        assertTrue(bearers.isNotEmpty())
        assertTrue(bearers.all { it == "Bearer fake-token" })
        val submit = generateSequence { frames.poll() }.first { (it["method"] as? JsonPrimitive)?.content == "prompt.submit" }
        assertEquals("Weather this afternoon?", submit["params"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        until("stored id saved") { saved == listOf("stored-1") }

        // An approval waits for the tap; nothing answers it on its own.
        check(
            socket.send(
                """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","request_id":"apr-1",""" +
                    """"command":"git push","description":"Push the branch","choices":["once","session","always","deny"]}}""",
            ),
        )
        until("approval shown") { hermes.state.value.approval?.id == "srq-1" }
        delay(200)
        assertTrue(frames.none { (it["id"] as? JsonPrimitive)?.content == "srq-1" })
        withContext(main) { hermes.approve(ApprovalChoice.Once) }
        val answer = withTimeout(5_000) {
            var f: JsonObject? = null
            while (f == null) {
                f = frames.poll(50, TimeUnit.MILLISECONDS)?.takeIf { (it["id"] as? JsonPrimitive)?.content == "srq-1" && it["method"] == null }
            }
            f
        }
        assertEquals("""{"choice":"once","all":false}""", answer["result"].toString())

        // Overlay gone: the socket closes and the gateway stays idle.
        withContext(main) { hermes.detach() }
        assertTrue("server saw the close", closed.poll(5, TimeUnit.SECONDS) != null)
        until("gateway idle") { gateway?.connection?.value == GatewayConnection.Idle }
    }

    @Test
    fun refusedToken_stopsWithAPlainLine() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                bearers += request.headers["Authorization"]
                return MockResponse.Builder().code(401).addHeader("Content-Type", "application/json")
                    .body("""{"error":"unauthenticated"}""").build()
            }
        }
        val hermes = hermesAsk()
        withContext(main) { hermes.attach() }
        until("stopped") { hermes.state.value.error == LiveTokenProvider.REFUSED }
        // One try, one re-ask of Hermes Live (same token), then stop: no loop.
        delay(300)
        assertEquals(listOf("Bearer fake-token"), bearers.distinct())
        assertTrue(bearers.size <= 2)
    }

    /** Asks "Catch me up", which streams a first chunk, then closes the overlay mid-turn. */
    private suspend fun cutOffMidTurn(): HermesAsk {
        createsSessions()
        startsTurns(first = "Two meetings")
        val hermes = hermesAsk()
        withContext(main) {
            hermes.attach()
            hermes.ask("Catch me up")
        }
        until("partial reply") { hermes.state.value.exchanges.singleOrNull()?.reply == "Two meetings" }
        withContext(main) { hermes.detach() }
        assertTrue(hermes.state.value.exchanges.single().note!!.contains("Hermes session"))
        return hermes
    }

    @Test
    fun turnCutOffByClosing_thatFinishedMeanwhile_showsTheWholeReplyOnReturn() = runBlocking {
        val hermes = cutOffMidTurn()
        resumes("Catch me up", running = { false }, answer = { "Two meetings and a parcel at four." })
        withContext(main) { hermes.attach() }
        until("recovered") { hermes.state.value.exchanges.single().status == ExchangeStatus.Done }
        val exchange = hermes.state.value.exchanges.single()
        assertEquals("Two meetings and a parcel at four.", exchange.reply)
        assertNull(exchange.note)
        // Read only: the question is never sent again.
        assertEquals(1, sent("prompt.submit").size)
    }

    @Test
    fun turnCutOffByClosing_stillRunning_isFollowedToItsEnd() = runBlocking {
        val hermes = cutOffMidTurn()
        val running = AtomicBoolean(true)
        val resumed = AtomicInteger()
        resumes(
            "Catch me up",
            running = { running.get() },
            answer = { if (running.get()) null else "Two meetings and a parcel at four." },
            count = resumed,
        )
        withContext(main) { hermes.attach() }
        // Re-bind, probe, then the follow's own resume: from here it hears the turn.
        until("following") { hermes.state.value.running && resumed.get() >= 3 }
        assertEquals("Hermes is still answering…", hermes.state.value.working)
        // What streamed while closed never arrives, so a later chunk is not glued onto the partial reply.
        event(socket, "message.delta", "rt1", """{"text":" at four."}""")
        delay(200)
        assertEquals("Two meetings", hermes.state.value.exchanges.single().reply)
        running.set(false)
        event(socket, "message.complete", "rt1", """{"text":"Two meetings and a parcel at four.","status":"complete"}""")
        until("done") { hermes.state.value.exchanges.single().status == ExchangeStatus.Done }
        assertEquals("Two meetings and a parcel at four.", hermes.state.value.exchanges.single().reply)
        assertEquals(1, sent("prompt.submit").size)
    }

    @Test
    fun closingDropsTheCards_reopeningShowsOnlyTheOnesStillOpen() = runBlocking {
        createsSessions()
        responders["prompt.submit"] = { ws, id, params ->
            val sid = params["session_id"]!!.jsonPrimitive.content
            reply(ws, id, """{"status":"streaming"}""")
            event(ws, "message.start", sid, "{}")
            event(ws, "message.complete", sid, """{"text":"Pushed.","status":"complete"}""")
        }
        val hermes = hermesAsk()
        withContext(main) {
            hermes.attach()
            hermes.ask("Ship it")
        }
        until("turn done") { hermes.state.value.exchanges.singleOrNull()?.status == ExchangeStatus.Done }
        check(
            socket.send(
                """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","request_id":"apr-1",""" +
                    """"command":"git push","description":"Push the branch","choices":["once","deny"]}}""",
            ),
        )
        until("approval shown") { hermes.state.value.approval?.id == "srq-1" }
        withContext(main) { hermes.detach() }
        // The card goes with the socket: Hermes may withdraw it while the overlay is closed.
        assertNull(hermes.state.value.approval)

        // Back again, Hermes holds a different one; resuming re-delivers it.
        responders["session.resume"] = { ws, id, _ ->
            reply(
                ws, id,
                """{"session_id":"rt1","session_key":"stored-1","messages":[],"running":false,"open_requests":[""" +
                    """{"id":"srq-2","method":"approval","params":{"session_id":"rt1","request_id":"apr-2",""" +
                    """"command":"git push --tags","description":"Push the tags","choices":["once","deny"]}}]}""",
            )
        }
        withContext(main) { hermes.attach() }
        until("open approval re-delivered") { hermes.state.value.approval?.id == "srq-2" }
    }

    @Test
    fun anAnswerStillWaitingWhenTheOverlayCloses_neverBringsItsCardBack() = runBlocking {
        val hermes = hermesAsk(connectTimeoutMs = 1_000)
        withContext(main) { hermes.attach() }
        until("connected") { gateway?.connection?.value is GatewayConnection.Ready }
        check(
            socket.send(
                """{"jsonrpc":"2.0","id":"srq-1","method":"approval","params":{"session_id":"rt1","request_id":"apr-1",""" +
                    """"command":"git push","description":"Push the branch","choices":["once","deny"]}}""",
            ),
        )
        until("approval shown") { hermes.state.value.approval?.id == "srq-1" }
        // Hermes drops the socket and stays away: the gateway keeps trying.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(503).build()
        }
        socket.close(1001, "restart")
        until("trying again") { gateway?.connection?.value is GatewayConnection.Waiting }
        // Approve once waits for a socket; the overlay closes before one comes, and the answer gives up later.
        withContext(main) { hermes.approve(ApprovalChoice.Once) }
        withContext(main) { hermes.detach() }
        assertNull(hermes.state.value.approval)
        delay(2_500)
        assertNull(hermes.state.value.approval)
    }

    @Test
    fun aStopThatFailsLate_leavesTheNextQuestionRunning() = runBlocking {
        createsSessions()
        startsTurns()
        val held = CompletableDeferred<JsonElement>()
        responders["session.interrupt"] = { ws, id, _ ->
            if (!held.isCompleted) {
                held.complete(id) // Stop's: left hanging, failed below.
            } else {
                // The next question's interrupt-first: the first turn ends.
                reply(ws, id, """{"status":"interrupted"}""")
                event(ws, "message.complete", "rt1", """{"text":"","status":"interrupted"}""")
            }
        }
        val hermes = hermesAsk()
        withContext(main) {
            hermes.attach()
            hermes.ask("One")
        }
        until("turn one running") { sent("prompt.submit").size == 1 }
        withContext(main) { hermes.stop() }
        val stopId = withTimeout(5_000) { held.await() }
        withContext(main) { hermes.ask("Two") }
        until("turn two submitted") { sent("prompt.submit").size == 2 }
        // Only now does Stop's interrupt fail: it must not touch the question asked after it.
        check(socket.send("""{"jsonrpc":"2.0","error":{"code":-32000,"message":"lost"},"id":$stopId}"""))
        delay(300)
        assertEquals(ExchangeStatus.Running, hermes.state.value.exchanges.last().status)
        event(socket, "message.complete", "rt1", """{"text":"Two done.","status":"complete"}""")
        until("turn two done") { hermes.state.value.exchanges.last().status == ExchangeStatus.Done }
    }

    @Test
    fun newDuringATurn_theNextQuestionStartsAFreshSession() = runBlocking {
        createsSessions()
        startsTurns()
        // A slow link: the old turn's interrupt is never answered.
        responders["session.interrupt"] = { _, _, _ -> }
        val hermes = hermesAsk()
        withContext(main) {
            hermes.attach()
            hermes.ask("One")
        }
        until("turn one running") { sent("prompt.submit").size == 1 }
        withContext(main) {
            hermes.newSession()
            hermes.ask("Two")
        }
        until("turn two submitted") { sent("prompt.submit").size == 2 }
        assertEquals("rt2", sent("prompt.submit").last()["params"]!!.jsonObject["session_id"]!!.jsonPrimitive.content)
        until("new session stored") { saved.lastOrNull() == "stored-2" }
        assertEquals(listOf("stored-1", "", "stored-2"), saved)
    }

    @Test
    fun attachedBeforeHermesLiveAnswered_listensOnceAQuestionConnects() = runBlocking {
        createsSessions()
        responders["prompt.submit"] = { ws, id, params ->
            val sid = params["session_id"]!!.jsonPrimitive.content
            reply(ws, id, """{"status":"streaming"}""")
            event(ws, "message.start", sid, "{}")
            event(ws, "message.complete", sid, """{"text":"All fine.","status":"complete"}""")
        }
        // Hermes Live gives nothing on the first ask (a cold start), then its connection.
        val asks = AtomicInteger()
        val hermes = hermesAsk(connection = { if (asks.getAndIncrement() == 0) null else live() })
        withContext(main) { hermes.attach() }
        until("nothing yet") { hermes.state.value.error == LiveTokenProvider.SIGNED_OUT }
        withContext(main) { hermes.ask("All good?") }
        until("turn done") { hermes.state.value.exchanges.singleOrNull()?.status == ExchangeStatus.Done }
        // Hermes drops the socket and turns the session down from now on: the overlay must say so.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse.Builder().code(401)
                .addHeader("Content-Type", "application/json").body("""{"error":"unauthenticated"}""").build()
        }
        socket.close(1001, "restart")
        until("connection line shown") { hermes.state.value.error == LiveTokenProvider.REFUSED }
    }
}
