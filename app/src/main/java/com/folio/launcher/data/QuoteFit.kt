package com.folio.launcher.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Jev picks, from a shortlist that moves with the day, the quote that suits a new Bing print. */
object QuoteFit {
    const val SHORTLIST = 60

    /** Coprime to the bank's size (872), so the shortlist spreads across every theme. */
    private const val STRIDE = 97
    internal const val QUESTION = "quote"

    /** Up to [n] bank indices; the first is the plain daily pick, so a failed call changes nothing. */
    fun shortlist(size: Int, epochDay: Int, salt: Int, n: Int = SHORTLIST): List<Int> {
        if (size <= 0) return emptyList()
        val start = (epochDay * 13 + salt).mod(size)
        return (0 until minOf(n, size)).map { (start + it * STRIDE).mod(size) }.distinct()
    }

    /** Bing's title and what its credit line says the photo is, without the photographer. */
    fun describe(title: String, credit: String): String {
        val about = credit.substringBefore(" (©").substringBefore(" ©").trim()
        return listOf(title.trim(), about)
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .joinToString(" — ")
    }

    fun request(photo: String, quotes: List<Quote>, shortlist: List<Int>): Pair<JsonObject, JsonObject> {
        val state = buildJsonObject { put("photograph", photo) }
        val questions = buildJsonObject {
            putJsonObject(QUESTION) {
                put("type", "choice")
                put(
                    "instructions",
                    "Which quote best suits `photograph` in mood or subject, printed small above the clock on it?",
                )
                putJsonObject("criteria") {
                    for (i in shortlist) {
                        val q = quotes.getOrNull(i) ?: continue
                        put("q$i", if (q.author.isEmpty()) "“${q.text}”" else "“${q.text}” — ${q.author}")
                    }
                }
            }
        }
        return state to questions
    }

    /** The bank index Jev chose, if it's one that was offered. */
    fun pick(answers: Map<String, JevAnswer>, shortlist: List<Int>): Int? =
        answers[QUESTION]?.choice?.removePrefix("q")?.toIntOrNull()?.takeIf { it in shortlist }
}
