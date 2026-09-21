package com.celica.dash.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A scripted drive for when there is no dongle: idle, a pull through lift, cruise, stop. Plays
 * the whole UI - readouts, lift line, wheel spin - so the dash can be judged without a car.
 */
object DemoDrive {
    fun flow(): Flow<VehicleState> = flow {
        var coolant = 62f
        var t = 0f
        while (true) {
            t += 0.1f
            val phase = t % 40f
            val (speed, rpm) = when {
                phase < 6f -> 0f to 912f                                          // idle
                phase < 16f -> {                                                    // pull, 2nd then 3rd
                    val u = (phase - 6f) / 10f
                    val s = 70f * u * u
                    val r = if (u < 0.55f) 2200f + 6000f * (u / 0.55f) else 4200f + 3600f * ((u - 0.55f) / 0.45f)
                    s to min(r, 8100f)
                }
                phase < 30f -> 62f + 4f * sin(phase) to 3300f + 200f * sin(phase * 0.7f)   // cruise
                else -> max(0f, 62f - 62f * ((phase - 30f) / 8f)) to max(912f, 3300f - 2400f * ((phase - 30f) / 8f))
            }
            coolant = min(86f, coolant + 0.02f)
            emit(VehicleState(
                speedMph = speed, rpm = rpm.toInt(), coolantC = coolant.toInt(), intakeC = 18,
                throttlePct = (rpm / 82f).toInt().coerceIn(0, 100), shortTrimPct = 1.2f, longTrimPct = -2.5f,
                obdLive = true, gpsLive = true,
            ))
            delay(100)
        }
    }
}
