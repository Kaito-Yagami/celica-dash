package com.celica.dash.ui.phone

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.phone.BtDevice
import com.celica.dash.phone.PhoneState
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Chip
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Numeral
import com.celica.dash.ui.theme.Title

/**
 * The phone: what is linked and over which radio on the left, pairing on the right. Pairing
 * itself is the system's passkey dialog; everything else is ours.
 *
 * The unit has two radios. The vendor's external module carries the music, the calls and the
 * CarPlay handshake, and the phone pairs with it in the car's own Bluetooth app. Android's radio
 * is the one this screen can pair things with: the dongle, and the phone for its GPS. The dash
 * keeps the phone's music and calls off Android's radio, so they cannot land on the side with
 * no speakers.
 */
@Composable
fun PhoneScreen(vm: DashViewModel) {
    val st by vm.phoneState.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val q by vm.qf.state.collectAsStateWithLifecycle()
    val gps by vm.phoneGpsBle.status.collectAsStateWithLifecycle()
    val gpsMode by vm.phoneGpsSetting.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val carPlay by vm.carPlay.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var drawer by remember { mutableStateOf(false) }
    // one radio or two is the unit's to say: on a one-radio unit the vendor's service drives
    // Android's own Bluetooth, and this screen's pairing list is the only one there is
    val twoRadios = vm.qf.externalRadio()
    val linked = if (twoRadios) q.connected else st.connected != null
    Row(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Tile(Modifier.weight(1.6f).fillMaxHeight()) {
            TileLabel(if (linked) "●" else "○", "PHONE")
            TileNote(if (!st.enabled) "BLUETOOTH OFF" else if (linked) "LINK" else "NO LINK")
            val onDial = {
                // the unit's own Bluetooth phone app has the keypad and the phone book
                if (!vm.openApp(com.celica.dash.media.QfBluetooth.PKG)) runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Unit
            }
            if (twoRadios) TwoRadioPanel(st, q, gps = gps, gpsOverBle = gpsMode == "ble", nowPlayingSource = np.source, onDial = onDial, onVendor = { vm.openApp(com.celica.dash.media.QfBluetooth.PKG) })
            else LinkPanel(st, gps = gps, gpsOverBle = gpsMode == "ble", nowPlayingSource = np.source, onDial = onDial)
            // the way out to the phone's projection (zlink under the dash's own curtain where the
            // unit has it - see PhoneLink; a plain launch elsewhere) and to everything else installed
            Row(Modifier.align(Alignment.BottomStart).padding(18.dp, 0.dp, 18.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val cp = carPlay
                val mode = vm.phoneLink.mode
                val phase by vm.phoneLink.phase.collectAsStateWithLifecycle()
                val linkUp by vm.phoneLink.up.collectAsStateWithLifecycle()
                // the session as the vendor's flag has it, polled every second - the one true word
                // for CarPlay; Android Auto has only the broadcast, so the dash's own memory serves
                val live = if (mode == com.celica.dash.apps.PhoneLink.Mode.CARPLAY) q.carPlay else linkUp
                if (mode != com.celica.dash.apps.PhoneLink.Mode.NONE) {
                    PhoneButton(enabled = cp != null || vm.phoneLink.choreographed, live = live, word = mode.word) { vm.openCarPlay(cp?.pkg ?: "") }
                    // the end of a session, from this side: the phone comes back to Bluetooth.
                    // Root's to do, so only offered where the unit's door is open
                    if (phase is com.celica.dash.apps.PhoneLink.Phase.Cutting) Text("CUTTING", style = Label.copy(color = Ink.red))
                    else if (live && vm.phoneLink.choreographed && com.celica.dash.apps.UnitProfile.root) FlatButton("Cut", hot = true) { vm.phoneLink.cut() }
                }
                FlatButton("All apps") { drawer = true }
                if (mode != com.celica.dash.apps.PhoneLink.Mode.NONE && cp == null) Text("no ${mode.word} app found on this unit · pick one in Settings › Phone", style = Body)
                else if (mode != com.celica.dash.apps.PhoneLink.Mode.NONE && !vm.phoneLink.choreographed) Text("opens ${cp?.label ?: "the app"} · the dash steps aside", style = Body)
            }
        }
        Tile(Modifier.weight(1f).fillMaxHeight()) {
            TileLabel("PAIR", if (st.scanning) "scanning…" else "${st.bonded.size} paired")
            PairPanel(
                st, twoRadios = twoRadios,
                onScan = { if (st.scanning) vm.phone.stopScan() else vm.phone.scan() },
                onPair = { vm.phone.pair(it.address) },
                onForget = { d -> if (!vm.phone.forget(d.address)) ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                onSystem = { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
            )
        }
    }
    if (drawer) AppsSheet(apps, "APPS", "everything else on the unit", carPlay?.pkg, onPick = { vm.openApp(it.pkg); drawer = false }, onClose = { drawer = false })
}

/**
 * The unit as it really is: the phone's music and calls on the vendor's radio, its GPS on
 * Android's. What the vendor's service says about its link is all it says - no name, only an
 * address - so the name is borrowed from Android's pairing list when the same phone is in it.
 */
@Composable
private fun TwoRadioPanel(st: PhoneState, q: com.celica.dash.media.QfState, gps: String, gpsOverBle: Boolean, nowPlayingSource: String?, onDial: () -> Unit, onVendor: () -> Unit) {
    val addr = q.linkedAddress
    val name = addr?.let { a -> st.bonded.firstOrNull { it.address.equals(a, true) }?.name }
    val gpsLive = gps.startsWith("listening")
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 16.dp)) {
        if (!q.connected) {
            Text("NO PHONE", style = Numeral.copy(fontSize = 26.sp))
            Text(
                "Pair the phone in the car's own Bluetooth app - it shows there as \"${q.localName ?: "the unit"}\". That radio carries the music, the calls and CarPlay.",
                style = Body, modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text(name ?: "PHONE", style = Numeral.copy(fontSize = 28.sp), maxLines = 1)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("music", color = Ink.tx, fill = Ink.redDim)
                Chip("calls", color = Ink.tx, fill = Ink.redDim)
                Chip("carplay", color = if (q.carPlay) Ink.tx else Ink.txFaint, fill = if (q.carPlay) Ink.redDim else Ink.s2)
                Chip("gps", color = if (gpsLive) Ink.tx else Ink.txFaint, fill = if (gpsLive) Ink.redDim else Ink.s2)
            }
            Text("${addr ?: ""}  ·  on \"${q.localName ?: "the unit's radio"}\"", style = Data, modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.height(22.dp))
        Feature("MUSIC", "The unit's own radio, \"${q.localName ?: "?"}\": paired in the car's Bluetooth app, not here.", if (q.connected) (nowPlayingSource ?: "linked") else "—", action = "Open" to onVendor)
        Feature("CALLS", "The same radio; the unit's phone app rings, answers and hangs up.", if (q.connected) "linked" else "—", action = "Dial" to onDial)
        Feature("CARPLAY", "zlink shakes hands over that radio, then runs over Wi-Fi.", if (q.carPlay) "up" else "—")
        Feature(
            "GPS",
            if (gpsOverBle) "Android's radio, \"${st.localName ?: "?"}\", with GPS2IP set to Bluetooth. Music and calls are kept off this radio so they stay on the other."
            else "Over Bluetooth needs the setting: ⚙ › Map › the phone over Bluetooth.",
            if (!gpsOverBle) "—" else if (gpsLive) "live" else gps.take(26),
        )
    }
}

/**
 * One radio doing everything: the phone's music and calls, the dongle, and the phone's GPS over
 * a low-energy link. That last one gives the phone a second entry with the unit's name - the
 * low-energy pairing - which is connected but carries no audio, and is not the one to tap.
 */
@Composable
private fun LinkPanel(st: PhoneState, gps: String, gpsOverBle: Boolean, nowPlayingSource: String?, onDial: () -> Unit) {
    val d = st.connected
    val gpsLive = gps.startsWith("listening")
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 40.dp, bottom = 16.dp)) {
        if (d == null) {
            Text(if (st.enabled) "NO PHONE" else "BLUETOOTH IS OFF", style = Numeral.copy(fontSize = 26.sp))
            Text(
                if (st.enabled) "Pair the phone on the right, or pick a paired one - it links on its own once it is in range."
                else "Turn Bluetooth on in the unit's settings; the dash reads it from there.",
                style = Body, modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text(d.name, style = Numeral.copy(fontSize = 28.sp), maxLines = 1)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("audio", color = if (st.audio) Ink.tx else Ink.txFaint, fill = if (st.audio) Ink.redDim else Ink.s2)
                Chip("calls", color = if (st.calls) Ink.tx else Ink.txFaint, fill = if (st.calls) Ink.redDim else Ink.s2)
                Chip("contacts", color = if (st.contacts) Ink.tx else Ink.txFaint, fill = if (st.contacts) Ink.redDim else Ink.s2)
                st.battery?.let { b -> Chip("battery $b%", color = Ink.tx, fill = Ink.s2) }
            }
            Text(d.address, style = Data, modifier = Modifier.padding(top = 8.dp))
        }
        Box(Modifier.height(22.dp))
        // what the link carries, and where it lands in the dash
        Feature("MUSIC", "Now playing, art and the transport controls come over A2DP/AVRCP.", if (d != null && st.audio) (nowPlayingSource ?: "linked") else "—")
        Feature("CALLS", "Ring, answer and hang up through the unit's phone app over HFP.", if (d != null && st.calls) "linked" else "—", action = "Dial" to onDial)
        Feature("CONTACTS", "The phone book comes over PBAP for the phone app to search.", if (d != null && st.contacts) "linked" else "—")
        Feature(
            "GPS",
            if (gpsOverBle) "A low-energy link to GPS2IP on the same radio. It shows on the phone as a second \"${st.localName ?: "unit"}\" that is connected but plays nothing - leave that one be."
            else "Over Bluetooth needs the setting: ⚙ › Map › the phone over Bluetooth.",
            if (!gpsOverBle) "—" else if (gpsLive) "live" else gps.take(26),
        )
        Feature("INTERNET", "Personal Hotspot over Bluetooth gives the dash weather; turn it on in the phone, then Internet access in the unit's Bluetooth settings.", null)
    }
}

@Composable
private fun Feature(name: String, blurb: String, status: String?, action: Pair<String, () -> Unit>? = null) {
    Row(
        Modifier.fillMaxWidth().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = Label.copy(color = Ink.tx, fontWeight = FontWeight.SemiBold), modifier = Modifier.width(92.dp))
        Text(blurb, style = Body, modifier = Modifier.weight(1f).padding(end = 12.dp))
        if (status != null) Text(status.uppercase(), style = Label.copy(color = if (status == "—") Ink.txFaint else Ink.red))
        if (action != null) FlatButton(action.first, modifier = Modifier.padding(start = 10.dp), onClick = action.second)
    }
}

