package com.celica.dash.data

/**
 * Where the owner drives, as far as the dash needs to know: the point the map opens on before
 * there is a fix, and the region's name for the map's caption. Set in Setup - from the PC
 * helper's answers when there are any, else from the short list here - and held as plain vars
 * like [CarSpec], applied before the first frame.
 */
object Region {
    var name = ""
    var homeLat = 54.0
    var homeLon = -2.5
    var homeZoom = 5.5

    fun apply(name: String, lat: Double, lon: Double, zoom: Double) {
        this.name = name; homeLat = lat; homeLon = lon; homeZoom = zoom
    }

    /** A place to start from, with the units and the language its roads are usually driven in. */
    class Preset(val name: String, val lat: Double, val lon: Double, val zoom: Double, val units: String, val locale: String)

    /** The PC wizard has the whole world by country; this is enough to set a unit up without it. */
    val PRESETS = listOf(
        Preset("Great Britain", 54.0, -2.5, 5.5, Units.UK, "en-GB"),
        Preset("Ireland", 53.3, -8.0, 6.5, Units.METRIC, "en-IE"),
        Preset("United States", 39.5, -98.0, 3.8, Units.US, "en-US"),
        Preset("Canada", 56.0, -96.0, 3.5, Units.METRIC, "en-CA"),
        Preset("Australia", -25.5, 134.0, 4.0, Units.METRIC, "en-AU"),
        Preset("New Zealand", -41.0, 173.0, 5.2, Units.METRIC, "en-NZ"),
        Preset("Germany", 51.1, 10.4, 5.8, Units.METRIC, "de-DE"),
        Preset("France", 46.6, 2.4, 5.6, Units.METRIC, "fr-FR"),
        Preset("Spain", 40.2, -3.7, 5.6, Units.METRIC, "es-ES"),
        Preset("Italy", 42.5, 12.5, 5.6, Units.METRIC, "it-IT"),
        Preset("Netherlands", 52.2, 5.3, 7.0, Units.METRIC, "nl-NL"),
        Preset("Belgium", 50.6, 4.5, 7.2, Units.METRIC, "nl-BE"),
        Preset("Poland", 52.0, 19.4, 6.0, Units.METRIC, "pl-PL"),
        Preset("Sweden", 62.0, 16.0, 4.8, Units.METRIC, "sv-SE"),
        Preset("Norway", 64.0, 12.0, 4.6, Units.METRIC, "nb-NO"),
        Preset("Japan", 36.5, 138.0, 5.2, Units.METRIC, "ja-JP"),
        Preset("Somewhere else", 20.0, 0.0, 2.0, Units.METRIC, "en-US"),
    )
}
