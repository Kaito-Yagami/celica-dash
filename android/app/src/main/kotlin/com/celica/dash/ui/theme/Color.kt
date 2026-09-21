package com.celica.dash.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The palette, lifted from the mockup's :root and the site's app.css. Dark surfaces, light type,
 * and one accent kept for state only - live, warning, active - so it means something when it
 * appears.
 *
 * Two looks: "ink", the mockup's soft near-black, and "contrast", true black and a hotter red
 * to match the car's trim. Over either the owner may set their own accent and background in
 * Setup; the surfaces, hairlines and the accent's dim and glow are derived from those two so
 * the whole dash stays of a piece. Chosen in Settings; the activity is recreated on a change,
 * so these are plain vars read at composition.
 */
object Ink {
    var bg = Color(0xFF0E0D0F)          // page
    var s1 = Color(0xFF131216)          // tile surface
    var s2 = Color(0xFF1A191E)          // raised
    var line = Color(0xFF26242B)        // hairline
    var line2 = Color(0xFF36333B)       // hairline, stronger

    /** The accent. Named red because that is what it was; it is whatever the owner chose. */
    var red = Color(0xFFD8433C)
    var redDim = Color(0xFF8E3C38)
    var ember = Color(0xFFFF6A5E)
    var glow = Color(0x38D8433C)

    var tx = Color(0xFFF1EDEA)
    var txDim = Color(0xFF8F8884)
    var txFaint = Color(0xFF55504D)
    var chrome = Color(0xFFC7CACF)       // polished alloy - numerics, metadata
    val amber = Color(0xFFC99A4E)
    val go = Color(0xFF6E9C6A)
    val spotify = Color(0xFF1DB954)

    const val INK = "ink"
    const val CONTRAST = "contrast"

    /** The accents offered in Setup, first the one the dash was drawn with. */
    val ACCENTS = listOf(
        "Celica red" to "D8433C", "Racing red" to "E5372E", "Amber" to "E0A040", "Ice" to "5FB8E8",
        "Mint" to "58C79A", "Violet" to "9B7BEA", "White" to "F1EDEA",
    )
    val BACKGROUNDS = listOf(
        "Ink" to "0E0D0F", "Black" to "000000", "Navy" to "0A0F1C", "Forest" to "0A1410", "Slate" to "15171C", "Paper" to "F2EEE8",
    )

    fun apply(look: String, accent: String? = null, background: String? = null) {
        if (look == CONTRAST) {
            bg = Color(0xFF000000); s1 = Color(0xFF050505); s2 = Color(0xFF0D0D0D)
            line = Color(0xFF1F1F1F); line2 = Color(0xFF303030)
            red = Color(0xFFE5372E); redDim = Color(0xFF8F2620); ember = Color(0xFFFF5A4E); glow = Color(0x40E5372E)
            tx = Color(0xFFFFFFFF); txDim = Color(0xFFA3A3A3); txFaint = Color(0xFF5C5C5C); chrome = Color(0xFFD6D6D6)
        } else {
            bg = Color(0xFF0E0D0F); s1 = Color(0xFF131216); s2 = Color(0xFF1A191E)
            line = Color(0xFF26242B); line2 = Color(0xFF36333B)
            red = Color(0xFFD8433C); redDim = Color(0xFF8E3C38); ember = Color(0xFFFF6A5E); glow = Color(0x38D8433C)
            tx = Color(0xFFF1EDEA); txDim = Color(0xFF8F8884); txFaint = Color(0xFF55504D); chrome = Color(0xFFC7CACF)
        }
        parse(background)?.let { b ->
            bg = b
            val light = b.luminance() > 0.5f
            val toward = if (light) Color.Black else Color.White
            s1 = mix(b, toward, if (light) 0.035f else 0.025f)
            s2 = mix(b, toward, if (light) 0.07f else 0.06f)
            line = mix(b, toward, if (light) 0.13f else 0.11f)
            line2 = mix(b, toward, if (light) 0.22f else 0.18f)
            if (light) { tx = Color(0xFF15131A); txDim = Color(0xFF5E5A63); txFaint = Color(0xFFA9A4AA); chrome = Color(0xFF3E4046) }
            else if (look != CONTRAST) { tx = Color(0xFFF1EDEA); txDim = Color(0xFF8F8884); txFaint = Color(0xFF55504D); chrome = Color(0xFFC7CACF) }
        }
        parse(accent)?.let { a ->
            red = a
            redDim = mix(a, bg, 0.42f)
            ember = mix(a, Color.White, 0.22f)
            glow = a.copy(alpha = 0.22f)
        }
    }

    /**
     * The map style's colours, keyed by the literal the style was written in (the ink look's),
     * so one pass of replacement re-dresses it for any look, accent or background.
     */
    fun mapPalette(): Map<String, String> = mapOf(
        "#0E0D0F" to hex(bg), "#121114" to hex(mix(bg, s1, 0.5f)), "#151417" to hex(s1), "#08080A" to hex(mix(bg, Color.Black, 0.35f)),
        "#19181C" to hex(s2), "#26242B" to hex(line), "#232127" to hex(mix(s2, line, 0.6f)), "#36333B" to hex(line2),
        "#55504D" to hex(txFaint), "#8E3C38" to hex(redDim), "#D8433C" to hex(red), "#8F8884" to hex(txDim), "#F1EDEA" to hex(tx),
    )

    /** "D8433C" or "#D8433C" to a colour; null for anything else. */
    fun parse(hex: String?): Color? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return Color(0xFF000000 or h.toLong(16))
    }

    fun hex(c: Color): String = "#%02X%02X%02X".format((c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt())

    private fun mix(a: Color, b: Color, t: Float) = Color(
        a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, 1f,
    )
}
