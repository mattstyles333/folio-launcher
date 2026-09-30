package com.folio.launcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherTest {
    @Test
    fun url_roundsTheLocation() {
        assertEquals(
            "https://api.open-meteo.com/v1/forecast?latitude=51.51&longitude=-0.13&current=temperature_2m,weather_code,is_day",
            WeatherClient.url(51.50735, -0.12776),
        )
    }

    @Test
    fun parse_readsCurrent() {
        val body = """
            {"latitude":51.5,"longitude":-0.12,"current_units":{"temperature_2m":"°C"},
             "current":{"time":"2026-09-27T09:00","interval":900,"temperature_2m":11.4,"weather_code":61,"is_day":1}}
        """.trimIndent()
        assertEquals(Weather(tempC = 11.4, code = 61, isDay = 1), WeatherClient.parse(body))
        assertNull(WeatherClient.parse("""{"error":true,"reason":"bad"}"""))
        assertNull(WeatherClient.parse("nope"))
    }

    @Test
    fun describe_bucketsForJev() {
        assertEquals("light rain, cool, daylight", WeatherClient.describe(Weather(11.4, 61, 1)))
        assertEquals("clear sky, hot, dark outside", WeatherClient.describe(Weather(29.0, 0, 0)))
        assertEquals("snow, freezing, daylight", WeatherClient.describe(Weather(-1.0, 73, 1)))
    }
}
