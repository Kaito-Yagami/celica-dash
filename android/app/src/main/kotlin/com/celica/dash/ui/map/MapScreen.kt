package com.celica.dash.ui.map

import com.celica.dash.data.CarSpec
import com.celica.dash.data.Region
import com.celica.dash.data.Units
import android.graphics.RectF
import android.os.SystemClock
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celica.dash.car.Car
import com.celica.dash.data.Drive
import com.celica.dash.data.Place
import com.celica.dash.location.Fix
import com.celica.dash.location.compass
import com.celica.dash.search.PlaceSearch
import com.celica.dash.routing.NavState
import com.celica.dash.ui.DashViewModel
import com.celica.dash.ui.shell.Tile
import com.celica.dash.ui.shell.TileLabel
import com.celica.dash.ui.shell.TileNote
import com.celica.dash.ui.theme.Body
import com.celica.dash.ui.theme.Data
import com.celica.dash.ui.theme.Ink
import com.celica.dash.ui.theme.panelWidth
import com.celica.dash.ui.theme.Label
import com.celica.dash.ui.theme.Mono
import com.celica.dash.ui.theme.Numeral
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.android.gestures.StandardScaleGestureDetector
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.geojson.Point
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.pow

/**
 * Where the tiles live on the unit. Made on the PC by the setup helper for the owner's region,
 * then fetched or imported through Setup, which copies the file into the app's private storage
 * - the one place that is certain to be readable. A file dropped into the app's external folder
 * is honoured too when the system lets the app read it.
 */
fun ownTilesFile(ctx: android.content.Context): File = File(ctx.filesDir, "map.pmtiles")

fun tilesFile(ctx: android.content.Context): File {
    val own = ownTilesFile(ctx)                                   // fetched or imported
    if (own.exists()) return own
    val ext = File(ctx.getExternalFilesDir(null), "map.pmtiles")  // adb push, when ownership allows
    return if (ext.canRead()) ext else own
}

/** The car sits this far down the map while following, so the road ahead gets the screen. */
private const val CAR_Y = 0.72f
private const val TILT_3D = 58.0
private const val CONTROLS_MS = 7000L

/** The car's size on the map at the base zoom; it grows and shrinks with the zoom from there. */
private const val CAR_DP = 44f
private const val BASE_ZOOM = 16.5f

private val replayFmt get() = Units.date("EEE d MMM HH:mm")

/** What the map view needs to remember between updates; also how the screen reaches the map. */
private class MapHandle {
    var map: MapLibreMap? = null
    var styleReady by mutableIntStateOf(0)
    var pinsApplied: List<Place>? = null
    var trailApplied: List<DoubleArray>? = null
    var replayApplied: Drive? = null
    var routeApplied: Any? = null
    var cursorApplied = -1
    var threeDApplied: Boolean? = null
    var camKey: Any? = null
    var places: List<Place> = emptyList()
    var onTap: (Pick?) -> Unit = {}
    var onHold: (LatLng) -> Unit = {}
    var onMoved: () -> Unit = {}
    var onZoomed: (Int) -> Unit = {}
    /** The car's place on screen while not following; null when following (it is at CAR_Y). */
    var carPx by mutableStateOf<Offset?>(null)
    var carLatLng: LatLng? = null
    var following = true
    /** True while a recentre is still flying, so the car is drawn where it really is until then. */
    var settling by mutableStateOf(false)
    /** The camera as it is right now, so the car can be drawn from the matching angle. */
    var camBearing by mutableStateOf(0f)
    var camTilt by mutableStateOf(0f)
    var camZoom by mutableStateOf(BASE_ZOOM)
    var wordsApplied: MapWords? = null
    var ghostApplied: List<com.celica.dash.data.DrivePoint>? = null
    /** The map opens on the car rather than on the country; true once that has happened. */
    var snapped = false
    /** Called when the camera settles, so the icons can be read for whatever is now in view. */
    var onIdle: () -> Unit = {}
    /** South, west, north, east of what the screen covers, for the icons. */
    fun bounds(): DoubleArray? {
        val b = map?.projection?.visibleRegion?.latLngBounds ?: return null
        return doubleArrayOf(b.latitudeSouth, b.longitudeWest, b.latitudeNorth, b.longitudeEast)
    }
    fun readCamera() { val c = map?.cameraPosition ?: return; camBearing = c.bearing.toFloat(); camTilt = c.tilt.toFloat(); camZoom = c.zoom.toFloat() }
    fun project() { val m = map; val ll = carLatLng; carPx = if ((!following || settling) && m != null && ll != null) m.projection.toScreenLocation(ll).let { Offset(it.x, it.y) } else null }
}

