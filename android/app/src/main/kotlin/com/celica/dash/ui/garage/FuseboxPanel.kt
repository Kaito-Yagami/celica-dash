package com.celica.dash.ui.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.data.SiteData
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Chip
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Display
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import com.celica.dash.ui.theme.Title

/** The filters across the top, as on the site: all, tapped, then one per circuit class. */
private val FILTERS = listOf("all" to "All", "tap" to "Tapped", "bat" to "Constant", "acc" to "Accessory", "ig" to "Ignition", "ill" to "Illumination")

/** Standard blade-fuse body colours, for the "10 A · red body" line. */
private fun bodyColour(amp: Float?) = when (amp) {
    3f -> "violet"; 5f -> "tan"; 7.5f -> "brown"; 10f -> "red"; 15f -> "blue"; 20f -> "yellow"; 25f -> "clear"; 30f -> "green"; 40f -> "orange"; else -> null
}

/**
 * The fusebox as it is on this car, drawn the way the site draws it: the label's own layout,
 * slot cards with the number over the rating, a red dot on the fitted taps, filters to light up
 * a circuit class, and the slot's story on the right.
 */
@Composable
fun FuseboxPanel(vm: DashViewModel, openManualPage: (Int) -> Unit) {
    val fb = remember { vm.site.fusebox } ?: run { EmptyPanel("No fusebox in this car pack."); return }
    var sel by rememberSaveable { mutableIntStateOf(fb.layout.first) }
    var filter by rememberSaveable { mutableStateOf("all") }
    val byN = remember { fb.slots.associateBy { it.n } }
    val cur = byN[sel]
    fun lit(s: SiteData.FuseSlot) = when (filter) { "all" -> true; "tap" -> s.n in fb.taps; else -> s.cls == filter }

    Row(Modifier.fillMaxSize()) {
        // ---- the map --------------------------------------------------------------------
        Column(Modifier.weight(1f).fillMaxHeight().padding(18.dp, 14.dp, 18.dp, 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fb.boxName, style = Title.copy(fontSize = 18.sp))
                Spacer(Modifier.width(12.dp))
                Text(listOf(fb.caption, "TAP ANY FUSE").filter { it.isNotBlank() }.joinToString(" · "), style = Label.copy(color = Ink.txFaint), modifier = Modifier.padding(bottom = 3.dp))
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FILTERS.forEach { (id, name) ->
                    val on = filter == id
                    Box(
                        Modifier.background(if (on) Ink.red else Ink.s2).border(1.dp, if (on) Ink.red else Ink.line2).clickable { filter = id }.padding(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text(name.uppercase(), style = Label.copy(fontSize = 9.sp, color = if (on) Ink.tx else Ink.txDim, letterSpacing = 0.1.sp)) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().weight(1f).background(Ink.bg.copy(alpha = 0.5f)).border(1.dp, Ink.line).padding(14.dp)) {
                // the label's own arrangement, from the pack: wide slots first, then the rows of
                // full-size cards, then the rows of small ones under their own caption
                val lay = fb.layout
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (lay.wide.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { lay.wide.forEach { n -> Slot(byN[n], sel == n, fb.taps, ::lit, wide = true) { sel = n } } }
                    lay.rows.forEachIndexed { i, row ->
                        if (i > 0 || lay.wide.isNotEmpty()) Spacer(Modifier.height(if (i == 0) 10.dp else 4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { n -> Slot(byN[n], sel == n, fb.taps, ::lit) { sel = n } } }
                    }
                    if (lay.small.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        if (lay.smallCaption.isNotBlank()) { Text(lay.smallCaption, style = Label.copy(fontSize = 9.sp, color = Ink.txFaint)); Spacer(Modifier.height(6.dp)) }
                    }
                    lay.small.forEachIndexed { i, row ->
                        if (i > 0) Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { n -> Slot(byN[n], sel == n, fb.taps, ::lit, small = true) { sel = n } } }
                    }
                }
            }
        }
        // ---- the detail -----------------------------------------------------------------
        Column(
            Modifier.width(panelWidth(300.dp, 0.42f)).fillMaxHeight()
                .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(w / 2, 0f), Offset(w / 2, size.height), w) }
                .padding(18.dp, 14.dp).verticalScroll(rememberScrollState())
        ) {
            if (cur == null) return@Column
            val cls = fb.classes.firstOrNull { it.id == cur.cls }
            val tapped = cur.n in fb.taps
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(if (tapped) Ink.red else Ink.s2).border(1.dp, if (tapped) Ink.red else Ink.line2), contentAlignment = Alignment.Center) {
                    Text("${cur.n}", fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = Ink.tx)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(cur.name, style = Title.copy(fontSize = 18.sp))
                    Text(
                        if (cur.amp == null) "empty slot" else listOfNotNull("${fmtAmp(cur.amp)} A", bodyColour(cur.amp)?.let { "$it body" }).joinToString(" · "),
                        style = Label.copy(color = Ink.txDim, letterSpacing = 0.08.sp),
                    )
                }
            }
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (cls != null) Chip(cls.name, Ink.tx)
                if (cur.conf != null) Chip(if (cur.conf == "hi") "confident" else "unsure", if (cur.conf == "hi") Ink.txDim else Ink.amber)
                if (tapped) Chip("tap fitted", Ink.red, Ink.red.copy(alpha = 0.15f))
            }
            if (cls != null) Text(cls.desc, style = Body.copy(color = Ink.txDim), modifier = Modifier.padding(top = 12.dp))
            if (cur.what != null) Text(cur.what, style = Body.copy(color = Ink.tx, fontWeight = FontWeight.SemiBold, fontSize = 13.sp), modifier = Modifier.padding(top = 12.dp))
            if (cur.load != null) {
                Text("ESTIMATED EXISTING LOAD", style = Label, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("%.1f – %.1f".format(cur.load.first, cur.load.second), fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink.chrome)
                    Text("  A", style = Label, modifier = Modifier.padding(bottom = 3.dp))
                    if (cur.amp != null) Text("   of ${fmtAmp(cur.amp)}", style = Label, modifier = Modifier.padding(bottom = 3.dp))
                }
            }
            if (cur.note != null) {
                Text("NOTE", style = Label, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                Text(cur.note, style = Body.copy(color = Ink.txDim))
            }
            Spacer(Modifier.height(18.dp))
            if (fb.manualFig != null) FlatButton("Manual page · the US box, for context") { openManualPage(fb.manualFig) }
        }
    }
}

