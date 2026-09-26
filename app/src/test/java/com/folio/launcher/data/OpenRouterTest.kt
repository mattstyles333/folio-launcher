package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterTest {
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
