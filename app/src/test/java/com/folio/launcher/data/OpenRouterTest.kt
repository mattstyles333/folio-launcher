package com.folio.launcher.data

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTest {
    private val nothing = JsonObject(emptyMap())

    @Test
    fun decide_postsToSystemOneWithTheKey() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("""{"answers":{"intent":{"type":"choice","choice":"web","confidence":0.9}}}""")
                .build(),
        )
        server.start()
        try {
            val answers = OpenRouter("sk-or-test", server.url("/api/v1").toString()).decide(nothing, nothing)
            assertEquals("web", answers!!["intent"]!!.choice)
            val request = server.takeRequest()
            assertEquals("/api/v1/systemone", request.url.encodedPath)
            assertEquals("Bearer sk-or-test", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun warm_opensTheConnectionJevsFirstCallGoesOutOn() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().body("""{"data":{"label":"Folio"}}""").build())
        server.enqueue(
            MockResponse.Builder()
                .body("""{"answers":{"intent":{"type":"choice","choice":"web","confidence":0.9}}}""")
                .build(),
        )
        server.start()
        try {
            val router = OpenRouter("sk-or-test", server.url("/api/v1").toString())
            assertTrue(router.warm())
            assertEquals("web", router.decide(nothing, nothing)!!["intent"]!!.choice)
            // One small authenticated GET: OpenRouter's key endpoint, which bills nothing.
            val warm = server.takeRequest()
            assertEquals("GET", warm.method)
            assertEquals("/api/v1/key", warm.url.encodedPath)
            assertEquals("Bearer sk-or-test", warm.headers["Authorization"])
            // Its answer was read to the end and its socket left open, so Jev's call needs no new one.
            val jev = server.takeRequest()
            assertEquals("/api/v1/systemone", jev.url.encodedPath)
            assertEquals(warm.connectionIndex, jev.connectionIndex)
            assertEquals(1, jev.exchangeIndex)
        } finally {
            server.close()
        }
    }

    @Test
    fun warm_noAnswerIsFalse() = runBlocking {
        val server = MockWebServer()
        server.start()
        val base = server.url("/api/v1").toString()
        server.close()
        assertFalse(OpenRouter("sk-or-test", base).warm())
        assertNull(OpenRouter("sk-or-test", base).check())
    }

    @Test
    fun decide_cancelClosesTheCallInsteadOfWaitingForIt() = runBlocking {
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                release.await(10, TimeUnit.SECONDS) // Jev is slow today.
                return MockResponse.Builder().body("""{"answers":{}}""").build()
            }
        }
        server.start()
        try {
            val router = OpenRouter("sk-or-test", server.url("/api/v1").toString())
            val call = launch(Dispatchers.Default) { router.decide(nothing, nothing) }
            assertTrue("request never arrived", arrived.await(5, TimeUnit.SECONDS))
            val started = System.nanoTime()
            call.cancelAndJoin()
            val ms = (System.nanoTime() - started) / 1_000_000
            // A blocked read ignores cancellation; without closing the socket this is the 5 s read timeout.
            assertTrue("cancel took $ms ms", ms < 2_000)
        } finally {
            release.countDown()
            server.close()
        }
    }

    @Test
    fun decide_saysWhyItCameBackEmpty() = runBlocking {
        val server = MockWebServer()
        // Out of credit (the key's limit, or the account's), then topped up.
        server.enqueue(MockResponse.Builder().code(402).body("""{"error":{"code":402}}""").build())
        server.enqueue(MockResponse.Builder().body("""{"answers":{"intent":{"type":"choice","choice":"web"}}}""").build())
        server.start()
        try {
            val router = OpenRouter("sk-or-test", server.url("/api/v1").toString())
            assertNull(router.decide(nothing, nothing))
            assertEquals(402, router.lastStatus)
            assertEquals("web", router.decide(nothing, nothing)!!["intent"]!!.choice)
            assertEquals(200, router.lastStatus)
        } finally {
            server.close()
        }
    }

    @Test
    fun decide_tellsASpentLimitFromABlockedRequest() = runBlocking {
        val server = MockWebServer()
        // The key's own limit is spent: OpenRouter's 403 names it.
        server.enqueue(
            MockResponse.Builder().code(403).addHeader("Content-Type", "application/json")
                .body(
                    """{"error":{"code":403,"message":"Key limit exceeded","metadata":""" +
                        """{"limit_source":"openrouter_key_limit","scope":"key_limit","limit_usd":5,"spend_usd":5}}}""",
                )
                .build(),
        )
        // One search a moderation check flagged: a 403 too, about that request only.
        server.enqueue(
            MockResponse.Builder().code(403).addHeader("Content-Type", "application/json")
                .body("""{"error":{"code":403,"message":"Input was flagged","metadata":{"reasons":["x"]}}}""")
                .build(),
        )
        server.start()
        try {
            val router = OpenRouter("sk-or-test", server.url("/api/v1").toString())
            assertNull(router.decide(nothing, nothing))
            assertEquals(403, router.lastStatus)
            assertTrue(router.lastOverLimit)
            assertEquals(BrainStatus.NoCredit, OpenRouter.keyStatus(router.lastStatus, router.lastOverLimit))
            assertNull(router.decide(nothing, nothing))
            assertEquals(403, router.lastStatus)
            assertFalse(router.lastOverLimit)
            assertNull(OpenRouter.keyStatus(router.lastStatus, router.lastOverLimit))
        } finally {
            server.close()
        }
    }

    @Test
    fun keyStatus_refusedOrOutOfCreditStopsJev_tooManyCallsDoesNot() {
        assertEquals(BrainStatus.Rejected, OpenRouter.keyStatus(401))
        assertEquals(BrainStatus.NoCredit, OpenRouter.keyStatus(402))
        // A 403 is the key's only when it names a spent limit (the key's, a workspace's, a guardrail's budget).
        assertEquals(BrainStatus.NoCredit, OpenRouter.keyStatus(403, overLimit = true))
        // Otherwise one request was blocked (moderation, a guardrail): nothing said about the key.
        assertNull(OpenRouter.keyStatus(403))
        // Too many calls is a pause, not a verdict on the key.
        assertNull(OpenRouter.keyStatus(OpenRouter.RATE_LIMITED))
        assertNull(OpenRouter.keyStatus(200))
        assertNull(OpenRouter.keyStatus(502))
        // No answer at all (offline): nothing said about the key.
        assertNull(OpenRouter.keyStatus(0))
    }

    @Test
    fun parseAnswers_readsChoiceNoulAndScore() {
        // Shape from TypeSafe's quick start; OpenRouter adds cost to usage.
        val body = """
            {
              "model": "typesafe/jev-1.13",
              "answers": {
                "department": {
                  "type": "choice",
                  "choice": "technical",
                  "confidence": 0.78,
                  "probabilities": { "technical": 0.85, "sales": 0.0, "billing": 0.15 }
                },
                "frustration": {
                  "type": "score",
                  "score": 1.0,
                  "confidence": 1.0,
                  "legend": { "0": "Calm", "1": "Frustrated" },
                  "probabilities": { "0": 0.0, "1": 1.0 }
                },
                "is_urgent": { "type": "noul", "noul": 1.0 }
              },
              "usage": { "input_tokens": 392, "output_tokens": 65, "cost": 0.0000165 }
            }
        """.trimIndent()
        val answers = OpenRouter.parseAnswers(body)!!
        assertEquals("technical", answers["department"]!!.choice)
        assertEquals(0.78, answers["department"]!!.confidence, 1e-9)
        assertEquals(0.15, answers["department"]!!.probabilities["billing"]!!, 1e-9)
        assertEquals(1.0, answers["is_urgent"]!!.noul!!, 1e-9)
        assertEquals("score", answers["frustration"]!!.type)
    }

    @Test
    fun namesALimit_onlyWhenTheErrorSaysWhichLimit() {
        assertTrue(OpenRouter.namesALimit("""{"error":{"code":403,"metadata":{"limit_source":"openrouter_workspace_budget"}}}"""))
        assertFalse(OpenRouter.namesALimit("""{"error":{"code":403,"message":"Input was flagged"}}"""))
        assertFalse(OpenRouter.namesALimit("""{"error":{"code":403,"metadata":{"limit_source":""}}}"""))
        assertFalse(OpenRouter.namesALimit("<html>Forbidden</html>"))
        assertFalse(OpenRouter.namesALimit(""))
    }

    @Test
    fun parseAnswers_nullWhenEmptyOrBroken() {
        assertNull(OpenRouter.parseAnswers("""{"answers":{}}"""))
        assertNull(OpenRouter.parseAnswers("<html>busy</html>"))
    }

    @Test
    fun parseChat_readsFirstMessage() {
        val body = """
            {"id":"gen-1","choices":[{"index":0,"message":{"role":"assistant","content":"  Paris. \n"}}]}
        """.trimIndent()
        assertEquals("Paris.", OpenRouter.parseChat(body))
        assertNull(OpenRouter.parseChat("""{"choices":[]}"""))
        assertNull(OpenRouter.parseChat("""{"choices":[{"message":{"content":null}}]}"""))
    }

    @Test
    fun looksLikeKey_onlyOpenRouterKeys() {
        assertTrue(OpenRouter.looksLikeKey("sk-or-v1-" + "a".repeat(64)))
        assertFalse(OpenRouter.looksLikeKey("sk-ant-api03-" + "a".repeat(40)))
        assertFalse(OpenRouter.looksLikeKey("sk-or-v1-abc def ghi jkl mno"))
        assertFalse(OpenRouter.looksLikeKey("sk-or-v1-short"))
        assertFalse(OpenRouter.looksLikeKey(""))
    }
}
