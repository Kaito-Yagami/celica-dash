package com.celica.dash.ui.map

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.data.DriveSummary
import com.celica.dash.data.Place
import com.celica.dash.location.Fix
import com.celica.dash.location.bearingDeg
import com.celica.dash.location.compass
import com.celica.dash.location.haversineM
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.LabelWide
import com.celica.dash.ui.theme.Title
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the finger landed on: one of the owner's pins, a fuel stop from the tiles, or a held spot. */
sealed class Pick {
    class Pin(val place: Place) : Pick()
    class Found(val name: String, val kind: String, val lat: Double, val lon: Double) : Pick()
    class Spot(val lat: Double, val lon: Double) : Pick()
}

private val dayFmt get() = Units.date("EEE d MMM · HH:mm")

fun fmtDuration(ms: Long): String {
    val s = ms / 1000; val h = s / 3600; val m = (s % 3600) / 60
    return if (h > 0) "%dH %02dM".format(h, m) else "%dM %02dS".format(m, s % 60)
}

/** "2.3 MI · NE" from the car to a point, or nothing without a fix. */
fun fromCar(fix: Fix?, lat: Double, lon: Double): String? {
    val f = fix ?: return null
    val mi = haversineM(f.lat, f.lon, lat, lon) / 1609.344
    val d = if (mi < 10) "%.1f MI".format(mi) else "%.0f MI".format(mi)
    return "$d · ${compass(bearingDeg(f.lat, f.lon, lat, lon))}"
}

/**
 * The panel that slides over the right of the map: recorded drives to replay, and pins.
 */
@Composable
fun BoxScope.MapSheet(
    vm: com.celica.dash.ui.DashViewModel,
    drives: List<DriveSummary>, places: List<Place>, fix: Fix?,
    onReplay: (Long) -> Unit, onDeleteDrive: (Long) -> Unit,
    onGoTo: (Place) -> Unit, onDeletePlace: (Long) -> Unit, onPinHere: () -> Unit, onNavigate: (Place) -> Unit,
    onClose: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(
        Modifier.align(Alignment.CenterEnd).width(panelWidth(372.dp, 0.62f)).fillMaxHeight().background(Ink.s1.copy(alpha = 0.97f))
            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line2, Offset(w / 2, 0f), Offset(w / 2, size.height), w) }
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Row(Modifier.fillMaxWidth().height(46.dp).drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }, verticalAlignment = Alignment.CenterVertically) {
            listOf("DRIVES", "PLACES", "MAP").forEachIndexed { i, t ->
                val on = tab == i
                Box(
                    Modifier.fillMaxHeight().clickable { tab = i }.padding(horizontal = 18.dp).drawBehind {
                        if (on) drawRect(Ink.red, Offset(0f, size.height - 2.dp.toPx()), Size(size.width, 2.dp.toPx()))
                    },
                    contentAlignment = Alignment.Center,
                ) { Text(t, style = LabelWide.copy(color = if (on) Ink.tx else Ink.txDim)) }
            }
            Box(Modifier.weight(1f))
            Box(Modifier.padding(end = 6.dp)) { MapButton(Glyph.CLOSE, size = 34, onClick = onClose) }
        }
        when (tab) {
            0 -> DrivesList(vm, drives, onReplay, onDeleteDrive)
            1 -> PlacesList(places, fix, onGoTo, onDeletePlace, onPinHere, onNavigate)
            else -> MapTab(vm)
        }
    }
}

/** What the map itself draws: the icons to keep on, and the words that can come off. */
@Composable
private fun MapTab(vm: com.celica.dash.ui.DashViewModel) {
    val icons by vm.mapIcons.collectAsStateWithLifecycle()
    val streets by vm.mapStreets.collectAsStateWithLifecycle()
    val places by vm.mapPlaces.collectAsStateWithLifecycle()
    val buildings by vm.mapBuildings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text("ICONS", style = Label, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 2.dp))
        Text(
            "Kept on the map whether or not they have been searched for - everything of that kind within the screen.",
            style = Body, modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
        )
        com.celica.dash.search.PlaceSearch.CATEGORIES.forEach { (cat, kinds) ->
            SwitchRow(cat, kinds.take(3).joinToString(", ") { it.replace('_', ' ') }, cat in icons) { vm.toggleMapIcon(cat) }
        }
        Text("WORDS", style = Label, modifier = Modifier.padding(start = 18.dp, top = 16.dp, bottom = 2.dp))
        SwitchRow("Street names", "the names along the roads", streets) { vm.setMapWords(streets = !streets) }
        SwitchRow("Place names", "towns, villages and districts", places) { vm.setMapWords(places = !places) }
        SwitchRow("Buildings", "the blocks, flat and in 3D", buildings) { vm.setMapWords(buildings = !buildings) }
        Box(Modifier.height(16.dp))
    }
}

