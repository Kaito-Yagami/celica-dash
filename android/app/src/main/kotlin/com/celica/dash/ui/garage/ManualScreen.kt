package com.celica.dash.ui.garage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.data.SiteData
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Chip
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The workshop manual, native: the section tree down the left, the chosen section's pages as a
 * grid of figures, full-text search across every page, and a viewer you can pinch. Page codes
 * read off the print are solid; inferred ones are shown as "≈".
 */
@Composable
fun ManualScreen(vm: DashViewModel) {
    val site = vm.site
    val sections = remember { site.sections }
    if (sections.isEmpty()) { Tile(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp)) { EmptyPanel("No manual in this car pack.") }; return }
    val index = remember { site.pageIndex.associateBy { it.page } }
    var secIdx by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Int>>(emptyList()) }
    val requested by vm.manualPage.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf<Int?>(null) }

    LaunchedEffect(requested) { requested?.let { open = it; vm.openManual(null) } }
    LaunchedEffect(query) {
        val q = query.trim().lowercase()
        results = if (q.length < 3) emptyList() else withContext(Dispatchers.Default) {
            site.pageTexts.entries.asSequence().filter { it.value.lowercase().contains(q) }.map { it.key }.take(200).toList()
        }
    }
    BackHandler(enabled = open != null) { open = null }

    Tile(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp)) {
        val page = open
        if (page != null) {
            Viewer(site, index, page, onPage = { open = it.coerceIn(0, site.manualTotal - 1) }, onClose = { open = null })
            return@Tile
        }
        Row(Modifier.fillMaxSize()) {
            // ---- sections ----------------------------------------------------------------
            LazyColumn(Modifier.width(196.dp).fillMaxHeight().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w) }) {
                items(sections.size) { i ->
                    val s = sections[i]
                    val on = i == secIdx && query.isBlank()
                    Row(
                        Modifier.fillMaxWidth().clickable { secIdx = i; query = "" }.background(if (on) Ink.s2 else Color.Transparent)
                            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                            .padding(12.dp, 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(s.code, style = Label.copy(color = if (on) Ink.red else Ink.txDim, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(30.dp))
                        Text(s.name, style = Body.copy(color = if (on) Ink.tx else Ink.txDim, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // ---- pages -------------------------------------------------------------------
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().padding(14.dp, 10.dp, 14.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).background(Ink.s2).border(1.dp, Ink.line2).padding(10.dp, 8.dp)) {
                        if (query.isEmpty()) Text("SEARCH ALL ${site.manualTotal} PAGES", style = Label.copy(color = Ink.txFaint))
                        BasicTextField(query, { query = it }, textStyle = Body.copy(color = Ink.tx), cursorBrush = SolidColor(Ink.red), singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    if (query.isNotEmpty()) Text("  ✕", style = Label.copy(color = Ink.txDim), modifier = Modifier.clickable { query = "" }.padding(6.dp))
                }
                if (query.trim().length >= 3) {
                    Text(
                        if (results.isEmpty()) "NO PAGES MATCH" else "${results.size} PAGES · text layer is damaged, so spellings vary",
                        style = Label, modifier = Modifier.padding(14.dp, 4.dp),
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(results) { p ->
                            val ix = index[p]
                            val text = site.pageTexts[p] ?: ""
                            val at = text.lowercase().indexOf(query.trim().lowercase())
                            val snippet = if (at >= 0) text.substring((at - 50).coerceAtLeast(0), (at + 90).coerceAtMost(text.length)).replace('\n', ' ') else ""
                            Row(
                                Modifier.fillMaxWidth().clickable { open = p }
                                    .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                                    .padding(14.dp, 8.dp),
                            ) {
                                Text(codeOf(ix), style = Label.copy(color = Ink.red), modifier = Modifier.width(70.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ix?.heading?.ifBlank { "p.$p" } ?: "p.$p", style = Body.copy(color = Ink.tx))
                                    Text("…$snippet…", style = Body.copy(color = Ink.txFaint, fontSize = 11.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                } else {
                    val s = sections[secIdx]
                    Row(Modifier.padding(14.dp, 4.dp, 14.dp, 6.dp), verticalAlignment = Alignment.Bottom) {
                        Text(s.name, style = Title)
                        Spacer(Modifier.width(10.dp))
                        Text("${s.code} · pages ${s.a}–${s.b} · ${s.figures} figures", style = Label)
                    }
                    LazyVerticalGrid(GridCells.Adaptive(150.dp), Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp, 4.dp, 14.dp, 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items((s.a..s.b).toList()) { p ->
                            val ix = index[p]
                            Column(Modifier.clickable { open = p }) {
                                Box(Modifier.fillMaxWidth().aspectRatio(0.72f).background(Ink.s2).border(1.dp, Ink.line)) {
                                    Figure(site.figurePath(p), sample = 8, modifier = Modifier.fillMaxSize().padding(4.dp))
                                }
                                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(codeOf(ix), style = Label.copy(color = if (ix?.printed == true) Ink.tx else Ink.txDim))
                                    Spacer(Modifier.width(6.dp))
                                    Text(ix?.heading ?: "", style = Label.copy(color = Ink.txFaint), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun codeOf(ix: SiteData.PageIndex?): String = when {
    ix == null -> ""
    ix.code.isNotEmpty() && ix.printed -> ix.code
    ix.code.isNotEmpty() -> "≈ " + ix.code
    else -> "p.${ix.page}"
}

/** One page, big, with pinch and drag; prev/next along the bottom. */
@Composable
private fun Viewer(site: SiteData, index: Map<Int, SiteData.PageIndex>, page: Int, onPage: (Int) -> Unit, onClose: () -> Unit) {
    val ix = index[page]
    var scale by remember(page) { mutableStateOf(1f) }
    var offset by remember(page) { mutableStateOf(Offset.Zero) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(14.dp, 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Chip(codeOf(ix), if (ix?.printed == true) Ink.tx else Ink.txDim)
            Chip("pdf p.${page + 1}")
            Text(ix?.heading ?: "", style = Title.copy(fontSize = 16.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            FlatButton("◀ prev", enabled = page > 0) { onPage(page - 1) }
            FlatButton("next ▶", enabled = page < site.manualTotal - 1) { onPage(page + 1) }
            FlatButton("close", hot = true) { onClose() }
        }
        Box(
            Modifier.fillMaxSize().clipToBounds().pointerInput(page) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Figure(
                site.figurePath(page), sample = 2,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            )
        }
    }
}
