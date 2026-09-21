package com.celica.dash.ui.setup

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.MainActivity
import com.celica.dash.apps.PhoneLink
import com.celica.dash.apps.UnitProfile
import com.celica.dash.data.CarSpec
import com.celica.dash.data.Helper
import com.celica.dash.data.Region
import com.celica.dash.data.Units
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.phone.AppsSheet
import com.celica.dash.ui.shell.FlatButton
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.shell.Wordmark
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Title
import com.celica.dash.ui.theme.WordmarkStyle

/**
 * First run. One question a page, down a rail, every page skippable: the PC helper (whose
 * answers fill the rest in), the car, where it drives, how it looks, the phone, the grants,
 * the dongle, what to fetch, and Home. Nothing here is final - Settings has every page again.
 */
private val STEPS = listOf("Welcome", "PC helper", "Your car", "Region", "Look", "Phone", "Permissions", "OBD dongle", "Fetch", "Home", "Done")

@Composable
fun SetupScreen(vm: DashViewModel, stepIn: Int, onStep: (Int) -> Unit) {
    // the page is the shell's to keep, so a restyle (which rebuilds this screen) lands on the same one
    var step by remember { mutableIntStateOf(stepIn) }
    LaunchedEffect(step) { onStep(step) }
    var picking by remember { mutableStateOf<String?>(null) }
    val helperInfo by vm.helper.collectAsStateWithLifecycle()
    // the PC's wizard answered the car, the region, the look and the phone: those pages are
    // not asked again, only kept in the rail for anyone who wants to change one
    val fromPc = helperInfo?.setup?.let { j -> listOf("car", "region", "look", "phone").any { j.optJSONObject(it)?.length() ?: 0 > 0 } } == true
    val answeredOnPc = if (fromPc) setOf(2, 3, 4, 5) else emptySet()
    fun next() { step = if (step == 1 && fromPc) 6 else step + 1 }
    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Tile(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp)) {
            TileLabel("SETUP", "${step + 1} of ${STEPS.size} · ${STEPS[step].lowercase()}")
            TileNote("EVERYTHING HERE IS IN SETTINGS TOO")
            Row(Modifier.fillMaxSize().padding(top = 36.dp)) {
                StepRail(step, answeredOnPc) { if (it <= step || it in answeredOnPc) step = it }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, bottom = 10.dp)) {
                        when (step) {
                            0 -> WelcomeStep()
                            1 -> HelperStep(vm)
                            2 -> CarStep(vm)
                            3 -> RegionStep(vm)
                            4 -> LookStep(vm)
                            5 -> PhoneStep(vm) { picking = it }
                            6 -> PermissionsStep()
                            7 -> ObdStep(vm)
                            8 -> FetchStep(vm)
                            9 -> HomeStep()
                            else -> DoneStep()
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(22.dp, 8.dp, 22.dp, 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (step > 0) FlatButton("Back") { step-- }
                        Spacer(Modifier.weight(1f))
                        if (step in 1 until STEPS.size - 1) FlatButton("Skip") { next() }
                        if (step < STEPS.size - 1) FlatButton(if (step == 0) "Begin" else "Next", hot = true) { next() }
                        else FlatButton("Start the dash", hot = true) { vm.finishSetup() }
                    }
                }
            }
        }
        picking?.let { what ->
            val apps by vm.apps.collectAsStateWithLifecycle()
            val carPlay by vm.carPlay.collectAsStateWithLifecycle()
            val btApp by vm.btMusicApp.collectAsStateWithLifecycle()
            if (what == "projection") AppsSheet(apps, "${vm.phoneLink.mode.word.uppercase()} APP", "what the button opens", carPlay?.pkg, onPick = { vm.setCarPlayApp(it.pkg); picking = null }, onClose = { picking = null })
            else AppsSheet(apps, "BLUETOOTH MUSIC APP", "the unit's own player", btApp?.pkg, onPick = { vm.setBtMusicApp(it.pkg); picking = null }, onClose = { picking = null })
        }
    }
}