/** One line with a small red lamp at the end, on when it is on. */
@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick)
            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
            .padding(start = 18.dp, end = 18.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Title.copy(fontSize = 15.sp, color = if (on) Ink.tx else Ink.txDim))
            Text(note, style = Data, maxLines = 1)
        }
        Box(Modifier.width(34.dp).height(18.dp).background(if (on) Ink.redDim else Ink.s2), contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart) {
            Box(Modifier.width(16.dp).height(18.dp).background(if (on) Ink.red else Ink.line2))
        }
    }
}

@Composable
private fun DrivesList(vm: com.celica.dash.ui.DashViewModel, drives: List<DriveSummary>, onReplay: (Long) -> Unit, onDelete: (Long) -> Unit) {
    val armed by vm.ghostArmed.collectAsStateWithLifecycle()
    if (drives.isEmpty()) {
        Column(Modifier.padding(20.dp)) {
            Text("NO DRIVES YET", style = Label.copy(color = Ink.tx))
            Text("Tap ● on the map to record one. Stop it and it lands here - with the trail coloured by speed and the car driving it again.", style = Body, modifier = Modifier.padding(top = 8.dp))
        }
        return
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        items(drives, key = { it.id }) { d ->
            Row(
                Modifier.fillMaxWidth().clickable { onReplay(d.id) }
                    .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                    .padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text((d.name ?: dayFmt.format(Date(d.startMs))).uppercase(), style = Label)
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 4.dp)) {
                        Text(Units.dist1(d.distM), style = Title)
                        Text("  ${Units.distUnit}", style = Data, modifier = Modifier.padding(bottom = 3.dp))
                        Text("   ${fmtDuration(d.durationMs)}", style = Data.copy(color = Ink.tx), modifier = Modifier.padding(bottom = 3.dp))
                    }
                    Text(
                        "MAX ${Units.speedInt(d.maxMph)} ${Units.speedUnit}" + (if (d.liftMs > 0 && CarSpec.hasLift) " · LIFT ${fmtDuration(d.liftMs)}" else "") + " · ${d.points} PTS",
                        style = Data.copy(fontSize = 10.sp), modifier = Modifier.padding(top = 3.dp),
                    )
                }
                var confirm by remember(d.id) { mutableStateOf(false) }
                if (confirm) {
                    FlatButton("Delete", hot = true) { onDelete(d.id) }
                    Box(Modifier.width(6.dp))
                    FlatButton("Keep") { confirm = false }
                } else {
                    // arming this run as the ghost: the chase starts when the car reaches its start
                    MapButton(Glyph.GHOST, hot = armed == d.id, size = 40) {
                        if (armed == d.id) vm.disarmGhost() else vm.armGhost(d.id)
                    }
                    Box(Modifier.width(6.dp))
                    MapButton(Glyph.PLAY, size = 40) { onReplay(d.id) }
                    Box(Modifier.width(6.dp))
                    MapButton(Glyph.TRASH, size = 40) { confirm = true }
                }
            }
        }
    }
}

