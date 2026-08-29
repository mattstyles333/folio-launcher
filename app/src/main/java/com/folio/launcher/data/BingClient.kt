package com.folio.launcher.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BingClient {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cache: List<BingImage>? = null
    @Volatile
    private var cacheKey: String? = null

    fun imageAt(
        index: Int,
        locale: Locale = Locale.getDefault(),
        width: Int = 1080,
        height: Int = 2340,
    ): BingImage? {
        val images = archive(locale, width, height)
        if (images.isEmpty()) return null
        val i = index.floorMod(images.size)
        return images[i]
    }

    fun archive(
        locale: Locale = Locale.getDefault(),
        width: Int = 1080,
        height: Int = 2340,
    ): List<BingImage> {
        val w = width.coerceIn(720, 1440)
        val h = height.coerceIn(1280, 3200)
        val key = LocalDate.now().toString() + market(locale) + "${w}x$h"
        cache?.let { if (cacheKey == key) return it }
        val mkt = market(locale)
        val markets = linkedSetOf(mkt, "en-GB", "en-US")
        val seen = LinkedHashSet<String>()
        val images = ArrayList<BingImage>(32)
        for (market in markets) {
            for (idx in OFFSETS) {
                val endpoint =
                    "https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=$market&uhd=1&uhdwidth=$w&uhdheight=$h"
                val body = getBytes(endpoint, accept = "application/json") ?: continue
                val parsed = runCatching {
                    json.decodeFromString<BingArchive>(body.decodeToString())
                }.getOrNull() ?: continue
                for (image in parsed.images) {
                    if (image.url.isBlank() && image.urlbase.isBlank()) continue
                    if (seen.add(image.identity())) images += image
                }
            }
        }
        if (images.isNotEmpty()) {
            cache = images
            cacheKey = key
        }
        return images
    }

    fun download(image: BingImage, dest: File): Boolean {
        val tmp = File(dest.parentFile, "wallpaper.tmp")
        for (url in image.candidateUrls()) {
            val bytes = getBytes(url, accept = "image/*") ?: continue
            if (bytes.size < 8_000) continue
            runCatching {
                tmp.outputStream().use { it.write(bytes) }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.inputStream().use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                    tmp.delete()
                }
            }.onSuccess {
                if (dest.length() > 0L) return true
            }
        }
        tmp.delete()
        return false
    }

    private fun getBytes(url: String, accept: String): ByteArray? {
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 25_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", accept)
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun market(locale: Locale): String {
        val lang = locale.language.ifBlank { "en" }
        val region = locale.country.ifBlank { "US" }
        return "$lang-$region"
    }

    companion object {
        private val OFFSETS = intArrayOf(0, 8, 16)
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126.0.0.0 Mobile Safari/537.36"

        fun nextIndex(current: Int, count: Int, random: kotlin.random.Random = kotlin.random.Random.Default): Int {
            if (count <= 1) return 0
            if (current < 0) return random.nextInt(count)
            val here = current.floorMod(count)
            var next = random.nextInt(count)
            if (next == here) {
                next = (here + 1 + random.nextInt(count - 1)) % count
            }
            return next
        }
    }
}

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
    fun candidateUrls(): List<String> {
        val host = "https://www.bing.com"
        val list = linkedSetOf<String>()
        if (url.startsWith("http")) list += url
        else if (url.isNotBlank()) list += host + url
        if (urlbase.isNotBlank()) {
            list += "$host${urlbase}_1080x1920.jpg"
            list += "$host${urlbase}_UHD.jpg"
            list += "$host${urlbase}_1920x1080.jpg"
        }
        return list.toList()
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
    }
}

private fun Int.floorMod(m: Int): Int {
    if (m <= 0) return 0
    val r = this % m
    return if (r < 0) r + m else r
}
