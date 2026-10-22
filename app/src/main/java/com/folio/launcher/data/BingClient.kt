package com.folio.launcher.data

import android.graphics.BitmapFactory
import com.folio.hermes.telemetry.Telemetry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
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

    /** Last non-2xx status seen, for diagnostics (0 = no HTTP answer at all, e.g. offline). */
    private val lastStatus = AtomicInteger(0)

    @Volatile
    private var cache: BingArchiveCache? = null

    suspend fun archive(locale: Locale = Locale.getDefault()): List<BingImage> = lock.withLock {
        val key = cacheKey(LocalDate.now(), market(locale))
        cache?.let { if (it.key == key) return@withLock it.images }
        readDisk()?.let { if (it.key == key && it.images.isNotEmpty()) { cache = it; return@withLock it.images } }
        lastStatus.set(0)
        val images = fetchArchive(locale)
        if (images.isEmpty()) Telemetry.warn("bing.fetch_failed", null, mapOf("what" to "archive", "http_status" to lastStatus.get()))
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

    /** [width]×[height] is the print's own size: Bing's crop at that size is the print as it shows. */
    fun download(image: BingImage, dest: File, width: Int, height: Int): Boolean {
        lastStatus.set(0)
        val tmp = File(dest.parentFile, "wallpaper.tmp")
        val crop = image.printCrop(width, height)
        for (url in image.candidateUrls(width, height)) {
            if (!getToFile(url, tmp)) continue
            if (tmp.length() < MIN_IMAGE_BYTES) continue
            // Bing's resizer is undocumented: a crop at any other size would quietly lower the
            // print, so the UHD original (cropped here) is next.
            if (url == crop && !tmp.hasSize(width, height)) continue
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
        // Every candidate failed (a missing UHD size alone is normal and not reported).
        Telemetry.warn("bing.fetch_failed", null, mapOf("what" to "image", "http_status" to lastStatus.get()))
        return false
    }

    /** The image's own size, read from its header alone, is [width]×[height]. */
    private fun File.hasSize(width: Int, height: Int): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(absolutePath, bounds)
        return BingImage.isPrintCrop(bounds.outWidth, bounds.outHeight, width, height)
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
                if (conn.responseCode !in 200..299) {
                    lastStatus.set(conn.responseCode)
                    return null
                }
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
                if (conn.responseCode !in 200..299) {
                    lastStatus.set(conn.responseCode)
                    return false
                }
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

        /** How many of the last prints a random pick stays away from while others are left. */
        internal const val RECENT_PRINTS = 10

        /**
         * The next print, by Bing identity (not list position). [avoid] is newest first: the current
         * and previous prints, then the ones before. A random pick that isn't one of the last
         * [RECENT_PRINTS] while unseen ones remain, and never the current or previous one while
         * anything else is left. Nothing to avoid means no print yet: today's, which the archive
         * lists first (the local market's newest).
         */
        fun pickNext(
            images: List<BingImage>,
            avoid: Collection<String>,
            random: Random = Random.Default,
        ): BingImage? {
            if (images.isEmpty()) return null
            if (avoid.isEmpty()) return images.first()
            val newestFirst = avoid.toList()
            for (n in intArrayOf(RECENT_PRINTS, 2)) {
                val skip = newestFirst.take(n).toSet()
                val fresh = images.filter { it.identity() !in skip }
                if (fresh.isNotEmpty()) return fresh.random(random)
            }
            return images.random(random)
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
    /**
     * Where to fetch this print at [width]×[height] (the print's own size), best first: Bing's
     * portrait crop of the UHD master at that size, about a fifth of the original's bytes; then
     * the UHD original, cropped here. Bing's own 1080×1920 file is only ever a fallback.
     */
    fun candidateUrls(width: Int = 1080, height: Int = 2340): List<String> {
        val list = linkedSetOf<String>()
        if (urlbase.isNotBlank()) {
            printCrop(width, height)?.let { list += it }
            list += "$HOST${urlbase}_UHD.jpg"
            list += "$HOST${urlbase}_1920x1200.jpg"
            list += "$HOST${urlbase}_1920x1080.jpg"
            list += "$HOST${urlbase}_1080x1920.jpg"
        }
        val raw = when {
            url.startsWith("http") -> url
            url.isNotBlank() -> HOST + url
            else -> null
        }
        if (raw != null) {
            list += sized(raw, width, height)
            list += raw
        }
        return list.toList()
    }

    /** Bing's crop of the UHD master at [width]×[height]: rs=1 resizes, c=4 crops to fill, at quality 90. */
    fun printCrop(width: Int, height: Int): String? {
        if (urlbase.isBlank() || width <= 0 || height <= 0) return null
        val uhd = "$HOST${urlbase}_UHD.jpg"
        return uhd + (if ('?' in uhd) "&" else "?") + "w=$width&h=$height&rs=1&c=4&qlt=90"
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
        private const val HOST = "https://www.bing.com"

        /**
         * A downloaded [printCrop] is kept only at exactly the size asked for. Anything else (the
         * parameters ignored, a smaller or reshaped answer) falls through to the UHD original.
         */
        fun isPrintCrop(width: Int, height: Int, askedW: Int, askedH: Int): Boolean =
            askedW > 0 && askedH > 0 && width == askedW && height == askedH

        private val OHR = Regex("OHR\\.([A-Za-z0-9]+)")
        private val WIDTH = Regex("([?&]w=)\\d+")
        private val HEIGHT = Regex("([?&]h=)\\d+")
    }
}
