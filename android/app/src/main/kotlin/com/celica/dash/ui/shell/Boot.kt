package com.celica.dash.ui.shell

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.WordmarkStyle
import kotlinx.coroutines.delay

/** One line of the system check: what, and its answer. */
data class BootLine(val key: String, val value: String, val hot: Boolean = false)

/**
 * The boot ceremony. Black, a mono system check typed in line by line, then it gets out of the
 * way and the car draws itself in (that part is the renderer's `reveal`, driven by the caller).
 * Tap to skip. Pure theatre, and the thing passengers will film.
 */
@Composable
fun BootOverlay(lines: List<BootLine>, onDone: () -> Unit) {
    var shown by remember { mutableIntStateOf(0) }
    val fade = remember { Animatable(1f) }
    var skip by remember { mutableStateOf(false) }

    LaunchedEffect(skip) {
        if (skip) { fade.animateTo(0f, tween(150)); onDone(); return@LaunchedEffect }
        delay(250)
        while (shown < lines.size) { shown++; delay(if (shown == lines.size) 500 else 170) }
        fade.animateTo(0f, tween(350))
        onDone()
    }

    Box(
        Modifier.fillMaxSize().alpha(fade.value).background(Ink.bg).clickable { skip = true },
    ) {
        Column(Modifier.align(Alignment.Center).width(panelWidth(560.dp, 0.94f))) {
            Row { Wordmark(WordmarkStyle.copy(fontSize = 24.sp, letterSpacing = 0.26.em)) }
            Text("SYSTEM CHECK", style = Data.copy(color = Ink.txFaint, fontSize = 12.sp), modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(30.dp))
            lines.take(shown).forEach { l ->
                Row(Modifier.padding(vertical = 5.dp)) {
                    Text(l.key.padEnd(14, ' '), style = Data.copy(color = Ink.txDim, fontSize = 14.sp))
                    Text("… ", style = Data.copy(color = Ink.txFaint, fontSize = 14.sp))
                    Text(l.value, style = Data.copy(color = if (l.hot) Ink.red else Ink.tx, fontSize = 14.sp))
                }
            }
        }
    }
}
