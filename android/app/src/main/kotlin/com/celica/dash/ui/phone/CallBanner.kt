package com.celica.dash.ui.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celica.dash.media.Call
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Numeral

/** A call, over whatever is on screen: who, what state, and the two buttons that matter. */
@Composable
fun BoxScope.CallBanner(c: Call, onAccept: () -> Unit, onReject: () -> Unit, onHangUp: () -> Unit) {
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 60.dp).width(panelWidth(560.dp, 0.94f)).background(Ink.bg.copy(alpha = 0.96f))
            .drawBehind { drawRect(Ink.red, Offset.Zero, Size(4.dp.toPx(), size.height)) }
            .padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(when (c.kind) { Call.Kind.INCOMING -> "INCOMING CALL"; Call.Kind.OUTGOING -> "CALLING"; Call.Kind.ACTIVE -> "ON CALL" }, style = Label.copy(color = Ink.red))
            Text(c.name?.takeIf { it.isNotBlank() } ?: c.number ?: "Unknown", style = Numeral.copy(fontSize = 26.sp), maxLines = 1)
            if (c.name != null && c.number != null) Text(c.number, style = Data)
        }
        when (c.kind) {
            Call.Kind.INCOMING -> { FlatButton("Answer", hot = true, onClick = onAccept); FlatButton("Decline", onClick = onReject) }
            else -> FlatButton("Hang up", hot = true, onClick = onHangUp)
        }
    }
}