@Composable
private fun StepRail(current: Int, fromPc: Set<Int>, onSelect: (Int) -> Unit) {
    Column(Modifier.width(150.dp).fillMaxHeight().drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(size.width - w / 2, 0f), Offset(size.width - w / 2, size.height), w) }) {
        STEPS.forEachIndexed { i, s ->
            val on = i == current
            val pc = i in fromPc && !on
            val done = i < current || pc
            Row(Modifier.fillMaxWidth().height(36.dp).clickable(enabled = done) { onSelect(i) }.background(if (on) Ink.s2 else Color.Transparent).padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).background(if (on) Ink.red else if (done) Ink.tx else Ink.txFaint, androidx.compose.foundation.shape.CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(s.uppercase(), style = Label.copy(color = if (on) Ink.tx else if (done) Ink.txDim else Ink.txFaint), modifier = Modifier.weight(1f))
                if (pc) Text("PC", style = Label.copy(fontSize = 8.sp, color = Ink.txFaint))
            }
        }
    }
}

// ---- the steps ------------------------------------------------------------------------------------

@Composable
private fun WelcomeStep() {
    Heading("Welcome", "Ten short questions. Skip any; Settings ⚙ has them all again.")
    Tip("Did RUN ME on a PC first? The next page finds it and fills most of this in.")
    Tip("The PC and this unit must be on the same Wi-Fi: park near the house, or bring a laptop on your phone's hotspot.")
    Tip("No PC? The gauges, Bluetooth, the OBD dongle and the weather work anyway. The offline map and your music need one.")
    Tip("This unit: ${UnitProfile.describe()}")
}

