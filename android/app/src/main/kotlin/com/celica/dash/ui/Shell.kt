package com.celica.dash.ui

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Units
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celica.dash.ui.drive.DriveScreen
import com.celica.dash.ui.garage.GarageScreen
import com.celica.dash.ui.garage.ManualScreen
import com.celica.dash.ui.home.HomeScreen
import com.celica.dash.ui.home.WeatherTile
import com.celica.dash.ui.map.MapScreen
import com.celica.dash.ui.media.MediaScreen
import com.celica.dash.ui.media.MediaTile
import com.celica.dash.ui.phone.CallBanner
import com.celica.dash.ui.phone.PhoneScreen
import com.celica.dash.ui.settings.SettingsSheet
import com.celica.dash.ui.shell.BootLine
import com.celica.dash.ui.shell.BootOverlay
import kotlinx.coroutines.delay
import com.celica.dash.ui.shell.Dock
import com.celica.dash.ui.shell.Link
import com.celica.dash.ui.shell.Section
import com.celica.dash.ui.shell.StatusStrip
import com.celica.dash.ui.theme.Ink

/**
 * Strip on top, dock underneath, the current section between. Sections are kept alive by state
 * rather than a navigation graph: there are seven of them, they are all one level deep, and Back
 * from anywhere means Home. The boot ceremony sits over the lot once per cold start.
 */
@Composable
fun Shell(vm: DashViewModel = viewModel()) {
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    BackHandler(enabled = section != Section.HOME) { section = Section.HOME }

    val vehicle by vm.vehicle.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val wx by vm.weather.collectAsStateWithLifecycle()
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    androidx.compose.runtime.DisposableEffect(activity) { vm.activity = activity; onDispose { if (vm.activity === activity) vm.activity = null } }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.onResume() }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    val setupDone by vm.setupDone.collectAsStateWithLifecycle()
    val restyle by vm.restyle.collectAsStateWithLifecycle()
    // the page survives a restyle: the key below rebuilds the wizard whenever the palette moves
    var setupStep by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    if (!setupDone) { androidx.compose.runtime.key(restyle) { com.celica.dash.ui.setup.SetupScreen(vm, setupStep) { setupStep = it } }; return }
    val booted by vm.booted.collectAsStateWithLifecycle()
    val wanted by vm.sectionRequest.collectAsStateWithLifecycle()
    LaunchedEffect(wanted) { wanted?.let { section = it; vm.consumeSection() } }
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val starsOpen by vm.starsOpen.collectAsStateWithLifecycle()
    val lights by vm.lights.state.collectAsStateWithLifecycle()
    val reveal = remember { Animatable(if (booted) 1f else 0f) }
    var overlayDone by remember { mutableStateOf(booted) }
    // A rehearsal under the black. The first frames that draw the car's lines are where the GPU
    // driver does its lazy work, and a reveal timed against the clock jumped ahead while it did:
    // a wheel arch, a wing, then most of a car at once. So the reveal is run once in full while
    // the ceremony still covers it - nothing shows - and wound back to nothing before the real
    // one, which then has a warm pipeline under it.
    var rehearsed by remember { mutableStateOf(booted) }
    LaunchedEffect(Unit) {
        if (rehearsed) return@LaunchedEffect
        delay(350)                                          // the surface and its shaders first
        reveal.animateTo(1f, tween(600))
        reveal.snapTo(0f)
        rehearsed = true
    }
    LaunchedEffect(overlayDone, rehearsed) {
        if (overlayDone && rehearsed && reveal.value < 1f) reveal.animateTo(1f, tween(1400))
    }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize()) {
            StatusStrip(
                links = listOf(Link("OBD", vehicle.obdLive), Link("GPS", vehicle.gpsLive), Link("BT", np.hasSession)),
                voltage = null,
                onSettings = { vm.showSettings(true) },
                onStars = if (lights.status != "off the air") ({ vm.showStars(true) }) else null, starsOn = lights.on && lights.connected,
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (section) {
                    Section.HOME -> HomeScreen(
                        vm, reveal = reveal.value,
                        media = { MediaTile(np, onPlayPause = vm.media::playPause, onNext = vm.media::next, onPrev = vm.media::previous) },
                        weather = { WeatherTile(wx) },
                    )
                    Section.DRIVE -> DriveScreen(vm)
                    Section.MAP -> MapScreen(vm)
                    Section.MEDIA -> MediaScreen(vm)
                    Section.PHONE -> PhoneScreen(vm)
                    Section.GARAGE -> GarageScreen(vm, openManualPage = { p -> vm.openManual(p); section = Section.MANUAL })
                    Section.MANUAL -> ManualScreen(vm)
                }
            }
            Dock(current = section, onSelect = { section = it }, sections = remember { Section.entries.filter { it != Section.MANUAL || vm.site.hasManual } })
        }
        if (settingsOpen) SettingsSheet(vm, onClose = { vm.showSettings(false) })
        if (starsOpen) com.celica.dash.ui.lights.StarsSheet(vm.lights, onClose = { vm.showStars(false) })
        val crt by vm.crt.collectAsStateWithLifecycle()
        if (crt) com.celica.dash.ui.shell.CrtOverlay(Modifier.fillMaxSize())
        val call by vm.call.collectAsStateWithLifecycle()
        call?.let { c -> CallBanner(c, onAccept = { vm.qf.accept() }, onReject = { vm.qf.reject() }, onHangUp = { vm.qf.hangUp() }) }
        if (!overlayDone) {
            BootOverlay(
                lines = listOf(
                    BootLine("DASH", "v${vm.version} · ${CarSpec.code.ifBlank { CarSpec.name.replace("*", "") }}"),
                    BootLine("ENGINE ECU", if (vehicle.obdLive) "LINK" else "NO DONGLE", hot = !vehicle.obdLive),
                    BootLine("GPS", if (vehicle.gpsLive) "FIX" else "SEARCHING"),
                    BootLine("BLUETOOTH", if (np.hasSession) (np.source ?: "LINK").uppercase() else "—"),
                    BootLine("GARAGE", vm.site.manualTotal.let { if (it > 0) "${Units.integers().format(it)} PAGES" else "NO MANUAL" }),
                    BootLine("MODEL", "${Units.integers().format(vm.modelFaces)} FACES"),
                    BootLine("READY", "", hot = false),
                ),
                onDone = { overlayDone = true; vm.markBooted() },
            )
        }
    }
}
