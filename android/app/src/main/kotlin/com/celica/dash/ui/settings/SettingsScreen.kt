package com.celica.dash.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.phone.AppsSheet
import com.celica.dash.ui.shell.Rail
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label

/**
 * Settings, as a sheet over the dash, in plain pages down a rail: the look, the car (dongle and
 * faults), the phone (apps and access), the map (data and the phone's GPS), and the weather.
 * The PC helper, the fetches, the diagnostics and the extras live on an Advanced page at the
 * bottom, out of the way; Setup can be run again from there.
 */
@Composable
fun SettingsSheet(vm: DashViewModel, onClose: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    // which app is being picked, if any; the grid is drawn out here rather than inside the
    // scrolling page, which would measure it with no height at all and bring the dash down
    var picking by remember { mutableStateOf<String?>(null) }
    val pages = listOf("Style", "Car", "Phone", "Map", "Weather", "Advanced")
    Box(Modifier.fillMaxSize().background(Ink.bg.copy(alpha = 0.92f)).clickable(onClick = onClose)) {
        Tile(Modifier.align(Alignment.Center).fillMaxWidth(0.8f).fillMaxHeight(0.84f).pointerInput(Unit) { detectTapGestures {} }) {
            TileLabel("SETTINGS", pages[page].lowercase())
            TileNote("TAP OUTSIDE TO CLOSE")
            Row(Modifier.fillMaxSize().padding(top = 36.dp)) {
                Rail(pages, page, { page = it })
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                    when (page) {
                        0 -> StylePage(vm)
                        1 -> CarPage(vm)
                        2 -> PhonePage(vm) { picking = it }
                        3 -> MapPage(vm)
                        4 -> WeatherPage(vm)
                        else -> AdvancedPage(vm)
                    }
                }
            }
        }
        picking?.let { what -> AppPicker(vm, what) { picking = null } }
    }
}

/** The app grid for the two pickers on the phone page, over the whole settings sheet. */
@Composable
private fun AppPicker(vm: DashViewModel, what: String, onClose: () -> Unit) {
    val apps by vm.apps.collectAsStateWithLifecycle()
    val carPlay by vm.carPlay.collectAsStateWithLifecycle()
    val btApp by vm.btMusicApp.collectAsStateWithLifecycle()
    if (what == "carplay") {
        AppsSheet(apps, "CARPLAY APP", "pick what the CarPlay button opens", carPlay?.pkg, onPick = { vm.setCarPlayApp(it.pkg); onClose() }, onClose = onClose)
    } else {
        AppsSheet(apps, "BLUETOOTH MUSIC APP", "pick the unit's own Bluetooth player", btApp?.pkg, onPick = { vm.setBtMusicApp(it.pkg); onClose() }, onClose = onClose)
    }
}

@Composable
private fun StylePage(vm: DashViewModel) {
    val look by vm.look.collectAsStateWithLifecycle()
    val zoom by vm.zoom.collectAsStateWithLifecycle()
    Section("Look")
    RowItem("Ink", "the soft near-black of the mockup", active = look == "ink", onClick = { vm.setStyle(look = "ink") })
    RowItem("High contrast", "true black, hotter red, white type - matches the car", active = look == "contrast", onClick = { vm.setStyle(look = "contrast") })
    val crt by vm.crt.collectAsStateWithLifecycle()
    Section("Tube")
    Hint("The whole dash behind the glass of a CRT - a shadow mask over it, and the picture falling away into the corners. Still, not rolling: a dash that will not sit still is worse than a plain one.")
    RowItem("Scan lines", if (crt) "on" else "off", active = crt, onClick = { vm.setCrt(!crt) })
    Section("Drive face")
    Hint("What the engine screen looks like. They all read the same car - it is a matter of what you want in front of you.")
    val face by vm.driveFace.collectAsStateWithLifecycle()
    com.celica.dash.ui.drive.DriveFace.entries.forEach { f ->
        RowItem(f.title, f.note, active = face == f.name, onClick = { vm.setDriveFace(f.name) })
    }
    if (face == "ANALOG") {
        val dial by vm.analogDial.collectAsStateWithLifecycle()
        Section("Rev counter")
        Hint("The Analogue face keeps its fascia either way - this is only which instrument sits beside it.")
        RowItem("Retro", "the turned dial, printed numerals, a needle on a spring", active = dial == "retro", onClick = { vm.setAnalogDial("retro") })
        RowItem("Modern", "the Sweep face's own - a lit arc with a blade riding the rim", active = dial == "modern", onClick = { vm.setAnalogDial("modern") })
    }
    val films = com.celica.dash.ui.drive.backdrops(LocalContext.current)
    if (face == "THEATRE") {
        Section("Backdrop")
        Hint("The film the Theatre face plays behind the numbers. It runs at the engine's pace - a crawl at idle, near double at the stop.")
        val chosen by vm.backdrop.collectAsStateWithLifecycle()
        if (films.isEmpty()) Hint("None on the unit yet.")
        films.forEach { f ->
            RowItem(
                f.nameWithoutExtension.replace('-', ' ').replace('_', ' '), "%.0f MB".format(f.length() / 1e6),
                active = chosen == f.name || (chosen.isBlank() && f == films.first()), onClick = { vm.setBackdrop(f.name) },
            )
        }
    }
    Section("Size")
    Hint("How large everything is drawn. Bigger is easier to hit on the move; the layouts flex to fit.")
    listOf(0.9f to "Compact", 1f to "Standard", 1.1f to "Large", 1.25f to "Larger", 1.5f to "Huge", 1.75f to "Largest").forEach { (z, name) ->
        RowItem(name, "${(z * 100).toInt()}%", active = kotlin.math.abs(zoom - z) < 0.01f, onClick = { vm.setStyle(zoom = z) })
    }
}

