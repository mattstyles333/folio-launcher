package com.folio.launcher.data

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One Jev answer. Choice fills [choice]/[probabilities]/[confidence]; Noul fills [noul]. */
@Serializable
data class JevAnswer(
    val type: String = "",
    val choice: String? = null,
    val probabilities: Map<String, Double> = emptyMap(),
    val confidence: Double = 0.0,
    val noul: Double? = null,
)

@Serializable
internal data class SystemOneResponse(val answers: Map<String, JevAnswer> = emptyMap())

@Serializable
internal data class ChatResponse(val choices: List<ChatChoice> = emptyList())

@Serializable
internal data class ChatChoice(val message: ChatMessage? = null)

@Serializable
internal data class ChatMessage(val content: String? = null)

/**
 * OpenRouter with the key pasted in Settings. Jev makes the decisions on the System One
 * endpoint; DeepSeek writes the odd line on chat completions. Any failure is null, and the
 * caller carries on without it.
 */
class OpenRouter(private val key: String) {
    suspend fun decide(state: JsonElement, questions: JsonObject): Map<String, JevAnswer>? {
        val body = buildJsonObject {
            put("model", JEV)
            put("state", state)
            put("questions", questions)
        }
        return post("$BASE/systemone", body.toString(), JEV_READ_MS)?.let(::parseAnswers)
    }

    suspend fun write(system: String, prompt: String, maxTokens: Int = 120): String? {
        val body = buildJsonObject {
            put("model", WRITER)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt)
                }
            }
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            // V4.1 Flash reasons at "high" by default; a one-liner doesn't need it.
            putJsonObject("reasoning") { put("enabled", false) }
        }
        return post("$BASE/chat/completions", body.toString(), WRITER_READ_MS)?.let(::parseChat)
    }

    /** True if OpenRouter accepts the key, false if it refuses it, null if it couldn't be reached. */
    suspend fun check(): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open("$BASE/key", JEV_READ_MS)
            try {
                when (conn.responseCode) {
                    in 200..299 -> true
                    401, 403 -> false
                    else -> null
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private suspend fun post(url: String, body: String, readMs: Int): String? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = open(url, readMs).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) return@runCatching null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun open(url: String, readMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_MS
            readTimeout = readMs
            setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("HTTP-Referer", REFERER)
            setRequestProperty("X-Title", "Folio")
        }

    companion object {
        private const val BASE = "https://openrouter.ai/api/v1"
        private const val REFERER = "https://github.com/mattstyles333/folio-launcher"
        const val JEV = "typesafe/jev-1.13"
        const val WRITER = "deepseek/deepseek-v4.1-flash"
        private const val CONNECT_MS = 4_000
        private const val JEV_READ_MS = 5_000
        private const val WRITER_READ_MS = 12_000

        private val json = Json { ignoreUnknownKeys = true }

        /** OpenRouter keys are `sk-or-…` with no whitespace. */
        fun looksLikeKey(text: String): Boolean =
            text.startsWith("sk-or-") && text.length >= 24 && text.none { it.isWhitespace() }

        internal fun parseAnswers(body: String): Map<String, JevAnswer>? =
            runCatching { json.decodeFromString<SystemOneResponse>(body).answers }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }

        internal fun parseChat(body: String): String? =
            runCatching { json.decodeFromString<ChatResponse>(body) }
                .getOrNull()
                ?.choices
                ?.firstOrNull()
                ?.message
                ?.content
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
    }
}
