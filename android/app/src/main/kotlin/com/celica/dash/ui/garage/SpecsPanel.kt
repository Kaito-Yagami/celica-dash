package com.celica.dash.ui.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.KeyValue
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Title

/** Torque, clearances, fluids - every row carries the manual page it was read from. */
@Composable
fun SpecsPanel(vm: DashViewModel, openManualPage: (Int) -> Unit) {
    val groups = remember { vm.site.specs }
    var g by rememberSaveable { mutableIntStateOf(0) }
    val cur = groups.getOrNull(g) ?: run { EmptyPanel("No specifications in this car pack."); return }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(200.dp).fillMaxHeight().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w) }) {
            groups.forEachIndexed { i, grp ->
                val on = i == g
                Column(
                    Modifier.fillMaxWidth().clickable { g = i }.background(if (on) Ink.s2 else Color.Transparent)
                        .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                        .padding(14.dp, 12.dp),
                ) {
                    Text(grp.name, style = Body.copy(color = if (on) Ink.tx else Ink.txDim))
                    Text((grp.sub ?: "") + "  · ${grp.rows.size}", style = Label)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(18.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cur.name, style = Title)
                Spacer(Modifier.weight(1f))
                if (cur.fig != null) FlatButton("manual p.${cur.fig}") { openManualPage(cur.fig) }
            }
            Text("Read off the rendered figure by eye; the PDF's text layer is not trusted. Tap a row to open its page.", style = Body.copy(color = Ink.txFaint), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(cur.rows) { r ->
                    KeyValue(r.k, r.v, r.v2, r.note, onClick = r.fig?.let { p -> { openManualPage(p) } })
                }
            }
        }
    }
}

/** The inspection checklist, ticked on the unit and kept until cleared. */
@Composable
fun ChecksPanel(vm: DashViewModel, openManualPage: (Int) -> Unit) {
    val groups = remember { vm.site.checks }
    val ticked by vm.garage.ticked.collectAsStateWithLifecycle(initialValue = emptySet())
    var g by rememberSaveable { mutableIntStateOf(0) }
    val cur = groups.getOrNull(g) ?: run { EmptyPanel("No checklist in this car pack."); return }
    val total = groups.sumOf { it.items.size }
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(200.dp).fillMaxHeight().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w) }) {
            groups.forEachIndexed { i, grp ->
                val on = i == g
                val done = grp.items.indices.count { "${grp.id}:$it" in ticked }
                Column(
                    Modifier.fillMaxWidth().clickable { g = i }.background(if (on) Ink.s2 else Color.Transparent)
                        .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                        .padding(14.dp, 12.dp),
                ) {
                    Text(grp.name, style = Body.copy(color = if (on) Ink.tx else Ink.txDim))
                    Text("${grp.code ?: ""}  · $done/${grp.items.size}", style = Label.copy(color = if (done == grp.items.size) Ink.go else Ink.txDim))
                }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(14.dp)) {
                Text("${ticked.size}/$total", style = Label.copy(color = Ink.tx))
                Spacer(Modifier.weight(1f))
                Text("RESET", style = Label.copy(color = Ink.red), modifier = Modifier.clickable { vm.clearTicked() })
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().padding(18.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(cur.name, style = Title)
                    if (cur.kind != null) Text(cur.kind, style = Label)
                }
                if (cur.fig != null) FlatButton("manual p.${cur.fig}") { openManualPage(cur.fig) }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(cur.items.size) { i ->
                    val key = "${cur.id}:$i"
                    val on = key in ticked
                    val it = cur.items[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setTicked(key, !on) }
                            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.padding(top = 2.dp).width(18.dp).height(18.dp).background(if (on) Ink.red else Ink.s2), contentAlignment = Alignment.Center) {
                            if (on) Text("✓", style = Label.copy(color = Ink.tx))
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(it.t, style = Body.copy(color = if (on) Ink.txDim else Ink.tx))
                            if (it.hint != null) Text(it.hint, style = Body.copy(color = Ink.txFaint), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}