@Composable
private fun HelperStep(vm: DashViewModel) {
    val info by vm.helper.collectAsStateWithLifecycle()
    val note by vm.helperNote.collectAsStateWithLifecycle()
    var typed by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (info == null) vm.findHelper() }
    Heading("The PC helper", "RUN ME on the PC makes the map and holds your music; this unit fetches them.")
    if (info != null) {
        Tip("Found ${info!!.name} at ${info!!.host}. Waiting there:")
        info!!.items.forEach { Line("• ${it.label.ifBlank { it.kind }}" + if (it.bytes > 0) "  ·  ${fmtBytes(it.bytes)}" else "") }
        val j = info!!.setup
        val summary = listOfNotNull(
            j?.optJSONObject("car")?.let { c -> c.optString("name").replace("*", "").ifBlank { null }?.let { n -> n + (c.optString("sub").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") } },
            j?.optJSONObject("region")?.optString("name")?.takeIf { it.isNotBlank() },
            j?.optJSONObject("look")?.let { l -> l.optString("accent").takeIf { it.isNotBlank() }?.let { "accent #$it" } },
            j?.optJSONObject("phone")?.optString("mode")?.let { m -> when (m) { "carplay" -> "iPhone"; "android_auto" -> "Android"; "none" -> "no phone"; else -> null } },
        )
        if (summary.isNotEmpty()) Tip("Answered on the PC: ${summary.joinToString(" · ")}.")
        Tip("So the car, region, look and phone pages are skipped - Next goes to what only this unit can do. Tap any of them in the rail to change one.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { FlatButton("Look again") { vm.forgetHelper(); vm.findHelper() } }
    } else {
        Tip(note.ifBlank { "Listening…" })
        Tip("Nothing heard? The PC's page shows an address - type it here.")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field("PC address", typed, { typed = it }, Modifier.width(220.dp), KeyboardType.Uri, hint = "192.168.0.xx")
            FlatButton("Ask it", hot = true, enabled = typed.isNotBlank()) { vm.findHelper(typed) }
            FlatButton("Listen again") { vm.findHelper() }
        }
        Tip("Same Wi-Fi as the PC? This unit is on ${Helper.unitAddress() ?: "no Wi-Fi at all - join one in the unit's settings"}.")
    }
}

@Composable
private fun CarStep(vm: DashViewModel) {
    val saved by vm.prefs.car.collectAsStateWithLifecycle(initialValue = null)
    val s = saved ?: return
    // the fields hold the effective values, so a preset's defaults show and can be edited
    val preset = s.preset
    fun eff(): CarSpec.Saved = CarSpec.effective(s)
    var name by remember(s, preset) { mutableStateOf(eff().name) }
    var alt by remember(s, preset) { mutableStateOf(eff().alt ?: "") }
    var code by remember(s, preset) { mutableStateOf(eff().code) }
    var sub by remember(s, preset) { mutableStateOf(eff().sub) }
    var note by remember(s, preset) { mutableStateOf(eff().note.replace("\n", " / ")) }
    var redline by remember(s, preset) { mutableStateOf(eff().redline.toString()) }
    var lift by remember(s, preset) { mutableStateOf(eff().liftRpm?.toString() ?: "") }
    var noLift by remember(s, preset) { mutableStateOf(eff().noLift) }
    var gears by remember(s, preset) { mutableStateOf(eff().gears ?: "") }
    var finalDrive by remember(s, preset) { mutableStateOf(eff().finalDrive.toString()) }
    var circ by remember(s, preset) { mutableStateOf(eff().circumferenceM.toString()) }
    var length by remember(s, preset) { mutableStateOf(eff().lengthM.toString()) }
    var model by remember(s, preset) { mutableStateOf(eff().model) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    fun save() = vm.saveCar(CarSpec.Saved(
        preset, name, alt.ifBlank { null } ?: "", code, sub, note.replace(" / ", "\n"), lift.toIntOrNull(), noLift, redline.toIntOrNull(),
        gears, finalDrive.toDoubleOrNull(), circ.toDoubleOrNull(), length.toFloatOrNull(), model,
    ))

    Heading("Your car", "What the dash calls it, and what its engine does.")
    // a preset tap starts over from its own defaults; the fields below then hold them for editing
    Choices(CarSpec.PRESETS, preset) { vm.saveCar(CarSpec.Saved(preset = it, model = model)) }
    Tip("A preset fills everything in. Change any of it - not every Celica is a T-Sport.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Name on the strip", name, { name = it; save() }, Modifier.weight(1.4f), hint = "CELICA *T*-SPORT")
        Field("Small script", alt, { alt = it; save() }, Modifier.weight(0.6f), hint = "セリカ")
    }
    Tip("Asterisks colour a part: CELICA *T*-SPORT lights the T.")
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Field("Tile label", code, { code = it; save() }, Modifier.weight(0.7f), hint = "ZZT231")
        Field("Under it", sub, { sub = it; save() }, Modifier.weight(1f), hint = "2ZZ-GE · VVTL-i")
        Field("Note", note, { note = it; save() }, Modifier.weight(1.3f), hint = "190 PS · 8,200 RPM / C60 · 6-SPEED")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
        Field("Redline", redline, { redline = it.filter { c -> c.isDigit() }; save() }, Modifier.width(120.dp), KeyboardType.Number)
        if (!noLift) Field(if (preset == CarSpec.PRESET_2ZZ) "Lift" else "Shift point", lift, { lift = it.filter { c -> c.isDigit() }; save() }, Modifier.width(120.dp), KeyboardType.Number)
        Toggle(if (preset == CarSpec.PRESET_1ZZ) "No lift (1ZZ)" else "No hot zone", noLift) { noLift = it; save() }
    }
    Tip(if (noLift) "One colour to the redline; no time on the cams counted." else "Red from here to the redline; drives count the time up there.")
    Spacer(Modifier.height(8.dp))
    Text("WIREFRAME", style = Label)
    Choices(CarSpec.MODELS, model) { model = it; save() }
    Spacer(Modifier.height(8.dp))
    Toggle("Gearbox and tyre", advanced) { advanced = it }
    if (advanced) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Gear ratios, 1st first (blank: no gear readout)", gears, { gears = it; save() }, Modifier.weight(1.6f), hint = "3.166, 1.904, 1.392, 1.031, 0.815, 0.725")
            Field("Final drive", finalDrive, { finalDrive = it; save() }, Modifier.weight(0.6f), KeyboardType.Decimal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("Metres per wheel turn", circ, { circ = it; save() }, Modifier.weight(1f), KeyboardType.Decimal)
            Field("Car length, metres", length, { length = it; save() }, Modifier.weight(1f), KeyboardType.Decimal)
        }
        Tip("Ratios let the dash read the gear from revs against speed. Blank them to hide it.")
    }
}

@Composable
private fun RegionStep(vm: DashViewModel) {
    val region by vm.prefs.region.collectAsStateWithLifecycle(initialValue = "")
    val units by vm.prefs.units.collectAsStateWithLifecycle(initialValue = Units.UK)
    val locale by vm.prefs.locale.collectAsStateWithLifecycle(initialValue = "en-GB")
    Heading("Where you drive", "Where the map opens, the units, and the voice.")
    if (region.isNotBlank()) Tip("From the PC: $region.")
    Text("REGION", style = Label)
    Choices(Region.PRESETS.map { it.name to it.name }, region) { name ->
        Region.PRESETS.firstOrNull { it.name == name }?.let { vm.saveRegion(it.name, it.lat, it.lon, it.zoom); vm.saveUnits(it.units, it.locale) }
    }
    Spacer(Modifier.height(10.dp))
    Text("UNITS", style = Label)
    Choices(Units.SYSTEMS, units) { vm.saveUnits(it, locale) }
    Tip("Change this any time - recorded drives replay right in either.")
    Spacer(Modifier.height(10.dp))
    Text("VOICE AND DATES", style = Label)
    Choices(listOf("en-GB" to "English (UK)", "en-US" to "English (US)", "de-DE" to "Deutsch", "fr-FR" to "Français", "es-ES" to "Español", "it-IT" to "Italiano", "nl-NL" to "Nederlands", "ja-JP" to "日本語"), locale) { vm.saveUnits(units, it) }
}

@Composable
private fun LookStep(vm: DashViewModel) {
    val look by vm.look.collectAsStateWithLifecycle()
    val accent by vm.prefs.accent.collectAsStateWithLifecycle(initialValue = null)
    val background by vm.prefs.background.collectAsStateWithLifecycle(initialValue = null)
    val crt by vm.crt.collectAsStateWithLifecycle()
    var hexA by remember(accent) { mutableStateOf(accent ?: "") }
    var hexB by remember(background) { mutableStateOf(background ?: "") }
    Heading("The look", "Two looks, any accent, any background. It changes as you tap.")
    Choices(listOf(Ink.INK to "Ink · soft near-black", Ink.CONTRAST to "Contrast · true black"), look) { vm.saveColours(it, accent, background) }
    Spacer(Modifier.height(10.dp))
    Text("ACCENT", style = Label)
    Swatches(Ink.ACCENTS, accent) { vm.saveColours(look, it, background) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Or a hex", hexA, { hexA = it.trim().removePrefix("#").take(6); if (Ink.parse(hexA) != null) vm.saveColours(look, hexA, background) }, Modifier.width(120.dp), hint = "D8433C")
        FlatButton("The look's own") { hexA = ""; vm.saveColours(look, null, background) }
    }
    Spacer(Modifier.height(10.dp))
    Text("BACKGROUND", style = Label)
    Swatches(Ink.BACKGROUNDS, background) { vm.saveColours(look, accent, it) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("Or a hex", hexB, { hexB = it.trim().removePrefix("#").take(6); if (Ink.parse(hexB) != null) vm.saveColours(look, accent, hexB) }, Modifier.width(120.dp), hint = "0E0D0F")
        FlatButton("The look's own") { hexB = ""; vm.saveColours(look, accent, null) }
    }
    Spacer(Modifier.height(10.dp))
    Toggle("Scan lines - the dash behind the glass of a tube", crt) { vm.setCrt(it) }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.background(Ink.s1).border(1.dp, Ink.line).padding(14.dp, 10.dp)) { Wordmark(WordmarkStyle) }
        Text("● LIVE", style = Label.copy(color = Ink.red))
        Text("12 ° C · 61 MPH", style = Data.copy(color = Ink.chrome))
    }
}