private fun fmtAmp(a: Float) = if (a % 1f == 0f) a.toInt().toString() else a.toString()

/** A slot card: the number, the rating under it, a red dot on a tap. Dims when filtered out. */
@Composable
private fun Slot(s: SiteData.FuseSlot?, selected: Boolean, taps: Set<Int>, lit: (SiteData.FuseSlot) -> Boolean, small: Boolean = false, wide: Boolean = false, onClick: () -> Unit) {
    if (s == null) return
    val empty = s.amp == null
    val tapped = s.n in taps
    val on = lit(s)
    val w = if (wide) 72.dp else if (small) 50.dp else 46.dp
    val h = if (small) 42.dp else 46.dp
    val fill = when { selected -> Ink.red; tapped -> Ink.red.copy(alpha = 0.16f); else -> Ink.s2 }
    val edge = when { selected -> Ink.red; tapped -> Ink.redDim; else -> Ink.line2 }
    Box(
        Modifier.width(w).height(h).clip(RoundedCornerShape(3.dp)).background(fill).border(1.dp, edge, RoundedCornerShape(3.dp)).clickable(onClick = onClick)
            .then(if (!on) Modifier.background(Ink.bg.copy(alpha = 0.72f)) else Modifier),
    ) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${s.n}", fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = if (small) 13.sp else 15.sp, lineHeight = 16.sp, color = if (empty) Ink.txFaint else Ink.tx)
            Text(if (empty) "—" else fmtAmp(s.amp!!) + "A", fontFamily = Mono, fontSize = 9.sp, lineHeight = 11.sp, color = if (selected) Ink.tx.copy(alpha = 0.85f) else Ink.txDim)
        }
        if (tapped) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(5.dp).clip(CircleShape).background(if (selected) Ink.tx else Ink.red))
    }
}
