package com.folio.launcher.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class Weather(
    @SerialName("temperature_2m") val tempC: Double = 0.0,
    @SerialName("weather_code") val code: Int = -1,
    @SerialName("is_day") val isDay: Int = 1,
)

@Serializable
internal data class WeatherResponse(val current: Weather? = null)

/** Current weather from Open-Meteo (no key), at a location rounded to ~1 km. Only when turned on. */
object WeatherClient {
    private val json = Json { ignoreUnknownKeys = true }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    suspend fun current(context: Context): Weather? = withContext(Dispatchers.IO) {
        val here = lastKnown(context) ?: return@withContext null
        runCatching {
            val conn = (URL(url(here.latitude, here.longitude)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 8_000
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                parse(conn.inputStream.bufferedReader().use { it.readText() })
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    /** The freshest fix any provider already has; no new location request. */
    private fun lastKnown(context: Context): Location? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (granted != PackageManager.PERMISSION_GRANTED) return null
        val lm = context.getSystemService(LocationManager::class.java) ?: return null
        var best: Location? = null
        for (provider in PROVIDERS) {
            val fix = try {
                lm.getLastKnownLocation(provider)
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            } ?: continue
            if (best == null || fix.time > best.time) best = fix
        }
        return best
    }

    private val PROVIDERS = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
    )

    internal fun url(lat: Double, lon: Double): String =
        String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code,is_day",
            lat,
            lon,
        )

    internal fun parse(body: String): Weather? =
        runCatching { json.decodeFromString<WeatherResponse>(body).current }.getOrNull()?.takeIf { it.code >= 0 }

    /** "light rain, cool, daylight": WMO code and temperature bucketed in code, for Jev. */
    fun describe(w: Weather): String {
        val sky = when (w.code) {
            0 -> "clear sky"
            1, 2 -> "some cloud"
            3 -> "overcast"
            45, 48 -> "fog"
            in 51..57 -> "drizzle"
            61, 80 -> "light rain"
            63, 65, 66, 67, 81, 82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "thunderstorm"
            else -> "unsettled"
        }
        val feel = when {
            w.tempC < 3 -> "freezing"
            w.tempC < 10 -> "cold"
            w.tempC < 16 -> "cool"
            w.tempC < 22 -> "mild"
            w.tempC < 27 -> "warm"
            else -> "hot"
        }
        return "$sky, $feel, ${if (w.isDay == 1) "daylight" else "dark outside"}"
    }
}