/** What the basemap is asked to draw besides the roads themselves. */
private class MapWords(val streets: Boolean, val places: Boolean, val buildings: Boolean) {
    override fun equals(other: Any?) = other is MapWords && other.streets == streets && other.places == places && other.buildings == buildings
    override fun hashCode() = (if (streets) 1 else 0) + (if (places) 2 else 0) + (if (buildings) 4 else 0)
}

/**
 * The offline map: MapLibre with the night style over a PMTiles file of the owner's region, tilted or
 * flat, heading-up, the car low in the frame with the road ahead filling the rest, and a red
 * trail of where this drive has been. Drag or pinch to look around - the car is let go and the
 * recentre button brings it back. Controls fade when idle; a tap on the map brings them back.
 * Hold a spot to pin it; tap a pin or a fuel stop for its card. ● records a drive; the list
 * button opens the recorded drives and the pins, and a drive replays with the car driving it.
 */
@Composable
fun MapScreen(vm: DashViewModel) {
    val fix by vm.fix.collectAsStateWithLifecycle()
    val trail by vm.trail.collectAsStateWithLifecycle()
    val places by vm.places.collectAsStateWithLifecycle()
    val drives by vm.drives.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val replay by vm.replay.collectAsStateWithLifecycle()
    val liftRpm = CarSpec.liftRpm ?: Int.MAX_VALUE                       // no lift, no dots
    val nav by vm.nav.collectAsStateWithLifecycle()
    val routing by vm.routing.collectAsStateWithLifecycle()
    val navError by vm.navError.collectAsStateWithLifecycle()
    val finished by vm.finished.collectAsStateWithLifecycle()
    val ghostResult by vm.ghostResult.collectAsStateWithLifecycle()
    val ghost by vm.ghostState.collectAsStateWithLifecycle()
    val ghostArmed by vm.ghostArmed.collectAsStateWithLifecycle()
    val ghostRoute by vm.ghostRoute.collectAsStateWithLifecycle()
    val road by vm.road.collectAsStateWithLifecycle()
    val voiceOn by vm.voiceOn.collectAsStateWithLifecycle()
    val canRoute = remember { vm.router.available() }
    val icons by vm.mapIcons.collectAsStateWithLifecycle()
    val words = MapWords(
        vm.mapStreets.collectAsStateWithLifecycle().value,
        vm.mapPlaces.collectAsStateWithLifecycle().value,
        vm.mapBuildings.collectAsStateWithLifecycle().value,
    )
    val ctx = LocalContext.current
    val tiles = remember { tilesFile(ctx) }
    val have = remember(tiles) { tiles.exists() && tiles.length() > 0 }

    var follow by remember { mutableStateOf(true) }
    var zoomStep by remember { mutableIntStateOf(0) }           // user zoom, relative to the auto level
    var threeD by rememberSaveable { mutableStateOf(true) }
    var boxH by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf<Pick?>(null) }
    var naming by remember { mutableStateOf<Naming?>(null) }
    val handle = remember { MapHandle() }
    LaunchedEffect(Unit) { vm.ensureSearchIndex() }
    // the icons for whatever is on screen, read out of the search file once the camera settles
    var settled by remember { mutableIntStateOf(0) }
    handle.onIdle = { settled++; handle.settling = false }
    // turning following back on starts a flight to the car; until the camera lands, the chevron
    // is drawn at the car's real place on the map rather than pinned to its spot on the screen
    LaunchedEffect(follow) { if (follow) handle.settling = true }
    LaunchedEffect(settled, icons, handle.styleReady) {
        val kinds = PlaceSearch.CATEGORIES.filter { it.first in icons }.flatMap { it.second }
        val st = handle.map?.style?.takeIf { it.isFullyLoaded } ?: return@LaunchedEffect
        if (kinds.isEmpty() || handle.camZoom < 12.5f) { MapLayers.setPois(st, emptyList()); return@LaunchedEffect }
        delay(150)
        val b = handle.bounds() ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) { PlaceSearch.inBox(ctx, b[0], b[1], b[2], b[3], kinds) }
        handle.map?.style?.takeIf { it.isFullyLoaded }?.let { MapLayers.setPois(it, found) }
    }

    // the controls: shown for a while after any touch, then gone
    var shownAt by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var recentlyTouched by remember { mutableStateOf(true) }
    LaunchedEffect(shownAt) { recentlyTouched = true; delay(CONTROLS_MS); recentlyTouched = false }
    // while the map is let go the owner is exploring, so the controls stay
    val controlsOn = recentlyTouched || !follow
    val poke = { shownAt = SystemClock.uptimeMillis() }
    // zoom and pitch act on the follow camera, or straight on the map once it is let go
    val zoomBy = { d: Int ->
        zoomStep = (zoomStep + d).coerceIn(-6, 4)
        if (!follow) handle.map?.animateCamera(CameraUpdateFactory.zoomBy(d.toDouble()), 300)
        poke()
    }
    val togglePitch = {
        threeD = !threeD
        if (!follow) handle.map?.animateCamera(CameraUpdateFactory.tiltTo(if (threeD) TILT_3D else 0.0), 500)
        poke()
    }
    val controlsAlpha by animateFloatAsState(if (controlsOn) 1f else 0f, tween(if (controlsOn) 180 else 600), label = "controls")

    // the replay clock
    val player = remember(replay) { replay?.let { Player(it) } }
    LaunchedEffect(player, player?.playing) {
        val p = player ?: return@LaunchedEffect
        if (!p.playing) return@LaunchedEffect
        var last = 0L
        while (p.playing) withFrameNanos { now -> if (last != 0L) p.advance((now - last) / 1_000_000); last = now }
    }
    val pose = player?.pose()
    LaunchedEffect(replay) { if (replay != null) { follow = true; sheet = false; pick = null } }

    Tile(Modifier.fillMaxSize().padding(20.dp, 14.dp, 20.dp, 14.dp), fill = !have) {
        if (!have) {
            Column(Modifier.align(Alignment.Center).padding(40.dp)) {
                Text("NO MAP DATA", style = Label.copy(color = Ink.tx))
                Text("The setup helper on the PC makes the map for your region; Setup fetches it over Wi-Fi.", style = Body, modifier = Modifier.padding(top = 8.dp))
                Text("Or copy a .pmtiles file to the unit and use Settings ⚙ › Map › Import map tiles.", style = Body)
            }
            TileLabel("MAP", "offline")
            return@Tile
        }

        Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { boxH = it.height }) {
            handle.places = places
            handle.onTap = { p -> if (p != null) { pick = p; poke() } else if (pick != null) pick = null else poke() }
            handle.onHold = { ll -> pick = Pick.Spot(ll.latitude, ll.longitude); poke() }
            handle.onMoved = { follow = false; poke() }
            handle.onZoomed = { d -> zoomStep = (zoomStep + d).coerceIn(-6, 4); poke() }
            // the trail is drawn only while a drive is being recorded
            OfflineMap(
                handle, fix, pose, if (recording != null) trail else emptyList(), places, replay, liftRpm, follow, zoomStep, threeD, tiles, nav, words,
                ghost, ghostRoute,
                Modifier.fillMaxSize().padding(1.dp),
            )
            // the car: the chevron live, the car itself on a replay
            val carPx = handle.carPx
            if (boxH > 0 && (follow || carPx != null)) {
                val d = LocalDensity.current
                // pinned to its place on screen only once the camera has arrived; until then it
                // is drawn at the car's real position, so it does not sail across the map
                val cx = if ((follow && !handle.settling) || carPx == null) null else carPx
                val yPx = cx?.y ?: (boxH * CAR_Y)
                val xPx = cx?.x
                if (pose != null) {
                    // the car from wherever the map is looking: behind it when heading-up, round
                    // the side once the map is turned, straight down when flat
                    val side = (CAR_DP * 2.0.pow((handle.camZoom - BASE_ZOOM).toDouble()).toFloat()).coerceIn(26f, 150f).dp
                    val az = (-Math.PI / 2 + Math.toRadians((pose.bearing - handle.camBearing).toDouble())).toFloat()
                    val el = Math.toRadians((90f - handle.camTilt).toDouble()).toFloat().coerceIn(0.12f, 1.45f)
                    val ox = with(d) { (xPx ?: 0f).toDp() - side / 2 }
                    val oy = with(d) { yPx.toDp() - side / 2 }
                    Car(
                        speedMph = pose.mph, camera = az to el, overlay = true,
                        modifier = Modifier.then(if (xPx == null) Modifier.align(Alignment.TopCenter) else Modifier).offset(if (xPx == null) 0.dp else ox, oy).size(side, side),
                    )
                } else {
                    val oy = with(d) { yPx.toDp() - 11.dp }
                    val ox = with(d) { (xPx ?: 0f).toDp() - 11.dp }
                    CarMarker(
                        Modifier.then(if (xPx == null) Modifier.align(Alignment.TopCenter) else Modifier).offset(if (xPx == null) 0.dp else ox, oy),
                        moving = (fix?.speedMph ?: 0f) > 2f, heading = (fix?.bearing ?: 0f) - handle.camBearing, tilt = handle.camTilt,
                    )
                }
            }
        }

        // captions
        if (player != null) {
            val s = player.drive.summary
            TileLabel("REPLAY", "${replayFmt.format(Date(s.startMs))} · ${Units.dist1(s.distM)} ${Units.distUnit} · MAX ${Units.speedInt(s.maxMph)}")
            TileNote("×${player.rate.toInt()}  ${fmtDuration(player.tMs)} / ${fmtDuration(player.lengthMs)}")
        } else {
            TileLabel("MAP", listOfNotNull("offline", Region.name.takeIf { it.isNotBlank() }, if (threeD) "3D" else null).joinToString(" · "))
            TileNote(fix?.let { f -> (road?.let { "$it  ·  " } ?: "") + "%s  %.4f  %.4f".format(compass(f.bearing), f.lat, f.lon) } ?: "NO FIX")
        }

        // speed and the recording, bottom left
        Row(Modifier.align(Alignment.BottomStart).padding(14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.background(Ink.bg.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text((pose?.mph ?: fix?.speedMph)?.toInt()?.toString() ?: "—", style = Numeral)
                    Text("  ${Units.speedUnit}", style = Data, modifier = Modifier.padding(bottom = 6.dp))
                }
            }
            recording?.let { r -> RecChip(r) }
        }

        // the controls, right edge and bottom right; they fade when idle
        val fade = Modifier.graphicsLayer { alpha = controlsAlpha }
        // one column of equal buttons on the right, the recentre slot at the bottom of it only
        // when the car is let go; the record and list buttons sit under it, same size, same gap
        val bsize = 54
        Column(Modifier.align(Alignment.CenterEnd).padding(end = 16.dp).offset(y = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(fade) { MapButton(text = if (threeD) "3D" else "2D", enabled = controlsOn, size = bsize) { togglePitch() } }
            Box(fade) { MapButton(Glyph.PLUS, enabled = controlsOn, size = bsize) { zoomBy(1) } }
            Box(fade) { MapButton(Glyph.MINUS, enabled = controlsOn, size = bsize) { zoomBy(-1) } }
            // shown whenever the map is not as it was left - let go, or zoomed off the auto level -
            // and it puts both back at once
            Box(Modifier.size(bsize.dp)) {
                if (!follow || zoomStep != 0) MapButton(Glyph.RECENTRE, hot = true, size = bsize) {
                    follow = true
                    if (zoomStep != 0) { zoomStep = 0; handle.camKey = null }
                    poke()
                }
            }
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(16.dp).then(fade), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (player == null) {
                MapButton(if (recording == null) Glyph.REC else Glyph.STOP, hot = recording != null, enabled = controlsOn && fix != null, size = bsize) {
                    if (recording == null) vm.startRecording() else vm.stopRecording(); poke()
                }
            }
            MapButton(Glyph.GHOST, hot = ghostArmed != 0L, enabled = controlsOn, size = bsize) {
                if (ghostArmed != 0L) vm.disarmGhost() else { sheet = true; searching = false }
                poke()
            }
            MapButton(Glyph.SEARCH, hot = searching, enabled = controlsOn, size = bsize) { searching = !searching; if (searching) sheet = false; poke() }
            MapButton(Glyph.LIST, hot = sheet, enabled = controlsOn, size = bsize) { sheet = !sheet; if (sheet) searching = false; poke() }
        }

        // chasing a previous run of this road
        if (player == null) ghost?.let { g -> GhostBar(g, Modifier.align(Alignment.TopCenter).padding(top = 34.dp)) }

        // the replay's transport, bottom centre
        if (player != null) ReplayBar(player, onClose = { vm.closeReplay() })

        // the route: the turn card while one is set, a word while it is being worked out
        nav?.let { n -> if (player == null) NavCard(n, voiceOn, onVoice = { vm.toggleVoice() }, onEnd = { vm.endNav() }) }
        if (routing && nav == null) Box(Modifier.align(Alignment.TopCenter).padding(top = 34.dp).background(Ink.bg.copy(alpha = 0.9f)).padding(14.dp, 9.dp)) {
            Text("ROUTING…", style = Label.copy(color = Ink.tx))
        }
        navError?.let { e ->
            LaunchedEffect(e) { delay(3000); vm.clearNavError() }
            Box(Modifier.align(Alignment.TopCenter).padding(top = 34.dp).background(Ink.red).padding(14.dp, 9.dp)) { Text(e, style = Label.copy(color = Ink.tx)) }
        }

        pick?.let { p ->
            if (player == null) PickCard(
                p, fix,
                onSavePin = { n, la, lo -> naming = Naming(la, lo, n.ifEmpty { "PIN ${places.size + 1}" }); pick = null },
                onDelete = { id -> vm.removePlace(id); pick = null },
                onRename = { place -> naming = Naming(place.lat, place.lon, place.name, place.id) },
                onNavigate = { n, la, lo -> vm.navigateTo(la, lo, n); pick = null; follow = true },
                canRoute = canRoute,
                onClose = { pick = null },
            )
        }
        if (searching && player == null) SearchSheet(
            fix, canRoute,
            onShow = { name, kind, la, lo ->
                follow = false; searching = false; pick = Pick.Found(name, kind, la, lo)
                handle.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(la, lo), 16.0), 900)
            },
            onNavigate = { name, la, lo -> vm.navigateTo(la, lo, name); searching = false; follow = true },
            onClose = { searching = false },
        )
        if (sheet) MapSheet(
            vm, drives, places, fix,
            onReplay = { id -> vm.openReplay(id) },
            onDeleteDrive = { id -> vm.deleteDrive(id) },
            onGoTo = { place ->
                follow = false; sheet = false; pick = Pick.Pin(place)
                handle.map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(place.lat, place.lon), 16.0), 900)
            },
            onDeletePlace = { id -> vm.removePlace(id); if ((pick as? Pick.Pin)?.place?.id == id) pick = null },
            onPinHere = { fix?.let { f -> naming = Naming(f.lat, f.lon, "PIN ${places.size + 1}") } },
            onNavigate = { place -> vm.navigateTo(place.lat, place.lon, place.name); sheet = false; follow = true },
            onClose = { sheet = false },
        )
        // what the drive just recorded came to
        finished?.let { d ->
            DriveCard(
                d, ghostResult,
                onReplay = { vm.closeCard(); vm.clearGhostResult(); vm.openReplay(d.summary.id) },
                onClose = { vm.closeCard(); vm.clearGhostResult() },
            )
        }
        naming?.let { n ->
            NameDialog(
                if (n.existing == null) "Name this pin" else "Rename pin", n.initial,
                onDone = { name -> if (n.existing == null) vm.addPlace(name, n.lat, n.lon) else vm.renamePlace(n.existing, name); naming = null; pick = null },
                onCancel = { naming = null },
            )
        }
    }
}

