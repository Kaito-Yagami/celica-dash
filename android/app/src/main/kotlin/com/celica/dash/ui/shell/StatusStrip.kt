package com.celica.dash.ui.shell

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.ui.theme.Clock
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.WordmarkStyle
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One link indicator: a dot that is red when live, faint when not. */
data class Link(val name: String, val on: Boolean)

/**
 * The top bar: wordmark left, clock centred, link dots and battery voltage right. The clock
 * ticks itself; everything else is passed in.
 */
@Composable
fun StatusStrip(links: List<Link>, voltage: String?, onSettings: () -> Unit = {}, onStars: (() -> Unit)? = null, starsOn: Boolean = false, modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(1000L * (60 - now.second))
        }
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Ink.bg)
            .drawBehind { drawLine(Ink.line, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx()) }
    ) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(WordmarkStyle)
            if (CarSpec.alt.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(CarSpec.alt, style = Data.copy(color = Ink.txFaint, letterSpacing = TextUnit(0.3f, TextUnitType.Em)))
            }
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), style = Clock)
            Spacer(Modifier.width(10.dp))
            Text(now.format(Units.dateTime("EEE d MMM")).uppercase(), style = Data)
        }
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            links.forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(if (l.on) Ink.red else Ink.txFaint))
                    Text(l.name, style = Data)
                }
            }
            if (voltage != null) Text(voltage, style = Data)
            if (onStars != null) Text("✦", style = Data.copy(color = if (starsOn) Ink.red else Ink.txDim, fontSize = 18.sp), modifier = Modifier.clickable(onClick = onStars).padding(horizontal = 6.dp, vertical = 4.dp))
            Text("⚙", style = Data.copy(color = Ink.txDim, fontSize = 22.sp), modifier = Modifier.padding(start = 4.dp).clickable(onClick = onSettings).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}
