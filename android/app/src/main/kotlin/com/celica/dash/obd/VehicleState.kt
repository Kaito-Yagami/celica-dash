package com.celica.dash.obd

import com.celica.dash.data.CarSpec

/**
 * What the dash knows about the car right now. Nulls mean "no source" and draw as an em dash.
 * Speed may come from OBD or GPS; the rest only from OBD.
 */
data class VehicleState(
    val speedMph: Float? = null,
    val rpm: Int? = null,
    val coolantC: Int? = null,
    val intakeC: Int? = null,
    val throttlePct: Int? = null,
    val mafGps: Float? = null,
    val shortTrimPct: Float? = null,
    val longTrimPct: Float? = null,
    val obdLive: Boolean = false,
    val gpsLive: Boolean = false,
) {
    /**
     * Miles to the gallon right now, from the air the engine is drawing: petrol burns at about
     * 14.7 parts air to one of fuel by mass, and a litre of it weighs 737 grams. Null below a
     * walking pace, where the figure is meaningless, and without the air reading at all.
     */
    val mpg: Float?
        get() {
            val maf = mafGps ?: return null
            val mph = speedMph ?: return null
            if (maf < 0.3f || mph < 3f) return null
            return (mph * 13.68f / maf).coerceAtMost(199f)
        }

    /** On the cams (or past the shift point); never, on a car with nothing of the kind. */
    val lift get() = CarSpec.liftRpm?.let { (rpm ?: 0) >= it } ?: false
    val warm get() = (coolantC ?: 0) >= 75

    companion object {
        /**
         * Where the tach goes hot. A car without a lift point answers one past its redline, so
         * every band drawn from here to the redline collapses to nothing and no tick turns red;
         * [CarSpec.hasLift] is the question to ask before drawing anything about it at all.
         */
        val LIFT_RPM: Int get() = CarSpec.liftRpm ?: (CarSpec.redline + 1)
        val REDLINE: Int get() = CarSpec.redline
    }
}

/**
 * Which gear the box is in, from the revs against the road speed. Null when the clutch is out of
 * the picture - stopped, idling, or nothing near enough to a ratio to be sure.
 */
fun gearOf(rpm: Int?, mph: Float?): Int? {
    val gears = CarSpec.gears ?: return null
    if (rpm == null || mph == null || mph < 4f || rpm < 900) return null
    val wheelTurnsPerSecond = mph * 0.44704 / CarSpec.circumferenceM
    val ratio = (rpm / 60.0) / (wheelTurnsPerSecond * CarSpec.finalDrive)
    var best = -1
    var bestOff = Double.MAX_VALUE
    for ((i, g) in gears.withIndex()) {
        val off = kotlin.math.abs(g - ratio) / g
        if (off < bestOff) { bestOff = off; best = i }
    }
    return if (bestOff < 0.12) best + 1 else null
}
