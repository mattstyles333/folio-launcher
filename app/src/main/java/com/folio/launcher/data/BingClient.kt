package com.folio.launcher.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Bing's homepage archive. One sweep per day per market (offsets 0 and 7 — Bing ignores
 * anything past idx=7), requests in parallel, and the list is kept on disk so a killed
 * process doesn't refetch it.
 */
class BingClient(private val cacheFile: File? = null) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    @Volatile
    private var cache: BingArchiveCache? = null

    suspend fun archive(locale: Locale = Locale.getDefault()): List<BingImage> = lock.withLock {
        val key = cacheKey(LocalDate.now(), market(locale))
        cache?.let { if (it.key == key) return@withLock it.images }
        readDisk()?.let { if (it.key == key && it.images.isNotEmpty()) { cache = it; return@withLock it.images } }
        val images = fetchArchive(locale)
        if (images.isNotEmpty()) {
            val fresh = BingArchiveCache(key, images)
            cache = fresh
            writeDisk(fresh)
        } else {
            // Offline: yesterday's list still points at valid images.
            (cache ?: readDisk())?.let { return@withLock it.images }
        }
        images
    }

    private suspend fun fetchArchive(locale: Locale): List<BingImage> = coroutineScope {
        val markets = linkedSetOf(market(locale), "en-GB", "en-US")
        val pages = markets.flatMap { market -> OFFSETS.map { idx -> archiveUrl(market, idx) } }
            .map { endpoint ->
                async(Dispatchers.IO) {
                    val body = getBytes(endpoint, accept = "application/json") ?: return@async emptyList()
                    runCatching { json.decodeFromString<BingArchive>(body.decodeToString()).images }
                        .getOrDefault(emptyList())
                }
            }
            .awaitAll()
        dedupe(pages.flatten())
    }

    fun download(image: BingImage, dest: File, width: Int, height: Int): Boolean {
        val tmp = File(dest.parentFile, "wallpaper.tmp")
        for (url in image.candidateUrls(width, height)) {
            if (!getToFile(url, tmp)) continue
            if (tmp.length() < MIN_IMAGE_BYTES) continue
            runCatching {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
            }.onSuccess {
                if (dest.length() > 0L) return true
            }
        }
        tmp.delete()
        return false
    }

    private fun readDisk(): BingArchiveCache? {
        val file = cacheFile ?: return null
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<BingArchiveCache>(file.readText()) }.getOrNull()
    }

    private fun writeDisk(value: BingArchiveCache) {
        val file = cacheFile ?: return
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(BingArchiveCache.serializer(), value))
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun open(url: String, accept: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", accept)
        }

    private fun getBytes(url: String, accept: String): ByteArray? {
        return runCatching {
            val conn = open(url, accept)
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** Streams straight to disk — a UHD original is several MB. */
    private fun getToFile(url: String, out: File): Boolean {
        return runCatching {
            val conn = open(url, accept = "image/*")
            try {
                if (conn.responseCode !in 200..299) return false
                conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
                true
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    companion object {
        /** Bing serves the same eight images for every idx >= 7. */
        internal val OFFSETS = intArrayOf(0, 7)
        private const val MIN_IMAGE_BYTES = 80_000L
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"

        internal fun archiveUrl(market: String, idx: Int): String =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=$market&uhd=1"

        internal fun cacheKey(day: LocalDate, market: String): String = "$day/$market"

        internal fun market(locale: Locale): String {
            val lang = locale.language.ifBlank { "en" }
            val region = locale.country.ifBlank { "US" }
            return "$lang-$region"
        }

        internal fun dedupe(images: List<BingImage>): List<BingImage> {
            val seen = HashSet<String>()
            return images.filter { image ->
                (image.url.isNotBlank() || image.urlbase.isNotBlank()) && seen.add(image.identity())
            }
        }

        /** A random print that isn't the current or previous one (by Bing identity, not list position). */
        fun pickNext(
            images: List<BingImage>,
            avoid: Set<String>,
            random: Random = Random.Default,
        ): BingImage? {
            if (images.isEmpty()) return null
            val fresh = images.filter { it.identity() !in avoid }
            return (fresh.ifEmpty { images }).random(random)
        }
    }
}

@Serializable
internal data class BingArchiveCache(
    val key: String,
    val images: List<BingImage>,
)

@Serializable
data class BingArchive(
    val images: List<BingImage> = emptyList(),
)

@Serializable
data class BingImage(
    val url: String = "",
    val urlbase: String = "",
    val copyright: String = "",
    val title: String = "",
    val hsh: String = "",
) {
    fun candidateUrls(width: Int = 1080, height: Int = 2340): List<String> {
        val host = "https://www.bing.com"
        val list = linkedSetOf<String>()
        if (urlbase.isNotBlank()) {
            list += "$host${urlbase}_UHD.jpg"
            list += "$host${urlbase}_1920x1200.jpg"
            list += "$host${urlbase}_1920x1080.jpg"
            list += "$host${urlbase}_1080x1920.jpg"
        }
        val raw = when {
            url.startsWith("http") -> url
            url.isNotBlank() -> host + url
            else -> null
        }
        if (raw != null) {
            list += sized(raw, width, height)
            list += raw
        }
        return list.toList()
    }

    private fun sized(url: String, width: Int, height: Int): String {
        var next = url
        next = WIDTH.replace(next, "\$1$width")
        next = HEIGHT.replace(next, "\$1$height")
        if (!next.contains("w=")) {
            next += if (next.contains('?')) "&w=$width&h=$height" else "?w=$width&h=$height"
        }
        return next
    }

    fun caption(): String {
        val named = title.trim()
        if (named.isNotEmpty()) return named
        val copy = copyright.trim()
        val cut = copy.indexOf(" (")
        return if (cut > 0) copy.substring(0, cut) else copy
    }

    fun identity(): String {
        val src = urlbase.ifBlank { url }
        val name = OHR.find(src)?.groupValues?.get(1)
        return name ?: hsh.ifBlank { src }
    }

    companion object {
        private val OHR = Regex("OHR\\.([A-Za-z0-9]+)")
        private val WIDTH = Regex("([?&]w=)\\d+")
        private val HEIGHT = Regex("([?&]h=)\\d+")
    }
}
