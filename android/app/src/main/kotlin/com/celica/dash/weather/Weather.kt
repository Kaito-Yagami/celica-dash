package com.celica.dash.weather

/** The forecast the tile shows. `fetchedAt` is epoch ms so the tile can say how stale it is. */
data class Weather(
    val tempC: Float,
    val code: Int,               // WMO weather code
    val windMph: Float,
    val windDir: String,
    val minNextC: Float?,        // lowest temperature in the coming hours
    val minAtHour: String?,      // "06:00"
    val fetchedAt: Long,
) {
    val description: String get() = describe(code)
    val iceLikely: Boolean get() = (minNextC ?: tempC) <= 3f

    companion object {
        /** WMO 4677 codes as Open-Meteo reports them, in dash English. */
        fun describe(code: Int) = when (code) {
            0 -> "Clear"; 1 -> "Mostly clear"; 2 -> "Partly cloudy"; 3 -> "Overcast"
            45, 48 -> "Fog"
            51, 53, 55 -> "Drizzle"; 56, 57 -> "Freezing drizzle"
            61 -> "Light rain"; 63 -> "Rain"; 65 -> "Heavy rain"; 66, 67 -> "Freezing rain"
            71 -> "Light snow"; 73 -> "Snow"; 75 -> "Heavy snow"; 77 -> "Snow grains"
            80 -> "Showers"; 81 -> "Heavy showers"; 82 -> "Violent showers"
            85, 86 -> "Snow showers"
            95 -> "Thunderstorm"; 96, 99 -> "Thunder, hail"
            else -> "—"
        }
        fun compass(deg: Float): String {
            val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            return names[((deg + 22.5f) / 45f).toInt() % 8]
        }
    }
}