/** A pin being named: where, the suggested name, and which pin if it already exists. */
private class Naming(val lat: Double, val lon: Double, val initial: String, val existing: Long? = null)

/** The red dot and the figures while a drive is being recorded. */
@Composable
private fun RecChip(r: DashViewModel.Recording) {
    val t = rememberInfiniteTransition(label = "rec")
    val blink by t.animateFloat(1f, 0.25f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "blink")
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Row(Modifier.background(Ink.bg.copy(alpha = 0.85f)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(9.dp)) { drawCircle(Ink.red.copy(alpha = blink)) }
        Text("  REC  ${fmtDuration(now - r.startMs)}  ·  %.1f MI".format(r.distM / 1609.344), style = Label.copy(color = Ink.tx))
    }
}

/** Play/pause, the rate, a scrubber, and the way out. */
@Composable
private fun BoxScope.ReplayBar(p: Player, onClose: () -> Unit) {
    Row(
        Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).background(Ink.bg.copy(alpha = 0.88f)).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MapButton(if (p.playing) Glyph.PAUSE else Glyph.PLAY, size = 36) { if (!p.playing && p.fraction >= 1f) p.restart() else p.playing = !p.playing }
        MapButton(text = "×${p.rate.toInt()}", size = 36) { p.cycleRate() }
        Box(
            Modifier.width(panelWidth(300.dp, 0.5f)).height(36.dp).pointerInput(p) {
                detectTapGestures { o -> p.seek(o.x / size.width) }
            }.padding(vertical = 15.dp).background(Ink.line2),
        ) {
            Box(Modifier.fillMaxSize().graphicsLayer { scaleX = p.fraction; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }.background(Ink.red))
        }
        MapButton(Glyph.CLOSE, size = 36, onClick = onClose)
    }
}