@Composable
private fun CarPage(vm: DashViewModel) {
    val ctx = LocalContext.current
    val addr by vm.prefs.obdAddress.collectAsStateWithLifecycle(initialValue = null)
    val status by vm.obdStatus.collectAsStateWithLifecycle()
    val devices = remember { vm.obd.bondedDevices() }
    val dtcs by vm.obd.dtcs.collectAsStateWithLifecycle()
    Section("The car")
    RowItem("${com.celica.dash.data.CarSpec.name.replace("*", "")}  ·  ${com.celica.dash.data.CarSpec.sub}", "name, engine, redline, gearbox and wireframe - in Setup", onClick = { vm.runSetupAgain() })
    Section("OBD dongle")
    Hint("An ELM327 v1.5 (classic Bluetooth) in the socket under the dash. Pair it in the unit's Bluetooth settings - PIN 1234, or 0000 - then pick it here.")
    if (devices.isEmpty()) RowItem("No paired devices", "pair the dongle first", onClick = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    })
    devices.forEach { d ->
        val on = d.address == addr
        RowItem(
            d.name, d.address + "  ·  " + d.kind + if (on) "  ·  " + status.name.lowercase() else "",
            active = on, onClick = { vm.setObdDevice(if (on) null else d.address) },
        )
    }
    if (addr != null && devices.none { it.address == addr }) RowItem("Forget the chosen device", "$addr is not a dongle", active = true, onClick = { vm.setObdDevice(null) })
    Section("Fault codes")
    val list = dtcs
    RowItem(
        "Read stored codes", when { list == null -> "not read yet"; list.isEmpty() -> "no codes stored"; else -> list.joinToString("  ") },
        active = !list.isNullOrEmpty(), onClick = { vm.readDtcs() },
    )
    RowItem("Clear codes", "turns the engine light off; the code comes back if the fault is still there", onClick = { vm.clearDtcs() })
}

@Composable
private fun PhonePage(vm: DashViewModel, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val carPlay by vm.carPlay.collectAsStateWithLifecycle()
    val btApp by vm.btMusicApp.collectAsStateWithLifecycle()
    val mode by vm.phoneMode.collectAsStateWithLifecycle()
    Section("Phone")
    RowItem("iPhone · CarPlay", "the button opens CarPlay", active = mode == "carplay", onClick = { vm.setPhoneMode("carplay") })
    RowItem("Android · Android Auto", "the button opens Android Auto", active = mode == "android_auto", onClick = { vm.setPhoneMode("android_auto") })
    RowItem("Neither", "no projection button", active = mode == "none", onClick = { vm.setPhoneMode("none") })
    Section("Apps")
    RowItem("${com.celica.dash.apps.PhoneLink.Mode.of(mode).word.ifBlank { "Projection" }} app", carPlay?.let { "${it.label}  ·  what the button opens" } ?: "none found - pick the unit's own CarPlay / Android Auto app", onClick = { onPick("carplay") })
    RowItem("Bluetooth music app", btApp?.let { "${it.label}  ·  the unit's own player" } ?: "not recognised - pick it", onClick = { onPick("bt") })
    Section("Access")
    val activity = ctx as? android.app.Activity
    com.celica.dash.ui.setup.Permissions.items.forEach { item ->
        val ok = com.celica.dash.ui.setup.Permissions.granted(ctx, item.kind)
        RowItem(item.title, if (ok) "granted  ·  ${item.why}" else item.why, active = ok, onClick = {
            if (!ok && activity != null) {
                val rt = com.celica.dash.ui.setup.Permissions.runtime(item.kind)
                if (rt.isNotEmpty()) androidx.core.app.ActivityCompat.requestPermissions(activity, rt, 9) else com.celica.dash.ui.setup.Permissions.ask(activity, item.kind)
            }
        })
    }
}

