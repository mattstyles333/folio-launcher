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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HermesAsk against a scripted Hermes dashboard (wire shapes copied from the :hermes library's
 * FakeHermesServer): Hermes Live's token reaches only Hermes Live's base URL, a turn streams into
 * the overlay state, an approval waits for the user's tap, and closing the overlay drops the socket.
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

    private fun hermesAsk(token: String = "fake-token") = HermesAsk(
        parent = parent,
        connection = {
            HermesConnection(true, "http://hermes.test.ts.net:${server.port}", token, expiresAtMs = Long.MAX_VALUE)
        },
        loadSessionId = { "" },
        saveSessionId = { saved += it },
        gatewayFactory = { config, tokens, scope ->
            val http = OkHttpClient.Builder().dns { listOf(InetAddress.getLoopbackAddress()) }.build()
            HermesGateway(config, tokens, http, scope).also { gateway = it }
        },
    )

    private suspend fun until(what: String, check: () -> Boolean) {
        withTimeout(10_000) { while (!check()) delay(10) }
        assertTrue(what, check())
    }

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
}
