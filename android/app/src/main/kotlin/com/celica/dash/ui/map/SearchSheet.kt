package com.celica.dash.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.location.Fix
import com.celica.dash.search.PlaceSearch
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.LabelWide
import com.celica.dash.ui.theme.Title
import com.celica.dash.ui.theme.panelWidth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Finding somewhere, with nothing but the unit: a word to type and a row of buttons for the
 * things a driver actually stops for. The answers come out of the map's own file (see
 * [PlaceSearch]) nearest first, and either show on the map or start a route.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.SearchSheet(
    fix: Fix?, canRoute: Boolean,
    onShow: (String, String, Double, Double) -> Unit,
    onNavigate: (String, Double, Double) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var hits by remember { mutableStateOf<List<PlaceSearch.Hit>>(emptyList()) }
    var working by remember { mutableStateOf(false) }
    val have = remember { PlaceSearch.file(ctx).exists() }

    LaunchedEffect(query, category, fix?.lat == null) {
        val kinds = PlaceSearch.CATEGORIES.firstOrNull { it.first == category }?.second ?: emptyList()
        if (query.length < 2 && kinds.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        delay(220)                                   // let the typing settle before the scan
        working = true
        hits = withContext(Dispatchers.Default) { PlaceSearch.find(ctx, query, kinds, fix) }
        working = false
    }

    Column(
        Modifier.align(Alignment.CenterEnd).width(panelWidth(372.dp, 0.62f)).fillMaxHeight().background(Ink.s1.copy(alpha = 0.97f))
            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line2, Offset(w / 2, 0f), Offset(w / 2, size.height), w) }
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("SEARCH", style = LabelWide.copy(color = Ink.tx), modifier = Modifier.padding(start = 18.dp))
            Box(Modifier.weight(1f))
            Box(Modifier.padding(end = 6.dp)) { MapButton(Glyph.CLOSE, size = 34, onClick = onClose) }
        }
        if (!have) {
            Column(Modifier.padding(20.dp)) {
                Text("NO SEARCH FILE", style = Label.copy(color = Ink.tx))
                Text("The setup helper on the PC makes it for your region - every town, village and stop; Setup fetches it over Wi-Fi.", style = Body, modifier = Modifier.padding(top = 8.dp))
            }
            return@Column
        }
        // what to look for
        Box(Modifier.fillMaxWidth().padding(14.dp, 12.dp, 14.dp, 0.dp).background(Ink.s2).padding(12.dp, 10.dp)) {
            BasicTextField(
                query, { query = it; category = null }, textStyle = Title.copy(fontSize = 18.sp), cursorBrush = SolidColor(Ink.red),
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            if (query.isEmpty()) Text("Town, village, or a name", style = Body.copy(color = Ink.txFaint))
        }
        FlowRow(Modifier.fillMaxWidth().padding(14.dp, 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaceSearch.CATEGORIES.forEach { (name, _) ->
                val on = category == name
                Box(
                    Modifier.background(if (on) Ink.red else Ink.s2).clickable { category = if (on) null else name; query = "" }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) { Text(name, style = Label.copy(color = Ink.tx, letterSpacing = 0.16.sp)) }
            }
        }
        val note = when {
            working -> "LOOKING…"
            hits.isNotEmpty() -> "${hits.size} NEAREST"
            category != null || query.length >= 2 -> "NOTHING FOUND"
            else -> if (fix == null) "NO FIX - TYPE A NAME" else "TYPE, OR PICK ONE ABOVE"
        }
        Text(note, style = Label, modifier = Modifier.padding(start = 18.dp, bottom = 6.dp))
        LazyColumn(Modifier.fillMaxWidth()) {
            itemsIndexed(hits) { i, h ->
                Row(
                    Modifier.fillMaxWidth().clickable { onShow(h.name, h.kind, h.lat, h.lon) }
                        .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                        .padding(start = 18.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(h.name, style = Title.copy(fontSize = 16.sp), maxLines = 1)
                        Text(
                            h.kind.replace('_', ' ').uppercase() + (if (fix != null) "  ·  ${com.celica.dash.data.Units.dist1(h.metres)} ${com.celica.dash.data.Units.distUnit}" else ""),
                            style = Data, modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                    MapButton(text = "GO", hot = true, size = 40, enabled = canRoute && fix != null) { onNavigate(h.name, h.lat, h.lon) }
                    Box(Modifier.width(6.dp))
                }
                if (i == hits.lastIndex) Box(Modifier.height(10.dp))
            }
        }
    }
}