@Composable
private fun PhoneStep(vm: DashViewModel, onPick: (String) -> Unit) {
    val mode by vm.phoneMode.collectAsStateWithLifecycle()
    val carPlay by vm.carPlay.collectAsStateWithLifecycle()
    val btApp by vm.btMusicApp.collectAsStateWithLifecycle()
    val gps by vm.phoneGpsSetting.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshApps() }
    Heading("Your phone", "Which kind, so the button opens the right thing.")
    Choices(listOf("carplay" to "iPhone · CarPlay", "android_auto" to "Android · Android Auto", "none" to "Neither"), mode) { vm.setPhoneMode(it) }
    if (mode != "none") {
        val word = PhoneLink.Mode.of(mode).word
        Tip(if (UnitProfile.zlink) "This unit runs $word through zlink. The dash covers zlink's connect screen with its own, and ends sessions cleanly."
            else "This unit's own $word app is opened as it is; the dash steps aside.")
        if (UnitProfile.zlink && mode == "android_auto") Tip("Android Auto behind the curtain is untested on a real phone. If it misbehaves, Settings › Phone › Home app puts things back.")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FlatButton("$word app: ${carPlay?.label ?: "pick one"}") { onPick("projection") }
            FlatButton("Bluetooth music app: ${btApp?.label ?: "pick one"}") { onPick("bt") }
        }
    }
    Spacer(Modifier.height(12.dp))
    Text("GPS", style = Label)
    Choices(
        listOfNotNull(
            "" to "The unit's own aerial",
            if (mode == "carplay") "ble" to "The iPhone over Bluetooth (GPS2IP app)" else null,
            if (mode == "carplay") "auto" to "The iPhone over its hotspot (GPS2IP app)" else null,
        ), gps,
    ) { vm.setPhoneGps(it) }
    if (mode == "carplay") Tip("Units hear GPS poorly behind glass. The GPS2IP app on the iPhone can stand in - over Bluetooth alongside CarPlay, or over its hotspot (not with wireless CarPlay).")
}

