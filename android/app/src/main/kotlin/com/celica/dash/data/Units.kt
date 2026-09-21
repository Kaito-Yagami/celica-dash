package com.celica.dash.data

import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The units the dash speaks in, chosen once in Setup. Inside, the dash keeps what it always
 * kept - speed in mph, distance in metres, fuel in miles a UK gallon, temperature in Celsius -
 * and recorded drives stay in those, so a drive made under one setting replays right under
 * another. Only what is drawn or spoken goes through here.
 *
 * Three systems: UK (mph, miles, mpg, °C), US (mph, miles, US mpg, °F), metric (km/h, km,
 * L/100 km, °C). Held as plain vars like [com.celica.dash.ui.theme.Ink]; the activity is
 * recreated on a change.
 */
object Units {
    const val UK = "uk"
    const val US = "us"
    const val METRIC = "metric"
    val SYSTEMS = listOf(UK to "Miles · mpg · °C", US to "Miles · US mpg · °F", METRIC to "Kilometres · L/100 km · °C")

    var system = UK
        private set
    var locale: Locale = Locale.UK
        private set

    fun apply(system: String, localeTag: String) {
        this.system = if (system in listOf(UK, US, METRIC)) system else UK
        locale = runCatching { Locale.forLanguageTag(localeTag) }.getOrNull()?.takeIf { it.language.isNotBlank() } ?: Locale.UK
    }

    val metric get() = system == METRIC
    val fahrenheit get() = system == US

    // ---- speed ----
    val speedUnit: String get() = if (metric) "KM/H" else "MPH"
    fun speed(mph: Float): Float = if (metric) mph * 1.609344f else mph
    fun speedInt(mph: Float?): String? = mph?.let { speed(it).toInt().toString() }

    // ---- distance ----
    /** The long unit, upper case: MI or KM. */
    val distUnit: String get() = if (metric) "KM" else "MI"
    val distWord: String get() = if (metric) "kilometres" else "miles"
    /** Miles or kilometres from metres, as a number. */
    fun dist(m: Double): Double = if (metric) m / 1000.0 else m / 1609.344
    /** "%.1f" of the distance, for a figure that carries its own unit label beside it. */
    fun dist1(m: Double): String = "%.1f".format(dist(m))
    /** A road distance for a sign: metres/yards close up, tenths of a km/mile, whole ones far off. */
    fun roadDist(m: Double): String {
        if (metric) {
            val km = m / 1000.0
            return when {
                km >= 10 -> "%.0f KM".format(km)
                km >= 0.3 -> "%.1f KM".format(km)
                else -> "${((m) / 10).toInt() * 10} M"
            }
        }
        val mi = m / 1609.344
        return when {
            mi >= 10 -> "%.0f MI".format(mi)
            mi >= 0.25 -> "%.1f MI".format(mi)
            else -> "${((m * 1.09361) / 10).toInt() * 10} YD"
        }
    }
    /** The same for speech: "300 metres", "2 kilometres", "400 yards", "3 miles". */
    fun spokenDist(m: Double, step: Int = 50): String {
        if (metric) {
            val km = m / 1000.0
            return if (km >= 0.8) "%.0f kilometres".format(km) else "${((m) / step).toInt() * step} metres"
        }
        val mi = m / 1609.344
        return if (mi >= 0.5) "%.0f miles".format(mi) else "${((m * 1.09361) / step).toInt() * step} yards"
    }

    // ---- fuel ----
    /** MPG, MPG (US) or L/100KM. */
    val econUnit: String get() = when (system) { METRIC -> "L/100KM"; US -> "MPG US"; else -> "MPG" }
    val econWord: String get() = when (system) { METRIC -> "Litres per 100 km"; US -> "Miles a US gallon"; else -> "Miles a gallon" }
    /** From miles a UK gallon, which is what the engine's air reading gives. */
    fun econ(mpgUk: Float?): String? {
        val v = mpgUk ?: return null
        return when (system) {
            METRIC -> if (v > 0.5f) "%.1f".format(282.481f / v) else null
            US -> "%.0f".format(v * 0.832674f)
            else -> "%.0f".format(v)
        }
    }

    // ---- temperature ----
    val tempUnit: String get() = if (fahrenheit) "°F" else "°C"
    fun temp(c: Int): Int = if (fahrenheit) (c * 9f / 5f + 32f).roundToInt() else c
    fun temp(c: Float): Float = if (fahrenheit) c * 9f / 5f + 32f else c

    // ---- weather ----
    val windUnit: String get() = if (metric) "KM/H" else "MPH"
    fun wind(mph: Float): Int = speed(mph).roundToInt()

    // ---- dates and numbers ----
    fun integers(): NumberFormat = NumberFormat.getIntegerInstance(locale)
    fun date(pattern: String): SimpleDateFormat = SimpleDateFormat(pattern, locale)
    fun dateTime(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, locale)
}
