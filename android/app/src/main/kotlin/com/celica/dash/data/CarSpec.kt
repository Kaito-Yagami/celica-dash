package com.celica.dash.data

/**
 * The car, as the dash draws it and reasons about it: the wordmark and the tile labels, the rev
 * counter's lift point and redline, the gearbox for reading the gear off revs against road
 * speed, and which wireframe to draw. Set in Setup (or Settings › Car) and held here as plain
 * vars, read at composition and by the renderers the way [com.celica.dash.ui.theme.Ink] holds
 * the palette; the activity is recreated on a change.
 *
 * Three presets. The 2ZZ-GE car is the one this was written for - a 2003 Celica T-Sport, C60
 * six-speed - and its lift at 6,000 is a real thing: the cam switch, which the dash turns red
 * for. The 1ZZ-FE car has no such thing, so the dash draws no hot zone and counts no time on
 * the cams; its C59 five-speed is the European one with the 4.312 final drive. "Other" starts
 * from nothing and takes whatever the owner types, gear readout off unless ratios are given.
 */
object CarSpec {
    const val PRESET_2ZZ = "celica_2zz"
    const val PRESET_1ZZ = "celica_1zz"
    const val PRESET_OTHER = "other"
    val PRESETS = listOf(PRESET_2ZZ to "Celica 2ZZ-GE · T-Sport / GT-S / SS-II", PRESET_1ZZ to "Celica 1ZZ-FE · 140 / GT / SS-I", PRESET_OTHER to "Another car")

    const val MODEL_STOCK = "stock"
    const val MODEL_WING = "wing"
    val MODELS = listOf(MODEL_STOCK to "Stock · lip spoiler", MODEL_WING to "GT wing")

    // first, since the vars below are initialised in order
    private val C60 = doubleArrayOf(3.166, 1.904, 1.392, 1.031, 0.815, 0.725)
    private val C59 = doubleArrayOf(3.166, 1.904, 1.310, 0.969, 0.815)

    var preset = PRESET_2ZZ
    /** The wordmark; the part between asterisks is drawn in the accent colour. */
    var name = "CELICA *T*-SPORT"
    /** A small second script beside the wordmark; blank for none. */
    var alt = "セリカ"
    /** The tile label on Home, and its line under. */
    var code = "ZZT231"
    var sub = "2ZZ-GE · VVTL-i"
    var note = "190 PS · 8,200 RPM\nC60 · 6-SPEED"
    /** Where the rev counter turns hot; null for a car with nothing of the kind. */
    var liftRpm: Int? = 6000
    var redline = 8200
    /** Ratios, first gear first; null turns the gear readout off. */
    var gears: DoubleArray? = C60
    var finalDrive = 4.529
    /** Metres a turn of the wheel covers. */
    var circumferenceM = 1.921
    /** The real car's length, so the road under the drawing streams past at road speed. */
    var lengthM = 4.335f
    /** Which wireframe: the printable as it is, or the owner's with the GT wing. */
    var model = MODEL_STOCK

    val hasLift: Boolean get() = liftRpm != null
    /** Rolling radius, for spinning the drawn wheels at the true rate. */
    val tyreRadiusM: Float get() = (circumferenceM / (2 * Math.PI)).toFloat()
    /** "lift" where it is a real thing, "shift" where the owner set a shift point instead. */
    val liftWord: String get() = if (preset == PRESET_2ZZ) "lift" else "shift"
    /** The line under a tach's label: the engine and where it goes hot, or just its redline. */
    val tachLabel: String get() = liftRpm?.let { "$sub · $liftWord $it" } ?: "$sub · redline $redline".removePrefix(" · ")

    /** What Setup stores; blank strings and nulls mean "the preset's own". */
    data class Saved(
        val preset: String = PRESET_2ZZ, val name: String = "", val alt: String? = null, val code: String = "",
        val sub: String = "", val note: String = "", val liftRpm: Int? = null, val noLift: Boolean = false, val redline: Int? = null,
        val gears: String? = null, val finalDrive: Double? = null, val circumferenceM: Double? = null, val lengthM: Float? = null,
        val model: String = "",
    )

    fun apply(s: Saved) {
        preset = if (s.preset in listOf(PRESET_2ZZ, PRESET_1ZZ, PRESET_OTHER)) s.preset else PRESET_2ZZ
        val d = defaults(preset)
        name = s.name.ifBlank { d.name }
        alt = s.alt ?: d.alt
        code = s.code.ifBlank { d.code }
        sub = s.sub.ifBlank { d.sub }
        note = s.note.ifBlank { d.note }
        redline = (s.redline ?: d.redline).coerceIn(3000, 20000)
        liftRpm = if (s.noLift) null else (s.liftRpm ?: d.liftRpm)?.coerceIn(1000, redline)
        gears = when {
            s.gears == null -> d.gears
            s.gears.isBlank() -> null
            else -> s.gears.split(",").mapNotNull { it.trim().toDoubleOrNull() }.toDoubleArray().takeIf { it.isNotEmpty() }
        }
        finalDrive = s.finalDrive ?: d.finalDrive
        circumferenceM = s.circumferenceM ?: d.circumferenceM
        lengthM = s.lengthM ?: d.lengthM
        model = if (s.model == MODEL_WING) MODEL_WING else MODEL_STOCK
    }

    private class Defaults(
        val name: String, val alt: String, val code: String, val sub: String, val note: String,
        val liftRpm: Int?, val redline: Int, val gears: DoubleArray?, val finalDrive: Double, val circumferenceM: Double, val lengthM: Float,
    )

    private fun defaults(preset: String) = when (preset) {
        PRESET_1ZZ -> Defaults(
            "CELICA", "セリカ", "ZZT230", "1ZZ-FE · VVT-i", "140 PS · 6,400 RPM\nC59 · 5-SPEED",
            null, 6400, C59, 4.312, 1.932, 4.335f,                 // 195/60 R15
        )
        PRESET_OTHER -> Defaults("DASH", "", "", "", "", null, 7000, null, 4.0, 1.95, 4.4f)
        else -> Defaults(
            "CELICA *T*-SPORT", "セリカ", "ZZT231", "2ZZ-GE · VVTL-i", "190 PS · 8,200 RPM\nC60 · 6-SPEED",
            6000, 8200, C60, 4.529, 1.921, 4.335f,                 // 205/50 R16
        )
    }

    /** What is in force right now, as a Saved - every field filled. */
    fun snapshot(): Saved = Saved(
        preset, name, alt, code, sub, note, liftRpm, !hasLift, redline,
        gears?.joinToString(", ") ?: "", finalDrive, circumferenceM, lengthM, model,
    )

    /** What [s] would come out as once applied - the preset's defaults under its blanks - leaving the live car alone. */
    fun effective(s: Saved): Saved {
        val keep = snapshot()
        apply(s)
        val out = snapshot()
        apply(keep)
        return out
    }

    /** The wordmark split into (text, accented) runs, from the asterisks. */
    fun wordmarkRuns(): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        var accent = false
        val sb = StringBuilder()
        for (ch in name) {
            if (ch == '*') { if (sb.isNotEmpty()) out += sb.toString() to accent; sb.clear(); accent = !accent } else sb.append(ch)
        }
        if (sb.isNotEmpty()) out += sb.toString() to accent
        return out
    }
}