@Composable
private fun PermissionsStep() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    Heading("Permissions", "Tap each one; Android asks. The reason is under each.")
    Permissions.items.forEach { item ->
        val ok = remember(tick) { Permissions.granted(ctx, item.kind) }
        Row(
            Modifier.fillMaxWidth().height(50.dp).clickable(enabled = !ok) {
                val rt = Permissions.runtime(item.kind)
                if (rt.isNotEmpty()) ask.launch(rt) else (ctx as? Activity)?.let { Permissions.ask(it, item.kind) }
            }.drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (ok) "●" else "○", style = Data.copy(color = if (ok) Ink.go else if (item.optional) Ink.txFaint else Ink.red), modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title + if (item.optional && !ok) "  ·  optional" else "", style = Body.copy(color = Ink.tx, fontSize = 14.sp))
                Text(item.why, style = Data.copy(color = Ink.txFaint), maxLines = 1)
            }
            Text(if (ok) "GRANTED" else "TAP TO ALLOW", style = Label.copy(color = if (ok) Ink.txFaint else Ink.tx))
        }
    }
    LaunchedEffect(tick) { if (UnitProfile.root) Thread { Permissions.rootExtras(ctx) }.start() }
    Row(Modifier.padding(top = 10.dp)) { FlatButton("Check again") { tick++ } }
    if (UnitProfile.root) Tip("This unit has an open root door, so the overlay and a faster start were set up quietly.")
}

