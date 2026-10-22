package com.folio.launcher.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What search remembers of Jev and DeepSeek this session: on virtual time, with counting fakes for the calls. */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchCacheTest {
    private val key = "sk-or-v1-" + "a".repeat(64)
    private val web = mapOf(SearchBrain.INTENT to JevAnswer(type = "choice", choice = "web", confidence = 0.9))

    private fun offer(vararg options: Pair<String, String>) = SearchCache.Offer(
        apps = emptyList(),
        launches = emptyMap(),
        options = emptyMap(),
        questions = SearchBrain.questions(linkedMapOf(*options), music = false),
    )

    private val trainline = "Trainline" to "com.thetrainline"
    private val maps = "Maps" to "com.google.android.apps.maps"
    private val apps = offer(trainline, maps)

    @Test
    fun aQueryTypedAgain_isAnsweredAtOnce_withNoCall() = runTest {
        val cache = SearchCache(backgroundScope)
        var calls = 0
        assertEquals(web, cache.ask(key, apps, "Capital of Australia") { calls++; web }.await())
        // Case and spaces aside it's the same query: answered already, no wait and no second call.
        val again = cache.answers(key, apps, "  capital  of australia ")!!
        assertTrue(again.isCompleted)
        assertEquals(web, again.await())
        assertEquals(1, calls)
    }

    @Test
    fun aCallTypingMovedPast_stillFinishes_andIsKept() = runTest {
        val cache = SearchCache(backgroundScope)
        var calls = 0
        // The keystroke's wait for Jev, then the next keystroke cancels it.
        val think = launch {
            cache.ask(key, apps, "trains to leeds") {
                calls++
                delay(400)
                web
            }.await()
        }
        runCurrent()
        think.cancel()
        advanceUntilIdle()
        assertEquals(1, calls)
        // Backspaced to it: the answer is in.
        assertEquals(web, cache.answers(key, apps, "trains to leeds")!!.await())
    }

    @Test
    fun theSameQueryOnItsWay_isJoined_notAskedAgain() = runTest {
        val cache = SearchCache(backgroundScope)
        var calls = 0
        val first = cache.ask(key, apps, "trains") {
            calls++
            delay(400)
            web
        }
        runCurrent()
        // Typed on and back while the call is still out.
        val joined = cache.answers(key, apps, "trains")!!
        assertSame(first, joined)
        assertFalse(joined.isCompleted)
        assertSame(first, cache.ask(key, apps, "trains") { calls++; web })
        assertEquals(web, joined.await())
        assertEquals(1, calls)
    }

    @Test
    fun noAnswer_isNotKept_soItsAskedAgain() = runTest {
        val cache = SearchCache(backgroundScope)
        var calls = 0
        assertNull(cache.ask(key, apps, "trains") { calls++; null }.await())
        assertNull(cache.answers(key, apps, "trains"))
        assertEquals(web, cache.ask(key, apps, "trains") { calls++; web }.await())
        assertEquals(2, calls)
    }

    @Test
    fun deepSeeksSkip_isKept_noAnswerIsNot() = runTest {
        val cache = SearchCache(backgroundScope)
        var calls = 0
        assertEquals("", cache.write(key, "weather tomorrow") { calls++; "" }.await())
        assertEquals("", cache.line(key, " Weather tomorrow")!!.await())
        assertNull(cache.write(key, "arsenal score") { calls++; null }.await())
        assertNull(cache.line(key, "arsenal score"))
        assertEquals(2, calls)
    }

    @Test
    fun otherAppsOnOffer_forgetJevsRoutes_butNotDeepSeeksLines() = runTest {
        val cache = SearchCache(backgroundScope)
        cache.ask(key, apps, "capital of australia") { web }.await()
        cache.write(key, "capital of australia") { "Canberra." }.await()
        // The same apps with usage moved them about: the same offer.
        assertNotNull(cache.answers(key, offer(maps, trainline), "capital of australia"))
        // An app installed: Jev has other options now, so its routes are asked again. The line didn't depend on them.
        assertNull(cache.answers(key, offer(trainline, maps, "Monzo" to "co.uk.getmondo"), "capital of australia"))
        assertEquals("Canberra.", cache.line(key, "capital of australia")!!.await())
    }

    @Test
    fun anotherKey_forgetsEverything() = runTest {
        val cache = SearchCache(backgroundScope)
        cache.ask(key, apps, "lofi") { web }.await()
        cache.write(key, "capital of australia") { "Canberra." }.await()
        val other = "sk-or-v1-" + "b".repeat(64)
        assertNull(cache.answers(other, apps, "lofi"))
        assertNull(cache.line(other, "capital of australia"))
        assertNull(cache.answers(key, apps, "lofi"))
    }

    @Test
    fun clear_forgets_andClosesTheCallsOnTheirWay() = runTest {
        val cache = SearchCache(backgroundScope)
        cache.ask(key, apps, "trains") { web }.await()
        val out = cache.ask(key, apps, "lofi") {
            delay(5_000)
            web
        }
        runCurrent()
        cache.clear()
        assertTrue(out.isCancelled)
        advanceUntilIdle()
        assertNull(cache.answers(key, apps, "trains"))
        assertNull(cache.answers(key, apps, "lofi"))
    }

    @Test
    fun onlyTheNewestTwoCalls_areOnTheirWay() = runTest {
        val cache = SearchCache(backgroundScope)
        val slow: suspend () -> Map<String, JevAnswer>? = {
            delay(5_000)
            web
        }
        val oldest = cache.ask(key, apps, "capital") { slow() }
        val older = cache.ask(key, apps, "capital of") { slow() }
        runCurrent()
        val newest = cache.ask(key, apps, "capital of australia") { slow() }
        assertTrue(oldest.isCancelled)
        assertFalse(older.isCancelled)
        advanceUntilIdle()
        assertTrue(newest.isCompleted)
        assertNull(cache.answers(key, apps, "capital"))
        assertNotNull(cache.answers(key, apps, "capital of"))
        assertNotNull(cache.answers(key, apps, "capital of australia"))
    }

    @Test
    fun keepsTheQueriesUsedLast() = runTest {
        val cache = SearchCache(backgroundScope)
        repeat(SearchCache.CAPACITY) { i -> cache.ask(key, apps, "query $i") { web }.await() }
        // Looked up again, the oldest is the most recent.
        assertNotNull(cache.answers(key, apps, "query 0"))
        cache.ask(key, apps, "one more") { web }.await()
        assertNotNull(cache.answers(key, apps, "query 0"))
        assertNull(cache.answers(key, apps, "query 1"))
    }

    @Test
    fun offer_isBuiltOnceForAnAppListAndItsUsage() = runTest {
        val cache = SearchCache(backgroundScope)
        val list = ArrayList<LaunchableApp>()
        val launches = HashMap<String, List<Long>>()
        val offer = cache.offer(list, launches)
        assertSame(offer, cache.offer(list, launches))
        // Usage read again: built again (the options may have moved), but asking the same.
        val reread = cache.offer(list, HashMap(launches))
        assertNotSame(offer, reread)
        assertEquals(offer.questions, reread.questions)
    }
}
