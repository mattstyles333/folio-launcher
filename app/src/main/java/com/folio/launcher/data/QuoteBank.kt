package com.folio.launcher.data

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Quote(
    @SerialName("t") val text: String,
    @SerialName("a") val author: String = "",
)

object QuoteBank {
    private val json = Json { ignoreUnknownKeys = true }
    @Volatile
    private var cached: List<Quote>? = null

    fun load(context: Context): List<Quote> {
        cached?.let { return it }
        val list = runCatching {
            context.assets.open("quotes.json").bufferedReader().use { it.readText() }
                .let { json.decodeFromString<List<Quote>>(it) }
        }.getOrDefault(emptyList()).filter { it.text.isNotBlank() }
        cached = list
        return list
    }

    fun pick(quotes: List<Quote>, epochDay: Int, salt: Int): Quote? {
        if (quotes.isEmpty()) return null
        val i = (epochDay * 13 + salt).mod(quotes.size)
        return quotes[i]
    }

    fun todayIndex(zone: ZoneId = ZoneId.systemDefault()): Int {
        return LocalDate.now(zone).toEpochDay().toInt()
    }
}
