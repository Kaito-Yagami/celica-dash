package com.celica.dash.ui.garage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.car.Car
import com.celica.dash.data.SiteData
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Chip
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Html
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Title

private val STATUSES = listOf("open", "diagnosing", "scheduled", "fixed")

private fun severityColor(s: String) = when (s) { "urgent" -> Ink.ember; "watch" -> Color(0xFF6C8EA6); else -> Ink.red }

/**
 * Faults and jobs, mapped on the car. Left: the list. Middle: the car with a callout on each
 * live issue - a dot on the part, a kinked leader, the label held clear of the bodywork, the
 * manual's own idiom. Right: the write-up, the status, and the manual pages it cites.
 */
@Composable
fun IssuesPanel(vm: DashViewModel, openManualPage: (Int) -> Unit) {
    val issues = remember { vm.site.issues }
    val statuses by vm.garage.issueStatus.collectAsStateWithLifecycle(initialValue = emptyMap())
    var selId by rememberSaveable { mutableStateOf(issues.firstOrNull()?.id ?: "") }
    fun statusOf(i: SiteData.Issue) = statuses[i.id] ?: i.status
    val live = issues.filter { statusOf(it) != "fixed" && it.hot != null }
    val hotspots = remember(live) { live.map { floatArrayOf(it.hot!!.x, it.hot.y, it.hot.z) } }
    var projected by remember { mutableStateOf<FloatArray?>(null) }

    Row(Modifier.fillMaxSize()) {
        // ---- list ----------------------------------------------------------------------
        LazyColumn(Modifier.width(240.dp).fillMaxHeight().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w) }) {
            items(issues, key = { it.id }) { i ->
                val st = statusOf(i)
                val on = i.id == selId
                Column(
                    Modifier.fillMaxWidth().clickable { selId = i.id }.background(if (on) Ink.s2 else Color.Transparent)
                        .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                        .padding(14.dp, 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (st == "fixed") Ink.txFaint else severityColor(i.severity)))
                        Spacer(Modifier.width(8.dp))
                        Text(i.title, style = Body.copy(color = if (st == "fixed") Ink.txDim else Ink.tx, fontWeight = FontWeight.SemiBold), maxLines = 2)
                    }
                    Row(Modifier.padding(top = 5.dp, start = 15.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(i.area.uppercase(), style = Label)
                        Text("· $st".uppercase(), style = Label.copy(color = if (st == "fixed") Ink.go else Ink.txDim))
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            // ---- the car, in its own window along the top ------------------------------
            Box(
                Modifier.fillMaxWidth().height(250.dp).clipToBounds()
                    .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
            ) {
                Car(speedMph = 0f, modifier = Modifier.fillMaxSize(), hotspots = hotspots, onProjected = { projected = it })
                Callouts(live, projected, selId, onPick = { selId = it })
                Text("${live.size} OPEN · DRAG TO TURN", style = Label.copy(color = Ink.txFaint), modifier = Modifier.align(Alignment.TopStart).padding(14.dp, 10.dp))
            }
            // ---- the write-up below -----------------------------------------------------
            val sel = issues.firstOrNull { it.id == selId }
            if (sel != null) {
                val st = statusOf(sel)
                Column(Modifier.fillMaxSize().padding(18.dp, 12.dp).verticalScroll(rememberScrollState())) {
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(sel.title, style = Title)
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Chip(sel.severity, severityColor(sel.severity))
                                Chip(sel.area)
                                if (sel.since.isNotEmpty()) Chip(sel.since)
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            STATUSES.forEach { s -> FlatButton(s, hot = s == st) { vm.setIssueStatus(sel.id, s) } }
                        }
                    }
                    Text(sel.blurb, style = Body.copy(color = Ink.txDim), modifier = Modifier.padding(top = 10.dp))
                    // two columns of prose so it reads at arm's length without a scroll marathon
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Column(Modifier.weight(1f)) {
                            listOf("Symptom" to sel.symptom, "Likely cause" to sel.cause).forEach { (h, html) ->
                                if (html.isNotBlank()) { Text(h.uppercase(), style = Label, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)); Html(html) }
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            if (sel.check.isNotEmpty()) {
                                Text("HOW TO CHECK", style = Label, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                                sel.check.forEachIndexed { n, st ->
                                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                                        Text("%02d".format(n + 1), style = Label.copy(color = Ink.red), modifier = Modifier.width(28.dp).padding(top = 2.dp))
                                        Column {
                                            Text(st.t, style = Body.copy(color = Ink.tx, fontSize = 13.sp))
                                            if (st.hint != null) Text(st.hint, style = Body.copy(color = Ink.txDim, fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
                                        }
                                    }
                                }
                            }
                            if (sel.fix.isNotBlank()) { Text("THE FIX", style = Label, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)); Html(sel.fix) }
                            if (sel.figs.isNotEmpty()) {
                                Text("IN THE MANUAL", style = Label, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
                                sel.figs.forEach { f ->
                                    Row(Modifier.fillMaxWidth().clickable { openManualPage(f.page) }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                                        Text("p.${f.page}", style = Label.copy(color = Ink.red), modifier = Modifier.width(56.dp))
                                        Text(f.caption, style = Body.copy(color = Ink.tx))
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}

/** Dot on the part, leader kinked away from the middle of the frame, label clear of the car. */
@Composable
private fun Callouts(live: List<SiteData.Issue>, projected: FloatArray?, selId: String, onPick: (String) -> Unit) {
    if (projected == null || projected.size < live.size * 3) return
    val labelPaint = remember {
        android.graphics.Paint().apply { isAntiAlias = true; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; letterSpacing = 0.08f }
    }
    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            // where each label wants to sit, then nudge labels on the same side apart
            val minGap = 18.dp.toPx()
            val want = live.indices.map { i ->
                val ax = projected[i * 3]; val ay = projected[i * 3 + 1]
                val vx = if (ax < size.width / 2) -1f else 1f
                val vy = if (ay < size.height / 2) -1f else 1f
                floatArrayOf(vx, ay + vy * 22.dp.toPx())
            }
            val ky = FloatArray(live.size) { want[it][1] }
            for (side in listOf(-1f, 1f)) {
                val idx = live.indices.filter { want[it][0] == side }.sortedBy { want[it][1] }
                for (j in 1 until idx.size) {
                    val a = idx[j - 1]; val b = idx[j]
                    if (ky[b] - ky[a] < minGap) ky[b] = ky[a] + minGap
                }
            }
            live.forEachIndexed { i, iss ->
                val ax = projected[i * 3]; val ay = projected[i * 3 + 1]
                val facing = projected[i * 3 + 2] > 0.5f
                val on = iss.id == selId
                val alpha = if (facing) 1f else 0.3f
                val vx = want[i][0]
                val kx = ax + vx * 22.dp.toPx(); val ky = ky[i].coerceIn(12.dp.toPx(), size.height - 12.dp.toPx())
                val ex = kx + vx * 34.dp.toPx()
                val col = (if (on) Ink.ember else Ink.txDim).copy(alpha = alpha)
                drawPath(Path().apply { moveTo(ax, ay); lineTo(kx, ky); lineTo(ex, ky) }, col, style = Stroke(1.dp.toPx()))
                drawCircle(Ink.red.copy(alpha = alpha), 4.dp.toPx(), Offset(ax, ay))
                if (on) drawCircle(Ink.glow, 9.dp.toPx(), Offset(ax, ay))
                // label
                val text = (iss.hot?.label ?: iss.area).uppercase()
                labelPaint.textSize = 11.sp.toPx()
                labelPaint.color = (if (on) Ink.tx else Ink.tx.copy(alpha = alpha * 0.85f)).toArgb()
                val tw = labelPaint.measureText(text)
                val tx = (if (vx > 0) ex + 6.dp.toPx() else ex - 6.dp.toPx() - tw).coerceIn(4.dp.toPx(), size.width - tw - 4.dp.toPx())
                drawRect(Ink.bg.copy(alpha = 0.85f * alpha), Offset(tx - 5.dp.toPx(), ky - 9.dp.toPx()), androidx.compose.ui.geometry.Size(tw + 10.dp.toPx(), 18.dp.toPx()))
                drawContext.canvas.nativeCanvas.drawText(text, tx, ky + 4.dp.toPx(), labelPaint)
            }
        }
        // hit targets on the labels
        live.forEachIndexed { i, iss ->
            val ax = projected[i * 3]; val ay = projected[i * 3 + 1]
            androidx.compose.foundation.layout.Box(
                Modifier
                    .padding(start = (ax / androidx.compose.ui.platform.LocalDensity.current.density - 22).dp.coerceAtLeast(0.dp), top = (ay / androidx.compose.ui.platform.LocalDensity.current.density - 22).dp.coerceAtLeast(0.dp))
                    .size(44.dp)
                    .clickable { onPick(iss.id) }
            )
        }
    }
}

private fun Color.toArgb() = android.graphics.Color.argb((alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())