@Composable
private fun PairPanel(st: PhoneState, twoRadios: Boolean, onScan: () -> Unit, onPair: (BtDevice) -> Unit, onForget: (BtDevice) -> Unit, onSystem: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(top = 40.dp, bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlatButton(if (st.scanning) "Stop" else "Scan", hot = !st.scanning, enabled = st.enabled, onClick = onScan)
            FlatButton("System", onClick = onSystem)
        }
        Text(
            if (twoRadios) "Android's radio, \"${st.localName ?: "?"}\": the dongle, and the phone for its GPS. Music and calls pair in the car's Bluetooth app instead."
            else "Put the phone in Settings › Bluetooth so it can be seen.",
            style = Body, modifier = Modifier.padding(14.dp, 10.dp, 14.dp, 6.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (st.bonded.isNotEmpty()) item { Text("PAIRED", style = Label, modifier = Modifier.padding(14.dp, 8.dp, 14.dp, 4.dp)) }
            items(st.bonded, key = { "b" + it.address }) { d ->
                DeviceRow(d, status = if (d.address == st.connected?.address) "LINK" else "", onClick = null, onForget = { onForget(d) })
            }
            if (st.found.isNotEmpty()) item { Text("NEARBY", style = Label, modifier = Modifier.padding(14.dp, 12.dp, 14.dp, 4.dp)) }
            items(st.found, key = { "f" + it.address }) { d ->
                DeviceRow(d, status = if (d.address == st.pairing) "PAIRING…" else "TAP TO PAIR", onClick = { onPair(d) }, onForget = null)
            }
        }
    }
}

@Composable
private fun DeviceRow(d: BtDevice, status: String, onClick: (() -> Unit)?, onForget: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }
            .padding(start = 14.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (d.phone) Ink.red else Ink.line2, CircleShape))
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(d.name, style = Title.copy(fontSize = 15.sp), maxLines = 1)
            Text(d.address + if (d.phone) "  ·  PHONE" else "", style = Data.copy(fontSize = 10.sp))
        }
        Text(status, style = Label.copy(color = if (status == "LINK") Ink.red else Ink.txDim))
        if (onForget != null) Text("  ✕", style = Label.copy(color = Ink.txFaint), modifier = Modifier.clickable(onClick = onForget).padding(6.dp))
    }
}