@Composable
private fun PlacesList(places: List<Place>, fix: Fix?, onGoTo: (Place) -> Unit, onDelete: (Long) -> Unit, onPinHere: () -> Unit, onNavigate: (Place) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(14.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatButton("◆ Pin here", hot = true, enabled = fix != null, onClick = onPinHere)
            Text("or hold a spot on the map", style = Body)
        }
        if (places.isEmpty()) {
            Text("No pins yet.", style = Body, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp))
            return
        }
        LazyColumn(Modifier.fillMaxWidth()) {
            items(places, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { onGoTo(p) }
                        .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                        .padding(start = 18.dp, end = 8.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = Title.copy(fontSize = 16.sp))
                        Text(fromCar(fix, p.lat, p.lon) ?: "%.4f  %.4f".format(p.lat, p.lon), style = Data, modifier = Modifier.padding(top = 3.dp))
                    }
                    var confirm by remember(p.id) { mutableStateOf(false) }
                    if (confirm) {
                        FlatButton("Delete", hot = true) { onDelete(p.id) }
                        Box(Modifier.width(6.dp))
                        FlatButton("Keep") { confirm = false }
                    } else {
                        MapButton(text = "GO", hot = true, size = 40, enabled = fix != null) { onNavigate(p) }
                        Box(Modifier.width(6.dp))
                        MapButton(Glyph.TRASH, size = 40) { confirm = true }
                    }
                }
            }
        }
    }
}

/** A small card at the bottom of the map for whatever was tapped. */
@Composable
fun BoxScope.PickCard(pick: Pick, fix: Fix?, onSavePin: (String, Double, Double) -> Unit, onDelete: (Long) -> Unit, onRename: (Place) -> Unit, onNavigate: (String, Double, Double) -> Unit, canRoute: Boolean, onClose: () -> Unit) {
    val (name, lat, lon) = when (pick) {
        is Pick.Pin -> Triple(pick.place.name, pick.place.lat, pick.place.lon)
        is Pick.Found -> Triple(pick.name, pick.lat, pick.lon)
        is Pick.Spot -> Triple("Dropped pin", pick.lat, pick.lon)
    }
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).background(Ink.bg.copy(alpha = 0.92f))
            .drawBehind { drawRect(Ink.red, Offset.Zero, Size(3.dp.toPx(), size.height)) }
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text(
            when (pick) { is Pick.Pin -> "PIN"; is Pick.Spot -> "SPOT"; is Pick.Found -> pick.kind.replace('_', ' ').uppercase().ifBlank { "PLACE" } },
            style = Label.copy(color = Ink.red, fontWeight = FontWeight.SemiBold),
        )
            Text(name, style = Title.copy(fontSize = 17.sp))
            Text(fromCar(fix, lat, lon) ?: "%.4f  %.4f".format(lat, lon), style = Data)
        }
        FlatButton(if (canRoute) "Go" else "No routing data", hot = canRoute, enabled = canRoute && fix != null) { onNavigate(name, lat, lon) }
        when (pick) {
            is Pick.Found -> FlatButton("Save pin") { onSavePin(name, lat, lon) }
            is Pick.Spot -> FlatButton("Save pin") { onSavePin("", lat, lon) }
            is Pick.Pin -> {
                FlatButton("Rename") { onRename(pick.place) }
                MapButton(Glyph.TRASH, size = 36) { onDelete(pick.place.id) }
            }
        }
        MapButton(Glyph.CLOSE, size = 36, onClick = onClose)
    }
}

/** Naming a pin: one mono field and two buttons, in the middle of the map. */
@Composable
fun BoxScope.NameDialog(title: String, initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    Box(Modifier.matchParentSize().background(Ink.bg.copy(alpha = 0.55f)).clickable(onClick = onCancel))
    Column(
        Modifier.align(Alignment.Center).width(panelWidth(360.dp, 0.8f)).background(Ink.s1)
            .drawBehind { drawRect(Ink.red, Offset.Zero, Size(size.width, 2.dp.toPx())) }
            .pointerInput(Unit) { detectTapGestures {} }.padding(18.dp),
    ) {
        Text(title.uppercase(), style = Label.copy(color = Ink.tx))
        Box(Modifier.fillMaxWidth().padding(top = 12.dp).background(Ink.s2).padding(12.dp, 10.dp)) {
            BasicTextField(text, { text = it }, textStyle = Title.copy(fontSize = 18.sp), cursorBrush = SolidColor(Ink.red), singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatButton("Save", hot = true, enabled = text.isNotBlank()) { onDone(text.trim()) }
            FlatButton("Cancel", onClick = onCancel)
        }
    }
}
