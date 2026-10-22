package com.folio.launcher.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.serialization.json.JsonObject

/**
 * What Jev said, and DeepSeek wrote, for each query this session, so a query typed again (a
 * backspace, a retype) shows its route at once: no pause, no call. Kept by the query as Jev
 * reads it (trimmed, lowercase, single spaces), the [CAPACITY] used last. Another key forgets
 * everything; other apps on offer (one installed, removed or renamed) forget Jev's routes, but
 * not DeepSeek's lines, which never depended on them.
 *
 * A call typing has moved past isn't closed: it finishes on [scope], so its connection is free
 * and open for the next call, and what it says is kept. Only the newest [RUNNING] of each kind
 * are on their way at once, so a slow network isn't flooded; an older one is closed.
 */
class SearchCache(private val scope: CoroutineScope) {
    /** Jev's options and questions for one app list and its usage: built once, not per keystroke. */
    class Offer internal constructor(
        internal val apps: List<LaunchableApp>,
        internal val launches: Map<String, List<Long>>,
        /** Option name → app, most-used first ([SearchBrain.candidates]). */
        val options: Map<String, LaunchableApp>,
        val questions: JsonObject,
    )

    private val routes = Recall<Map<String, JevAnswer>>()

    /** DeepSeek's lines; "" when it declined (SKIP). */
    private val lines = Recall<String>()

    /** The key's hash, not the key: what's kept was asked with it. */
    private var owner: Int? = null

    /** The questions [routes] were asked with. */
    private var offered: JsonObject? = null

    private var lastOffer: Offer? = null

    /** Jev's offer for [apps] and their [launches]: the last one again while neither has changed. */
    fun offer(apps: List<LaunchableApp>, launches: Map<String, List<Long>>): Offer {
        synchronized(this) { lastOffer }?.let { if (it.apps === apps && it.launches === launches) return it }
        val options = SearchBrain.candidates(apps, launches)
        val questions = SearchBrain.questions(
            options.mapValues { it.value.packageName },
            music = apps.any { it.packageName == Spotify.PACKAGE },
        )
        return Offer(apps, launches, options, questions).also { synchronized(this) { lastOffer = it } }
    }

    /** Jev's answers for [query] over [offer], asked with [key]: in, or on their way; null when Jev hasn't been asked. */
    fun answers(key: String, offer: Offer, query: String): Deferred<Map<String, JevAnswer>?>? =
        synchronized(this) {
            scopeTo(key, offer.questions)
            routes.known(normal(query))
        }

    /** Asks Jev about [query] with [call], unless that's in or on its way. A null answer (no answer) isn't kept. */
    fun ask(
        key: String,
        offer: Offer,
        query: String,
        call: suspend () -> Map<String, JevAnswer>?,
    ): Deferred<Map<String, JevAnswer>?> =
        synchronized(this) {
            scopeTo(key, offer.questions)
            routes.ask(normal(query), call)
        }.also { it.start() }

    /** DeepSeek's line for [query], asked with [key]: in, or on its way (""; it declined); null when it hasn't been asked. */
    fun line(key: String, query: String): Deferred<String?>? =
        synchronized(this) {
            scopeTo(key, null)
            lines.known(normal(query))
        }

    /** Asks DeepSeek for [query]'s line with [call] ("" when it declines), unless that's in or on its way. */
    fun write(key: String, query: String, call: suspend () -> String?): Deferred<String?> =
        synchronized(this) {
            scopeTo(key, null)
            lines.ask(normal(query), call)
        }.also { it.start() }

    /** Forgets everything and closes the calls on their way (the key was removed). */
    fun clear() {
        synchronized(this) {
            routes.clear()
            lines.clear()
        }
    }

    private fun scopeTo(key: String, questions: JsonObject?) {
        val who = key.hashCode()
        if (owner != who) {
            owner = who
            offered = null
            routes.clear()
            lines.clear()
        }
        // Compared by content: the same options in another order (usage moved them) are the same offer.
        if (questions != null && questions !== offered && questions != offered) {
            if (offered != null) routes.clear()
            offered = questions
        }
    }

    /** Answers in (the [CAPACITY] used last) and calls on their way, by query. Under the cache's lock. */
    private inner class Recall<T : Any> {
        private val done = object : LinkedHashMap<String, T>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>) = size > CAPACITY
        }
        private val running = LinkedHashMap<String, Deferred<T?>>()

        /** Moves on at [clear]: a call from before keeps nothing. */
        private var era = 0

        fun known(query: String): Deferred<T?>? = done[query]?.let { CompletableDeferred(it) } ?: running[query]

        /** Not started yet: the caller starts it once the lock is let go. */
        fun ask(query: String, call: suspend () -> T?): Deferred<T?> {
            known(query)?.let { return it }
            while (running.size >= RUNNING) running.remove(running.keys.first())?.cancel()
            val since = era
            val asked = scope.async(start = CoroutineStart.LAZY) {
                val self = coroutineContext.job
                try {
                    call().also { answer ->
                        synchronized(this@SearchCache) {
                            if (answer != null && since == era) done[query] = answer
                        }
                    }
                } finally {
                    synchronized(this@SearchCache) { if (running[query] === self) running.remove(query) }
                }
            }
            running[query] = asked
            return asked
        }

        fun clear() {
            era++
            // Let go of them first: a call can end (and look itself up) the moment it's cancelled.
            val calls = running.values.toList()
            running.clear()
            done.clear()
            calls.forEach { it.cancel() }
        }
    }

    companion object {
        /** Queries kept, of each kind. */
        const val CAPACITY = 64

        /** Calls of each kind on their way at once. */
        const val RUNNING = 2

        private val SPACES = Regex("\\s+")

        /** [query] as Jev reads it: "Capital  of Australia " and "capital of australia" are one. */
        internal fun normal(query: String): String = query.trim().lowercase().replace(SPACES, " ")
    }
}
