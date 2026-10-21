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
