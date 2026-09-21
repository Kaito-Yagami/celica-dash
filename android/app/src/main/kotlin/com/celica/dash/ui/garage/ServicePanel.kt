package com.celica.dash.ui.garage

import com.celica.dash.data.Units
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.data.ServiceEntry
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * The service log: what was done, when, at what mileage. Kept on the unit. The form is four
 * fields because that is all a log needs, and typing on a dash is no fun.
 */
@Composable
fun ServicePanel(vm: DashViewModel) {
    val log by vm.garage.log.collectAsStateWithLifecycle(initialValue = emptyList())
    var adding by remember { mutableStateOf(false) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var miles by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val nf = remember { Units.integers() }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(18.dp, 14.dp, 18.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("SERVICE LOG", style = Label.copy(color = Ink.tx))
                Spacer(Modifier.width(10.dp))
                Text("${log.size} entries", style = Label)
                Spacer(Modifier.weight(1f))
                FlatButton(if (adding) "cancel" else "add entry", hot = !adding) { adding = !adding }
            }
            if (log.isEmpty()) {
                Text("Nothing logged yet.", style = Body.copy(color = Ink.txFaint), modifier = Modifier.padding(18.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(log, key = { it.id }) { e ->
                    Row(
                        Modifier.fillMaxWidth().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
                            .padding(18.dp, 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(Modifier.width(110.dp)) {
                            Text(e.date, style = Label.copy(color = Ink.tx))
                            if (e.miles != null) Text(nf.format(e.miles) + " " + Units.distUnit, style = Label)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = Body.copy(color = Ink.tx, fontWeight = FontWeight.SemiBold))
                            if (e.notes.isNotBlank()) Text(e.notes, style = Body.copy(color = Ink.txDim), modifier = Modifier.padding(top = 2.dp))
                        }
                        Text("✕", style = Label.copy(color = Ink.txFaint), modifier = Modifier.clickable { vm.removeLog(e.id) }.padding(6.dp))
                    }
                }
            }
        }
        if (adding) {
            Column(
                Modifier.width(panelWidth(330.dp, 0.42f)).fillMaxHeight()
                    .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(w / 2, 0f), Offset(w / 2, size.height), w) }
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("NEW ENTRY", style = Label.copy(color = Ink.tx))
                Field("Date", date, { date = it })
                Field(if (Units.metric) "Odometer (km)" else "Mileage", miles, { miles = it.filter { c -> c.isDigit() } }, KeyboardType.Number)
                Field("What was done", title, { title = it })
                Field("Notes", notes, { notes = it }, lines = 4)
                FlatButton("save", hot = true, enabled = title.isNotBlank()) {
                    vm.addLog(ServiceEntry(System.currentTimeMillis(), date, miles.toIntOrNull(), title.trim(), notes.trim()))
                    title = ""; notes = ""; adding = false
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, keyboard: KeyboardType = KeyboardType.Text, lines: Int = 1) {
    Column {
        Text(label.uppercase(), style = Label, modifier = Modifier.padding(bottom = 4.dp))
        BasicTextField(
            value, onChange,
            textStyle = Body.copy(color = Ink.tx, fontSize = 14.sp),
            cursorBrush = SolidColor(Ink.red),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            minLines = lines, maxLines = lines.coerceAtLeast(1),
            modifier = Modifier.fillMaxWidth().background(Ink.s2).padding(10.dp),
        )
    }
}
