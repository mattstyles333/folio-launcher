package com.folio.launcher.data

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Real Jev and DeepSeek calls through Folio's own client. Skipped unless OPENROUTER_API_KEY is
 * set (CI never has it). Routes and timings go to app/build/openrouter-live.txt.
 */
class OpenRouterLiveTest {
    private val key = System.getenv("OPENROUTER_API_KEY").orEmpty()
    private val apps = linkedMapOf(
        "Trainline" to "com.thetrainline",
        "Spotify" to "com.spotify.music",
        "Gmail" to "com.google.android.gm",
        "Calculator" to "com.sec.android.app.popupcalculator",
        "Google Maps" to "com.google.android.apps.maps",
        "Monzo" to "co.uk.getmondo",
    )
    private val report = StringBuilder()

    @Before
    fun needsKey() = assumeTrue("OPENROUTER_API_KEY not set", key.isNotEmpty())

    @Test
    fun routesAndAnswers() = runBlocking {
        val router = OpenRouter(key)
        assertEquals(true, router.check())
        for (q in listOf("trains", "arsenal score", "capital of australia", "play lofi", "rewrite my cover letter", "my bank")) {
            val (state, questions) = SearchBrain.request(q, apps, music = true)
            val started = System.nanoTime()
            val answers = router.decide(state, questions)
            val ms = (System.nanoTime() - started) / 1_000_000
            assertNotNull("Jev gave no answers for \"$q\"", answers)
            val intent = answers!![SearchBrain.INTENT]
            val app = answers[SearchBrain.APP]
            report.appendLine(
                "\"$q\" → ${SearchBrain.route(q, answers)}  (${ms}ms; intent ${intent?.choice} " +
                    "${"%.2f".format(intent?.confidence)}, app ${app?.choice} ${"%.2f".format(app?.confidence)})",
            )
        }
        val started = System.nanoTime()
        val line = router.write(SearchBrain.ANSWER_SYSTEM, "capital of australia")
        val ms = (System.nanoTime() - started) / 1_000_000
        report.appendLine("DeepSeek: $line  (${ms}ms) → ${line?.let(SearchBrain::cleanAnswer)}")
        assertNotNull("DeepSeek wrote nothing", line)

        val bank = Json.decodeFromString<List<Quote>>(File("src/main/assets/quotes.json").readText())
        val shortlist = QuoteFit.shortlist(bank.size, epochDay = 20_722, salt = 1)
        val photo = QuoteFit.describe("Lake Magadi", "Lesser flamingos at Lake Magadi, Kenya (© Martin Harvey/Getty Images)")
        val (state, questions) = QuoteFit.request(photo, bank, shortlist)
        val fit = router.decide(state, questions)?.let { QuoteFit.pick(it, shortlist) }
        report.appendLine("Quote for \"$photo\": ${fit?.let { bank[it] }} (daily pick was ${bank[shortlist.first()]})")
        File("build/openrouter-live.txt").writeText(report.toString())
    }
}