@Composable
private fun MapPage(vm: DashViewModel) {
    val ctx = LocalContext.current
    val pg by vm.phoneGpsSetting.collectAsStateWithLifecycle()
    val tiles by vm.tilesImport.collectAsStateWithLifecycle()
    val graph by vm.graphImport.collectAsStateWithLifecycle()
    val pickTiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importTiles(uri) }
    val pickGraph = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importGraph(uri) }
    Hint("The unit's own aerial hears very little behind a windscreen. The phone can stand in for it, over Bluetooth or over its hotspot, with GPS2IP set to match.")
    RowItem("The unit's own GPS only", "no help from the phone", active = pg.isBlank(), onClick = { vm.setPhoneGps("") })
    RowItem(
        "The phone over Bluetooth",
        "GPS2IP set to Bluetooth  ·  works alongside music, calls and CarPlay; the phone may ask to pair with Android's radio the first time",
        active = pg == "ble", onClick = { vm.setPhoneGps("ble") },
    )
    RowItem(
        "The phone over its hotspot",
        "GPS2IP set to Hotspot, the unit joined to it  ·  NMEA on port 11123  ·  not alongside wireless CarPlay: the phone cannot host a hotspot and sit on the unit's Wi-Fi at once",
        active = pg == "auto", onClick = { vm.setPhoneGps("auto") },
    )
    Section("Position")
    RowItem("Location settings", "the unit's own GPS: location and the GPS provider must be on", onClick = {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    })
    Section("Map data")
    RowItem(if (tiles != null) "Importing map tiles… ${(tiles!! * 100).toInt()}%" else "Import map tiles from a file", "a .pmtiles file", active = tiles != null, onClick = { if (tiles == null) pickTiles.launch(arrayOf("*/*")) })
    RowItem(if (graph != null) "Importing routing graph… ${(graph!! * 100).toInt()}%" else "Import routing graph from a file", if (vm.router.available()) "a routing .zip  ·  imported" else "a routing .zip", active = graph != null, onClick = { if (graph == null) pickGraph.launch(arrayOf("*/*")) })
    Hint("Both usually come over Wi-Fi from the PC helper instead - see Advanced, or run Setup again.")
}

@Composable
private fun WeatherPage(vm: DashViewModel) {
    val wx by vm.weather.collectAsStateWithLifecycle()
    Section("Weather")
    RowItem("Refresh now", wx?.let { "last fetched ${com.celica.dash.data.Units.date("EEE HH:mm").format(java.util.Date(it.fetchedAt))}" } ?: "nothing fetched yet", onClick = { vm.refreshWeatherNow() })
    Hint("Fetches on its own every ten minutes whenever the unit is online - the phone's hotspot does. Needs a position first.")
}

