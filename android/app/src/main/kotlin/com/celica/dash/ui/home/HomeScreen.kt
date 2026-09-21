package com.celica.dash.ui.home

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.car.Car
import com.celica.dash.obd.VehicleState
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.NumeralUnit
import com.celica.dash.ui.theme.screenDp
import java.text.NumberFormat
import java.util.Locale

/** One of the three numbers under the car. `value` null draws an em dash. */
data class Readout(val label: String, val value: String?, val unit: String, val warm: Boolean = false)

/**
 * Home, as in the mockup: the car with three readouts on the left, music and weather stacked on
 * the right. Nothing else - the dash is a dash, not a to-do list.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    vm: DashViewModel,
    reveal: Float = 1f,
    media: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
    weather: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit = {},
) {
    val v by vm.vehicle.collectAsStateWithLifecycle()
    val readouts = readoutsFor(v)
    val liftFraction = (v.rpm ?: 0) / VehicleState.REDLINE.toFloat()

    // the right-hand column is a share of the screen, not a fixed 330, so the larger sizes fit too
    val screen = screenDp()
    val side = (screen.width * 0.34f).coerceIn(250.dp, 330.dp)
    val weatherH = (screen.height * 0.27f).coerceIn(96.dp, 150.dp)
    Row(
        Modifier.fillMaxSize().padding(start = 20.dp, top = 14.dp, end = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Tile(Modifier.weight(1f).fillMaxHeight(), fill = false) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Car(speedMph = v.speedMph ?: 0f, reveal = reveal, modifier = Modifier.fillMaxSize())
                }
                ReadoutRow(readouts, liftFraction)
            }
            // the label doubles as the demo switch: hold it to play the scripted drive
            TileLabel(CarSpec.code.ifBlank { CarSpec.name.replace("*", "") }, CarSpec.sub, Modifier.combinedClickable(onClick = {}, onLongClick = { vm.toggleDemo() }))
            if (CarSpec.note.isNotBlank()) TileNote(CarSpec.note)
        }
        Column(Modifier.width(side).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Tile(Modifier.fillMaxWidth().weight(1f)) { media() }
            Tile(Modifier.fillMaxWidth().height(weatherH)) { weather() }
        }
    }
}

private fun readoutsFor(v: VehicleState) = listOf(
    Readout("Speed", Units.speedInt(v.speedMph), Units.speedUnit),
    Readout("Engine", v.rpm?.let { Units.integers().format(it) }, "RPM"),
    Readout("Coolant", v.coolantC?.let { Units.temp(it).toString() }, Units.tempUnit, warm = v.coolantC != null && !v.warm),
)

/** Three numbers, no boxes, one hairline above; the lift line runs under the middle one. */
@Composable
private fun ReadoutRow(readouts: List<Readout>, liftFraction: Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(86.dp)
            .background(Ink.s1)
            .drawBehind {
                val w = 1.dp.toPx()
                drawLine(Ink.line, Offset(0f, w / 2), Offset(size.width, w / 2), w)
                // lift line: grey, red across the lift zone, filled white as revs climb, red once in lift
                val third = size.width / 3
                val m = 18.dp.toPx()
                val x0 = third + m; val x1 = 2 * third - m
                val h = 3.dp.toPx(); val y = size.height - h
                val liftAt = if (CarSpec.hasLift) x0 + (x1 - x0) * (VehicleState.LIFT_RPM.toFloat() / VehicleState.REDLINE) else x1
                drawRect(Ink.line2, Offset(x0, y), Size(liftAt - x0, h))
                if (CarSpec.hasLift) drawRect(Ink.redDim, Offset(liftAt, y), Size(x1 - liftAt, h))
                if (liftFraction > 0f) {
                    val f = liftFraction.coerceIn(0f, 1f)
                    val fx = x0 + (x1 - x0) * f
                    val inLift = CarSpec.hasLift && f >= VehicleState.LIFT_RPM.toFloat() / VehicleState.REDLINE
                    drawRect(if (inLift) Ink.red else Ink.tx, Offset(x0, y), Size(fx - x0, h))
                }
            }
    ) {
        readouts.forEachIndexed { i, r ->
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        if (i < readouts.size - 1) {
                            val w = 1.dp.toPx()
                            drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w)
                        }
                    }
                    .padding(start = 18.dp, top = 12.dp)
            ) {
                Text(r.label.uppercase(), style = Label)
                Row(verticalAlignment = Alignment.Bottom) {
                    if (r.value == null) Text("—", style = Numeral.copy(fontFamily = com.celica.dash.ui.theme.Mono, color = Ink.txFaint))
                    else Text(r.value, style = Numeral.copy(color = if (r.warm) Ink.chrome else Ink.tx))
                    Text("  " + r.unit, style = NumeralUnit, modifier = Modifier.padding(bottom = 5.dp))
                }
            }
        }
    }
}
