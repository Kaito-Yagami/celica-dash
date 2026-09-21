package com.celica.dash.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.data.CarSpec
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.LabelWide

/**
 * A rail of sub-sections down the left of a screen: mono labels, the active one with a red bar.
 * Used inside Garage and Manual, which each hold several views.
 */
@Composable
fun Rail(items: List<String>, current: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.width(118.dp).fillMaxHeight().drawBehind {
            val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width, size.height), w)
        }
    ) {
        items.forEachIndexed { i, t ->
            val on = i == current
            Box(
                Modifier.fillMaxWidth().height(52.dp).clickable { onSelect(i) }.drawBehind {
                    val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w)
                    if (on) drawRect(Ink.red, Offset.Zero, androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height))
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(t.uppercase(), style = LabelWide.copy(color = if (on) Ink.tx else Ink.txDim, letterSpacing = 0.18.sp), modifier = Modifier.padding(start = 16.dp))
            }
        }
    }
}

/** A small mono chip: status, severity, code. */
@Composable
fun Chip(text: String, color: androidx.compose.ui.graphics.Color = Ink.txDim, fill: androidx.compose.ui.graphics.Color = Ink.s2, modifier: Modifier = Modifier) {
    Box(modifier.background(fill).padding(horizontal = 8.dp, vertical = 3.dp)) {
        Text(text.uppercase(), style = Label.copy(color = color, fontWeight = FontWeight.SemiBold, letterSpacing = 0.12.sp))
    }
}

/** A flat mono button with a hairline box. */
@Composable
fun FlatButton(text: String, modifier: Modifier = Modifier, hot: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        modifier.background(if (hot) Ink.red else Ink.s2).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), style = Label.copy(color = if (!enabled) Ink.txFaint else Ink.tx, letterSpacing = 0.16.sp))
    }
}

/**
 * The site's prose - paragraphs, bold, lists - rendered from its HTML. Divs and classes fall
 * away; the words and emphasis stay, which is what matters on a dash.
 */
@Composable
fun Html(html: String, modifier: Modifier = Modifier) {
    val cleaned = html
        .replace(Regex("<div class=\"hd\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL), "<p><b>$1</b></p>")
        .replace(Regex("</?div[^>]*>"), "")
        .replace(Regex("<figure.*?</figure>", RegexOption.DOT_MATCHES_ALL), "")
        .trim()
    val text = AnnotatedString.fromHtml(cleaned, linkStyles = TextLinkStyles(SpanStyle(color = Ink.red)))
    Text(text, style = Body.copy(color = Ink.tx, fontSize = 13.sp, lineHeight = 19.sp), modifier = modifier)
}

/** Hairline-separated key/value rows. */
@Composable
fun KeyValue(k: String, v: String, v2: String? = null, note: String? = null, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1.2f)) {
            Text(k, style = Body.copy(color = Ink.tx))
            if (note != null) Text(note, style = Body.copy(color = Ink.txFaint, fontSize = 11.sp), modifier = Modifier.padding(top = 2.dp))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(v, style = Body.copy(color = Ink.chrome, fontWeight = FontWeight.Bold))
            if (v2 != null) Text(v2, style = Label.copy(color = Ink.txDim))
        }
    }
}

/** The car's name as the strip and the boot ceremony wear it, the marked run in the accent. */
@Composable
fun Wordmark(style: TextStyle) {
    Row {
        for ((run, accent) in CarSpec.wordmarkRuns()) Text(run, style = if (accent) style.copy(color = Ink.red) else style)
    }
}