/**
 * The car live: a small hollow chevron with a slow sonar ring breathing out of it. The ring only
 * runs when the car is moving, so a parked car is a still mark. `heading` turns it when the map
 * is not heading-up; `tilt` lays it flat on the road when the map is pitched.
 */
@Composable
private fun CarMarker(modifier: Modifier, moving: Boolean, heading: Float, tilt: Float) {
    val t = rememberInfiniteTransition(label = "sonar")
    val ring by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "ring")
    // laid on the ground: leaned back by the map's pitch, then turned to the heading
    Canvas(modifier.size(22.dp).graphicsLayer { rotationX = -tilt; cameraDistance = 24f; rotationZ = heading }) {
        val c = center
        if (moving) {
            val r = size.minDimension * (0.35f + 1.1f * ring)
            drawCircle(Ink.red.copy(alpha = (1f - ring) * 0.45f), r, c, style = Stroke(1.dp.toPx()))
        }
        val p = Path().apply {
            moveTo(c.x, c.y - size.height * 0.46f)
            lineTo(c.x + size.width * 0.38f, c.y + size.height * 0.42f)
            lineTo(c.x, c.y + size.height * 0.18f)
            lineTo(c.x - size.width * 0.38f, c.y + size.height * 0.42f)
            close()
        }
        drawPath(p, Ink.bg, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        drawPath(p, Ink.red, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(Ink.tx, 1.4.dp.toPx(), c + Offset(0f, size.height * 0.02f))
    }
}

@Composable
private fun OfflineMap(
    h: MapHandle, fix: Fix?, pose: Pose?, trail: List<DoubleArray>, places: List<Place>, replay: Drive?, liftRpm: Int,
    follow: Boolean, zoomStep: Int, threeD: Boolean, tiles: File, nav: NavState?, words: MapWords,
    ghost: com.celica.dash.data.GhostState?, ghostRoute: List<com.celica.dash.data.DrivePoint>?, modifier: Modifier,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(ctx)
        MapView(ctx)
    }
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs); mapView.onDestroy(); h.map = null }
    }
    // where the car is on screen, for the marker while the map is let go
    val carLatLng = pose?.let { LatLng(it.lat, it.lon) } ?: fix?.let { LatLng(it.lat, it.lon) }
    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                getMapAsync { map: MapLibreMap ->
                    h.map = map
                    val style = MapLayers.styleJson(ctx, tiles)
                    map.setStyle(Style.Builder().fromJson(style)) { st -> MapLayers.install(st); h.styleReady++ }
                    map.uiSettings.isAttributionEnabled = false
                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.isCompassEnabled = false
                    map.uiSettings.isRotateGesturesEnabled = true
                    map.uiSettings.isTiltGesturesEnabled = true
                    // where the car is, if it is known by the time the map is built; the whole
                    // country only when there is no fix at all to open on
                    val here = fix?.let { LatLng(it.lat, it.lon) }
                    map.cameraPosition = if (here != null) {
                        h.snapped = true
                        CameraPosition.Builder().target(here).zoom(16.5).bearing(fix?.bearing?.toDouble() ?: 0.0).tilt(if (threeD) TILT_3D else 0.0).build()
                    } else {
                        CameraPosition.Builder().target(LatLng(Region.homeLat, Region.homeLon)).zoom(Region.homeZoom).build()
                    }
                    // a finger dragging the map lets go of the car; a pinch just changes the zoom we keep
                    map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                        override fun onMoveBegin(detector: MoveGestureDetector) = h.onMoved()
                        override fun onMove(detector: MoveGestureDetector) {}
                        override fun onMoveEnd(detector: MoveGestureDetector) {}
                    })
                    map.addOnRotateListener(object : MapLibreMap.OnRotateListener {
                        override fun onRotateBegin(detector: org.maplibre.android.gestures.RotateGestureDetector) = h.onMoved()
                        override fun onRotate(detector: org.maplibre.android.gestures.RotateGestureDetector) {}
                        override fun onRotateEnd(detector: org.maplibre.android.gestures.RotateGestureDetector) {}
                    })
                    map.addOnShoveListener(object : MapLibreMap.OnShoveListener {
                        override fun onShoveBegin(detector: org.maplibre.android.gestures.ShoveGestureDetector) = h.onMoved()
                        override fun onShove(detector: org.maplibre.android.gestures.ShoveGestureDetector) {}
                        override fun onShoveEnd(detector: org.maplibre.android.gestures.ShoveGestureDetector) {}
                    })
                    var zoomAtStart = 0.0
                    map.addOnScaleListener(object : MapLibreMap.OnScaleListener {
                        override fun onScaleBegin(detector: StandardScaleGestureDetector) { zoomAtStart = map.cameraPosition.zoom }
                        override fun onScale(detector: StandardScaleGestureDetector) {}
                        override fun onScaleEnd(detector: StandardScaleGestureDetector) {
                            val d = Math.round(map.cameraPosition.zoom - zoomAtStart).toInt()
                            if (d != 0) h.onZoomed(d)
                        }
                    })
                    // a tap picks a pin or a fuel stop, else just wakes the controls; a hold drops a pin
                    map.addOnMapClickListener { ll ->
                        val pt = map.projection.toScreenLocation(ll)
                        val box = RectF(pt.x - 18f, pt.y - 18f, pt.x + 18f, pt.y + 18f)
                        val hits = map.queryRenderedFeatures(box, MapLayers.PIN_LAYER, MapLayers.PIN_LABEL, MapLayers.POI_LAYER)
                        val f = hits.firstOrNull()
                        val pick: Pick? = when {
                            f == null -> null
                            f.hasProperty("pid") -> h.places.firstOrNull { it.id == f.getNumberProperty("pid").toLong() }?.let { Pick.Pin(it) }
                            else -> (f.geometry() as? Point)?.let { g ->
                                Pick.Found(f.getStringProperty("name") ?: "", f.getStringProperty("kind") ?: "", g.latitude(), g.longitude())
                            }
                        }
                        h.onTap(pick)
                        true
                    }
                    map.addOnMapLongClickListener { ll -> h.onHold(ll); true }
                    map.addOnCameraMoveListener { h.project(); h.readCamera() }
                    map.addOnCameraIdleListener { h.readCamera(); h.onIdle() }
                }
            }
        },
        update = { v ->
            val map = h.map ?: return@AndroidView
            val st = map.style
            @Suppress("UNUSED_VARIABLE") val ready = h.styleReady          // read so a late style still gets its data
            if (st != null && st.isFullyLoaded) {
                if (h.pinsApplied !== places) { MapLayers.setPins(st, places); h.pinsApplied = places }
                if (h.trailApplied !== trail) { MapLayers.setTrail(st, if (replay == null) trail else emptyList()); h.trailApplied = trail }
                if (h.replayApplied !== replay) { MapLayers.setReplay(st, replay?.pts, liftRpm); h.replayApplied = replay; h.cursorApplied = -1; h.trailApplied = null }
                if (pose != null && h.cursorApplied != pose.index) { MapLayers.setReplayCursor(st, pose.index); h.cursorApplied = pose.index }
                if (h.ghostApplied !== ghostRoute) { MapLayers.setGhostRoute(st, ghostRoute); h.ghostApplied = ghostRoute }
                MapLayers.setGhostAt(st, ghost?.lat, ghost?.lon)
                val routeKey = nav?.let { it.route to it.pointIdx }
                if (h.routeApplied != routeKey) { MapLayers.setRoute(st, nav?.route?.points, nav?.pointIdx ?: 0); h.routeApplied = routeKey }
                if (h.wordsApplied != words) {
                    MapLayers.setMapWords(st, words.streets, words.places, words.buildings)
                    h.wordsApplied = words; h.threeDApplied = null
                }
                if (h.threeDApplied != threeD) {
                    st.getLayer("buildings-3d")?.setProperties(PropertyFactory.visibility(if (threeD && words.buildings) Property.VISIBLE else Property.NONE))
                    st.getLayer("buildings")?.maxZoom = if (threeD) 15.5f else 24f
                    h.threeDApplied = threeD
                }
            }
            // the marker's place when the map is let go
            h.carLatLng = carLatLng; h.following = follow; h.project()
            if (!follow) { h.camKey = null; return@AndroidView }
            val top = (v.height * (2 * CAR_Y - 1)).toDouble().coerceAtLeast(0.0)
            val tilt = if (threeD) TILT_3D else 0.0
            if (pose != null) {
                // the replay: the car is moved every frame, so the camera just goes there
                val pos = CameraPosition.Builder().target(LatLng(pose.lat, pose.lon)).zoom((16.5 + zoomStep).coerceIn(10.0, 19.0))
                    .bearing(pose.bearing.toDouble()).tilt(tilt).padding(0.0, top, 0.0, 0.0).build()
                map.moveCamera(CameraUpdateFactory.newCameraPosition(pos))
                return@AndroidView
            }
            val f = fix ?: return@AndroidView
            // the first fix after the map opens is jumped to, not flown to from the whole country
            if (!h.snapped) {
                h.snapped = true
                map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder().target(LatLng(f.lat, f.lon)).zoom((16.5 + zoomStep).coerceIn(10.0, 19.0))
                        .bearing(f.bearing.toDouble()).tilt(tilt).padding(0.0, top, 0.0, 0.0).build(),
                ))
            }
            val key = listOf(f, zoomStep, threeD)
            if (h.camKey == key) return@AndroidView
            h.camKey = key
            val auto = if (f.speedMph > 45) 15.0 else 16.5
            val pos = CameraPosition.Builder()
                .target(LatLng(f.lat, f.lon))
                .zoom((auto + zoomStep).coerceIn(10.0, 19.0))
                .bearing(f.bearing.toDouble())
                .tilt(tilt)
                .padding(0.0, top, 0.0, 0.0)
                .build()
            map.animateCamera(CameraUpdateFactory.newCameraPosition(pos), 900)
        },
    )
}