@Composable
private fun ObdStep(vm: DashViewModel) {
    val ctx = LocalContext.current
    val addr by vm.prefs.obdAddress.collectAsStateWithLifecycle(initialValue = null)
    val status by vm.obdStatus.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val devices = remember(tick) { vm.obd.bondedDevices() }
    Heading("The OBD dongle", "Plugs into the socket under the dash; the gauges read the engine through it.")
    Tip("A cheap Bluetooth ELM327 - v1.5 is the safe one. Pair it in the unit's Bluetooth settings, then pick it here.")
    Tip("Pairing asks for a PIN: try 1234, then 0000 (6789 on a few). It is printed on some dongles.")
    Tip("None yet? Skip. Road speed still comes from GPS.")
    if (devices.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        FlatButton("Open Bluetooth settings") { runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        FlatButton("Look again") { tick++ }
    }
    devices.forEach { d ->
        val on = d.address == addr
        Row(Modifier.fillMaxWidth().height(48.dp).clickable { vm.setObdDevice(if (on) null else d.address) }.drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (on) "●" else "○", style = Data.copy(color = if (on) Ink.red else Ink.txFaint), modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = Body.copy(color = Ink.tx, fontSize = 14.sp))
                Text(d.address + "  ·  " + d.kind + if (on) "  ·  " + status.name.lowercase() else "", style = Data.copy(color = Ink.txFaint))
            }
        }
    }
}

@Composable
private fun FetchStep(vm: DashViewModel) {
    val info by vm.helper.collectAsStateWithLifecycle()
    val progress by vm.fetchProgress.collectAsStateWithLifecycle()
    val done by vm.fetchDone.collectAsStateWithLifecycle()
    val errors by vm.fetchErrors.collectAsStateWithLifecycle()
    Heading("Fetch from the PC", "The map, the music and the rest, over Wi-Fi.")
    val items = info?.items ?: emptyList()
    if (info == null) { Tip("No PC helper found. Go back a few pages, or skip - Settings › Advanced fetches later."); return }
    if (items.isEmpty()) { Tip("${info!!.name} has nothing ready yet. Finish the wizard on the PC, then Look again on the helper page."); return }
    val busy = progress.values.any { it != null }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { FlatButton("Fetch everything", hot = true, enabled = !busy) { vm.fetchAll(items.filter { it.kind !in done }) } }
    Spacer(Modifier.height(8.dp))
    items.forEach { it ->
        val p = progress[it.kind]
        val ok = it.kind in done
        val err = errors[it.kind]
        Row(Modifier.fillMaxWidth().height(50.dp).clickable(enabled = p == null) { vm.fetchItem(it) }.drawBehind { val w = 1.dp.toPx(); drawLine(Ink.line, Offset(0f, size.height - w / 2), Offset(size.width, size.height - w / 2), w) }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (ok) "●" else if (p != null) "◐" else "○", style = Data.copy(color = if (ok) Ink.go else if (p != null) Ink.red else Ink.txFaint), modifier = Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(it.label.ifBlank { it.kind }, style = Body.copy(color = Ink.tx, fontSize = 14.sp))
                Text(
                    when {
                        err != null -> "failed: $err - tap to try again"
                        p != null -> "fetching… ${(p * 100).toInt()}%"
                        ok -> "done"
                        else -> (if (it.bytes > 0) fmtBytes(it.bytes) else "") + (if (it.count > 0) "  ·  ${it.count} files" else "") + "  ·  tap to fetch"
                    },
                    style = Data.copy(color = if (err != null) Ink.red else Ink.txFaint), maxLines = 1,
                )
            }
            if (p != null) Box(Modifier.width(120.dp).height(4.dp).background(Ink.line)) { Box(Modifier.fillMaxWidth(p.coerceIn(0.01f, 1f)).height(4.dp).background(Ink.red)) }
        }
    }
    Tip("The map is the big one - a country is a gigabyte or more. Carry on with the wizard while it comes.")
}

