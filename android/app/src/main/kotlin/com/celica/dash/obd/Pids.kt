package com.celica.dash.obd

/** Mode 01 PIDs the dash polls, and how their bytes decode. Formulas per SAE J1979. */
object Pids {
    const val COOLANT = 0x05
    const val SHORT_TRIM_1 = 0x06
    const val LONG_TRIM_1 = 0x07
    const val RPM = 0x0C
    const val SPEED = 0x0D
    const val INTAKE = 0x0F
    const val THROTTLE = 0x11
    const val MAF = 0x10

    fun rpm(b: IntArray) = ((b[0] * 256) + b[1]) / 4
    fun speedMph(b: IntArray) = b[0] * 0.621371f
    fun tempC(b: IntArray) = b[0] - 40
    fun percent(b: IntArray) = b[0] * 100 / 255
    fun trimPct(b: IntArray) = (b[0] - 128) * 100f / 128f
    /** Air into the engine, grams a second. Fuel is that over the ideal mixture. */
    fun mafGps(b: IntArray) = ((b[0] * 256) + b[1]) / 100f

    /**
     * Mode 03 reply bytes -> codes. Each code is two bytes; the top two bits pick the letter.
     * Zeros are padding on the old K-line frames.
     */
    fun dtcs(b: IntArray): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i + 1 < b.size) {
            val a = b[i]; val c = b[i + 1]
            i += 2
            if (a == 0 && c == 0) continue
            val letter = "PCBU"[(a shr 6) and 3]
            val d1 = (a shr 4) and 3
            out += "%c%d%X%02X".format(letter, d1, a and 0xF, c)
        }
        return out
    }
}