@Composable
private fun AdvancedPage(vm: DashViewModel) {
    val ctx = LocalContext.current
    val host by vm.pcHost.collectAsStateWithLifecycle()
    val helper by vm.helper.collectAsStateWithLifecycle()
    val helperNote by vm.helperNote.collectAsStateWithLifecycle()
    val note by vm.fetchNote.collectAsStateWithLifecycle()
    val progress by vm.fetchProgress.collectAsStateWithLifecycle()
    Section("The PC helper · RUN ME on a PC on the same Wi-Fi")
    val typedHost = remember { mutableStateOf(host) }
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("PC address", style = Body.copy(color = Ink.tx), modifier = Modifier.width(110.dp))
        // typed here, saved when the field is left: saving on every key and re-reading the saved
        // value on every change raced each other and scrambled fast typing
        var text by typedHost
        var focused by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(host) { if (!focused) text = host }
        Box(Modifier.weight(1f).background(Ink.s2).padding(10.dp, 8.dp)) {
            BasicTextField(
                text, { text = it }, textStyle = Data.copy(color = Ink.tx), cursorBrush = SolidColor(Ink.red), singleLine = true,
                modifier = Modifier.fillMaxWidth().onFocusChanged { f -> if (focused && !f.isFocused && text != host) vm.setPcHost(text); focused = f.isFocused },
            )
            if (text.isEmpty()) Text("192.168.0.xx  ·  or leave blank and let it listen", style = Data.copy(color = Ink.txFaint))
        }
    }
    RowItem("Find the helper", helperNote.ifBlank { helper?.let { "found ${it.name} at ${it.host}" } ?: "listens for it on the Wi-Fi, or asks the address above" }, active = helper != null, onClick = { vm.findHelper(typedHost.value.ifBlank { null }) })
    helper?.items?.forEach { item ->
        val p = progress[item.kind]
        RowItem(item.label.ifBlank { item.kind }, if (p != null) "fetching… ${(p * 100).toInt()}%" else "tap to fetch", active = p != null, onClick = { if (p == null) vm.fetchItem(item) })
    }
    if (helper != null) RowItem("Fetch everything", "all of the above, one after another", onClick = { vm.fetchAll(helper!!.items) })
    RowItem("Fetch and install a newer build", "when the helper has one", onClick = { vm.fetchUpdate() })
    note?.let { Text(it, style = Data.copy(color = if (it.contains(":")) Ink.red else Ink.txDim), modifier = Modifier.padding(18.dp, 6.dp)) }

    Section("Setup")
    RowItem("Run setup again", "the first-run questions, from the top - nothing is lost", onClick = { vm.runSetupAgain() })

    Section("Extras")
    val remote by vm.prefs.remoteControl.collectAsStateWithLifecycle(initialValue = false)
    RowItem("Let the PC helper drive the unit", if (remote) "on  ·  the PC can run commands here - for fixing things, off when done" else "off  ·  the PC can only serve files", active = remote, onClick = { vm.setRemoteControl(!remote) })
    val stars by vm.lights.state.collectAsStateWithLifecycle()
    RowItem("Star ceiling lights", if (stars.status != "off the air") "on  ·  ${stars.status}" else "off  ·  a Bluetooth LED controller (LEDBLE) for a fibre-optic headliner", active = stars.status != "off the air", onClick = { if (stars.status != "off the air") vm.lights.stop() else vm.lights.start() })
    RowItem(if (vm.demoRunning) "Stop the demo drive" else "Play the demo drive", "idle, a pull through the revs, cruise, on a made-up road - no dongle needed", onClick = vm::toggleDemo)

    Section("Diagnostics")
    var diag by remember { mutableStateOf(vm.diagnostics()) }
    diag.forEach { (k, v) -> Text("$k:  $v", style = Data.copy(color = Ink.txDim, fontSize = 10.sp), modifier = Modifier.padding(18.dp, 3.dp)) }
    RowItem("Refresh", "read the lines above again", onClick = { vm.onResume(); diag = vm.diagnostics() })

    Section("Android · often hidden on a head unit")
    fun openSettings(activity: String, fallback: String) {
        runCatching { ctx.startActivity(Intent().setClassName("com.android.settings", activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { ctx.startActivity(Intent(fallback).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
    RowItem("Android settings", "the real one", onClick = { openSettings("com.android.settings.Settings", Settings.ACTION_SETTINGS) })
    RowItem("Developer options", "USB debugging lives here", onClick = { openSettings("com.android.settings.Settings\$DevelopmentSettingsDashboardActivity", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
    RowItem("Hands-off updates", "Accessibility › Celica Dash updates · on, and a newer build installs itself", onClick = { openSettings("com.android.settings.Settings\$AccessibilitySettingsActivity", Settings.ACTION_ACCESSIBILITY_SETTINGS) })
    val btMusic by vm.btMusicApp.collectAsStateWithLifecycle()
    RowItem("Open the unit's Bluetooth music app", btMusic?.label ?: "not recognised", onClick = { btMusic?.let { vm.openApp(it.pkg) } })
}

@Composable
private fun Section(t: String) {
    Text(t.uppercase(), style = Label, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun Hint(t: String) {
    Text(t, style = Body, modifier = Modifier.padding(18.dp, 4.dp, 18.dp, 6.dp))
}

@Composable
private fun RowItem(title: String, sub: String, active: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable(onClick = onClick).padding(horizontal = 18.dp)
            .drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Body.copy(color = if (active) Ink.red else Ink.tx, fontSize = 14.sp))
            Text(sub, style = Data.copy(color = Ink.txFaint), maxLines = 1)
        }
        if (active) Box(Modifier.padding(start = 10.dp)) { Text("●", style = Data.copy(color = Ink.red)) }
    }
}