@Composable
private fun HomeStep() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val isHome = remember(tick) { Permissions.isHome(ctx) }
    var wallpaper by rememberSaveable { mutableStateOf(false) }
    Heading("Home", "So the ignition opens onto the dash.")
    Tip(if (isHome) "The dash is the Home app." else "Android asks which Home to use - pick the dash, 'Always'. The unit's own launcher stays; Settings › Phone › Home app puts it back.")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!isHome) FlatButton("Make the dash Home", hot = true) { (ctx as? Activity)?.let { Permissions.ask(it, Permissions.Kind.HOME) } }
        FlatButton("Check again") { tick++ }
    }
    Spacer(Modifier.height(14.dp))
    Toggle("Black wallpaper, so nothing else flashes up between the boot animation and the dash", wallpaper) { on ->
        wallpaper = on
        if (on) Thread { MainActivity.setBlackWallpaper(ctx) }.start()
    }
}

@Composable
private fun DoneStep() {
    Heading("Ready", "The dash starts in what you chose.")
    Tip("Settings ⚙ (top right) has every page again; Advanced has the PC helper and the fetches.")
    Tip("Map blank? The tiles are still coming, or were skipped - Settings › Advanced › Fetch.")
    Tip("No music showing? Settings › Phone › Notification access.")
    Tip("Hold the car's label on Home for a demo drive with no dongle.")
}

// ---- the widgets -----------------------------------------------------------------------------------

@Composable
private fun Heading(title: String, sub: String) {
    Text(title, style = Title)
    Text(sub, style = Body, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
}

@Composable
private fun Para(t: String) { Text(t, style = Body.copy(color = Ink.tx, fontSize = 13.sp), modifier = Modifier.padding(bottom = 10.dp)) }

@Composable
private fun Line(t: String) { Text(t, style = Data.copy(color = Ink.txFaint), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) }

/** One line, how and why, with a mark in front so it reads as a tip and not a wall. */
@Composable
private fun Tip(t: String) {
    Row(Modifier.padding(bottom = 7.dp), verticalAlignment = Alignment.Top) {
        Text("›", style = Body.copy(color = Ink.red, fontSize = 13.sp), modifier = Modifier.width(14.dp))
        Text(t, style = Body.copy(color = Ink.tx, fontSize = 13.sp))
    }
}

@Composable
private fun Choices(options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
    Column {
        options.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (key, label) ->
                    val on = key == current
                    Box(
                        Modifier.weight(1f).height(40.dp).background(if (on) Ink.s2 else Ink.s1).border(1.dp, if (on) Ink.red else Ink.line).clickable { onPick(key) }.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) { Text(label, style = Body.copy(color = if (on) Ink.tx else Ink.txDim, fontSize = 12.sp), maxLines = 2) }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun Swatches(options: List<Pair<String, String>>, current: String?, onPick: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (name, hex) ->
            val on = current?.uppercase() == hex.uppercase()
            Column(Modifier.clickable { onPick(hex) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp).background(Ink.parse(hex) ?: Ink.s2).border(if (on) 2.dp else 1.dp, if (on) Ink.tx else Ink.line))
                Text(name.uppercase(), style = Label.copy(fontSize = 8.sp, color = if (on) Ink.tx else Ink.txFaint), modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clickable { onChange(!on) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(34.dp).height(18.dp).background(if (on) Ink.red else Ink.line2)) {
            Box(Modifier.align(if (on) Alignment.CenterEnd else Alignment.CenterStart).padding(2.dp).size(14.dp).background(Ink.tx))
        }
        Text(label, style = Body.copy(color = Ink.tx, fontSize = 13.sp))
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, hint: String = "") {
    Column(modifier.padding(bottom = 8.dp)) {
        Text(label.uppercase(), style = Label, modifier = Modifier.padding(bottom = 4.dp))
        Box(Modifier.fillMaxWidth().background(Ink.s2).padding(10.dp, 8.dp)) {
            BasicTextField(
                value, onChange, textStyle = Body.copy(color = Ink.tx, fontSize = 14.sp), cursorBrush = SolidColor(Ink.red), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard), modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, style = Body.copy(color = Ink.txFaint, fontSize = 14.sp))
        }
    }
}

private fun fmtBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
    else -> "%.0f KB".format(b / 1e3)
}
