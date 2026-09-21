package com.celica.dash.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celica.dash.apps.AppEntry
import com.celica.dash.apps.Apps
import com.celica.dash.data.Drive
import com.celica.dash.data.DrivePoint
import com.celica.dash.data.DriveStore
import com.celica.dash.data.DriveSummary
import com.celica.dash.data.GarageStore
import com.celica.dash.data.Place
import com.celica.dash.data.PlaceStore
import com.celica.dash.data.Prefs
import com.celica.dash.data.SiteData
import com.celica.dash.location.Fix
import com.celica.dash.location.haversineM
import com.celica.dash.location.LocationRepository
import com.celica.dash.media.NowPlaying
import com.celica.dash.media.NowPlayingRepository
import com.celica.dash.obd.DemoDrive
import com.celica.dash.phone.PhoneRepository
import com.celica.dash.phone.PhoneState
import com.celica.dash.routing.NavState
import com.celica.dash.routing.Navigator
import com.celica.dash.routing.Router
import com.celica.dash.routing.Voice
import com.celica.dash.obd.ObdRepository
import com.celica.dash.obd.ObdStatus
import com.celica.dash.obd.VehicleState
import com.celica.dash.weather.Weather
import com.celica.dash.weather.WeatherRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

/**
 * Everything the screens read, in one place. Sources (OBD, GPS, media, weather) publish into it;
 * screens only observe. The vehicle state is OBD when there is a dongle, otherwise GPS for speed
 * and nothing else; the demo drive overrides both while it runs.
 */
class DashViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val site = SiteData(app)
    val garage = GarageStore(app)
    val driveStore = DriveStore(app)
    val placeStore = PlaceStore(app)
    val drives: StateFlow<List<DriveSummary>> get() = driveStore.drives
    val places: StateFlow<List<Place>> = placeStore.places.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val qf = com.celica.dash.media.QfBluetooth(app)
    val media = NowPlayingRepository(app, qf)
    /** The songs on the unit, played by the dash itself; its session shows up through [media]. */
    val library = com.celica.dash.media.LocalPlayer(app)
    val call: StateFlow<com.celica.dash.media.Call?> = qf.state.map { it.call }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val nowPlaying: StateFlow<NowPlaying> get() = media.state

    // Android's radio. Only when the vendor's service drives a radio of its own does this side
    // keep the phone's music and calls off itself; on a one-radio unit Android's sink is the
    // music, and must be left alone
    val phone = PhoneRepository(app, vendorAudio = { qf.externalRadio() })
    val phoneState: StateFlow<PhoneState> get() = phone.state

    val location = LocationRepository(app)
    /** The phone's GPS over the hotspot, for when the unit's aerial has nothing. */
    val phoneGps = com.celica.dash.location.PhoneGps(app)
    /** The same phone, over Bluetooth instead: no hotspot, and the Wi-Fi stays on the house. */
    val phoneGpsBle = com.celica.dash.location.PhoneGpsBle(app)
    /** The star ceiling, over BLE on the same radio. */
    val lights = com.celica.dash.lights.StarLights(app)
    private val _starsOpen = MutableStateFlow(false)
    val starsOpen: StateFlow<Boolean> = _starsOpen.asStateFlow()
    fun showStars(open: Boolean) { _starsOpen.value = open }
    val phoneGpsSetting: StateFlow<String> = prefs.phoneGps.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setPhoneGps(v: String) = viewModelScope.launch { prefs.setPhoneGps(v) }
    /** The demo drive's made-up position while it runs, so the map can be judged without a car. */
    private val demoFix = MutableStateFlow<Fix?>(null)
    /** Where the car is: the demo while it runs, else the unit's GPS, else the phone's. */
    val fix: StateFlow<Fix?> = combine(location.fix, demoFix, phoneGps.fix, phoneGpsBle.fix) { r, d, p, b ->
        val fresh = { f: Fix? -> f?.takeIf { System.currentTimeMillis() - it.atMs < 15_000 } }
        d ?: fresh(r) ?: fresh(p) ?: fresh(b) ?: r ?: p ?: b
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val weatherRepo = WeatherRepository(app)
    val weather: StateFlow<Weather?> get() = weatherRepo.weather

    val obd = ObdRepository(app, viewModelScope)
    /** What this session of driving has amounted to, for the Track face. */
    private val sessionTracker = com.celica.dash.obd.SessionTracker()
    val session: StateFlow<com.celica.dash.obd.Session> = sessionTracker.state
    fun resetSession() = sessionTracker.reset()
    val driveFace: StateFlow<String> = prefs.driveFace.stateIn(viewModelScope, SharingStarted.Eagerly, "CLASSIC")
    fun setDriveFace(f: String) = viewModelScope.launch { prefs.setDriveFace(f) }
    val backdrop: StateFlow<String> = prefs.backdrop.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setBackdrop(f: String) = viewModelScope.launch { prefs.setBackdrop(f) }
    val crt: StateFlow<Boolean> = prefs.crt.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    fun setCrt(on: Boolean) = viewModelScope.launch { prefs.setCrt(on) }
    val analogDial: StateFlow<String> = prefs.analogDial.stateIn(viewModelScope, SharingStarted.Eagerly, "retro")
    fun setAnalogDial(k: String) = viewModelScope.launch { prefs.setAnalogDial(k) }
    val obdStatus: StateFlow<ObdStatus> get() = obd.status
    private val demo = MutableStateFlow<VehicleState?>(null)

    private val _vehicle = MutableStateFlow(VehicleState())
    val vehicle: StateFlow<VehicleState> = _vehicle.asStateFlow()

    private val _booted = MutableStateFlow(false)
    val booted: StateFlow<Boolean> = _booted.asStateFlow()
    fun markBooted() { _booted.value = true }

    /** This drive's trail as [lat, lon] points, one every ~15 m of movement; cleared on a cold start. */
    private val _trail = MutableStateFlow<List<DoubleArray>>(emptyList())
    val trail: StateFlow<List<DoubleArray>> = _trail.asStateFlow()
    fun clearTrail() { _trail.value = emptyList() }

    /** A manual page another screen asked to open; the Manual section consumes it. */
    private val _manualPage = MutableStateFlow<Int?>(null)
    val manualPage: StateFlow<Int?> = _manualPage.asStateFlow()
    fun openManual(page: Int?) { _manualPage.value = page }

    /** Copying a picked tiles file into the app's storage: null when idle, else 0..1. */
    private val _tilesImport = MutableStateFlow<Float?>(null)
    val tilesImport: StateFlow<Float?> = _tilesImport.asStateFlow()
    fun importTiles(uri: android.net.Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val total = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        runCatching { app.contentResolver.openInputStream(uri)!!.use { copyTiles(it, total) } }
    }

    /** Streams a tiles file into place; the map picks it up on its next open. */
    private fun copyTiles(inp: java.io.InputStream, total: Long) {
        val dest = com.celica.dash.ui.map.ownTilesFile(getApplication())
        val tmp = java.io.File(dest.path + ".part")
        try {
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 20); var done = 0L; var n: Int
                _tilesImport.value = 0f
                while (inp.read(buf).also { n = it } > 0) {
                    out.write(buf, 0, n); done += n
                    if (total > 0) _tilesImport.value = (done.toFloat() / total).coerceIn(0f, 0.999f)
                }
            }
            dest.delete(); tmp.renameTo(dest)
        } catch (e: Exception) {
            tmp.delete(); throw e
        } finally {
            _tilesImport.value = null
        }
    }

    // ---- fetching from the PC ---------------------------------------------------------------
    // No USB stick needed: the setup helper (RUN ME) serves its payload on the PC; the unit, on the same
    // Wi-Fi, pulls the tiles, the graph and new builds of the app straight from it.
    val pcHost: StateFlow<String> = prefs.pcHost.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    fun setPcHost(h: String) = viewModelScope.launch { prefs.setPcHost(h.trim()) }
    private val _fetchNote = MutableStateFlow<String?>(null)
    val fetchNote: StateFlow<String?> = _fetchNote.asStateFlow()

    private fun open(path: String): Pair<java.io.InputStream, Long> {
        val host = pcHost.value.ifBlank { throw IllegalStateException("no PC address set") }
        val c = java.net.URL(com.celica.dash.data.Helper.base(host) + "/$path").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 30000
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        return java.io.BufferedInputStream(c.inputStream, 1 shl 20) to c.contentLengthLong
    }

    fun fetchTiles() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _fetchNote.value = "Fetching map tiles…"
        runCatching { open("payload/map.pmtiles").let { (i, t) -> i.use { copyTiles(it, t) } } }
            .onSuccess { _fetchNote.value = "Map tiles fetched - reopen the map" }.onFailure { _fetchNote.value = "Tiles: ${it.message}" }
    }

    fun fetchGraph() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _fetchNote.value = "Fetching routing graph…"
        runCatching { open("payload/routing.zip").let { (i, t) -> i.use { unzipGraph(it, t) } } }
            .onSuccess { _fetchNote.value = "Routing graph fetched" }.onFailure { _fetchNote.value = "Graph: ${it.message}" }
    }

    /**
     * The map asks for this when it opens: the index is read in the background so the first
     * search is instant, and one that is missing or of an older make comes over on its own.
     */
    fun ensureSearchIndex() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (!com.celica.dash.search.PlaceSearch.usable(getApplication()) && pcHost.value.isNotBlank()) fetchSearch()
    }

    /** Pulls the search index - small, and worth re-pulling whenever the tiles change. */
    fun fetchSearch() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _fetchNote.value = "Fetching the search index…"
        runCatching {
            val dest = com.celica.dash.search.PlaceSearch.file(getApplication())
            val tmp = java.io.File(dest.path + ".part")
            open("payload/search.idx").first.use { inp -> tmp.outputStream().use { inp.copyTo(it, 1 shl 20) } }
            dest.delete(); tmp.renameTo(dest)
            com.celica.dash.search.PlaceSearch.forget()
        }.onSuccess { _fetchNote.value = "Search index fetched" }.onFailure { _fetchNote.value = "Search: ${it.message}" }
    }

    /** Pulls the films the Theatre face plays. */
    fun fetchFaces() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _fetchNote.value = "Fetching the backdrops\u2026"
        runCatching {
            val dir = com.celica.dash.ui.drive.backdropDir(getApplication())
            var n = 0
            open("payload/faces.zip").first.use { inp ->
                java.util.zip.ZipInputStream(inp).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val name = e.name.substringAfterLast('/')
                        if (!e.isDirectory && name.endsWith(".mp4", true)) { java.io.File(dir, name).outputStream().use { z.copyTo(it) }; n++ }
                        e = z.nextEntry
                    }
                }
            }
            n
        }.onSuccess { _fetchNote.value = "Backdrops fetched - $it" }.onFailure { _fetchNote.value = "Backdrops: ${it.message}" }
    }

    /** Pulls a prepared pack of cover art, so a playlist has its covers before it is played. */
    fun fetchArt() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        _fetchNote.value = "Fetching the cover art\u2026"
        runCatching {
            val dir = com.celica.dash.media.artDir(getApplication())
            var n = 0
            open("payload/art.zip").first.use { inp ->
                java.util.zip.ZipInputStream(inp).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val name = e.name.substringAfterLast('/')
                        if (!e.isDirectory && name.endsWith(".jpg")) { java.io.File(dir, name).outputStream().use { z.copyTo(it) }; n++ }
                        e = z.nextEntry
                    }
                }
            }
            n
        }.onSuccess { _fetchNote.value = "Cover art fetched - $it covers" }.onFailure { _fetchNote.value = "Art: ${it.message}" }
    }

    /** Pulls the latest build and hands it to the package installer. */
    fun fetchUpdate() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        if (!app.packageManager.canRequestPackageInstalls()) {
            // Android wants a one-time yes before an app may hand over an APK to install
            _fetchNote.value = "Allow Celica to install apps on the screen that opens, then tap again"
            runCatching { app.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:" + app.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return@launch
        }
        _fetchNote.value = "Fetching the app…"
        runCatching {
            val f = java.io.File(app.cacheDir, "update.apk")
            open("payload/celicadash.apk").let { (i, total) ->
                i.use { inp ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20); var done = 0L; var n: Int
                        while (inp.read(buf).also { n = it } > 0) {
                            out.write(buf, 0, n); done += n
                            _fetchNote.value = "Fetching the app… ${done / 1_000_000} MB" + if (total > 0) " of ${total / 1_000_000}" else ""
                        }
                    }
                }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".files", f)
            app.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onSuccess { _fetchNote.value = "Downloaded - the installer is open; tap Install there" }.onFailure { _fetchNote.value = "Update: ${it.message}" }
    }

    // ---- drive recording --------------------------------------------------------------------
    /** Live figures for the drive being recorded; null when not recording. */
    class Recording(val startMs: Long, val distM: Double, val maxMph: Float, val liftMs: Long, val points: Int)
    private val _recording = MutableStateFlow<Recording?>(null)
    val recording: StateFlow<Recording?> = _recording.asStateFlow()
    private val recPts = ArrayList<DrivePoint>()

    fun startRecording() {
        if (_recording.value != null) return
        recPts.clear(); _trail.value = emptyList()
        _recording.value = Recording(System.currentTimeMillis(), 0.0, 0f, 0L, 0)
    }

    /** Stops and saves, unless the drive never really went anywhere. */
    fun stopRecording() = viewModelScope.launch {
        val r = _recording.value ?: return@launch
        _recording.value = null
        val pts = ArrayList(recPts); recPts.clear()
        if (pts.size < 2 || r.distM < 50.0) return@launch
        val s = DriveSummary(r.startMs, r.startMs, pts.last().t, r.distM, r.maxMph, r.liftMs, pts.size, null)
        driveStore.save(s, pts)
        _finished.value = com.celica.dash.data.Drive(s, pts)          // the card, over the map
    }

    /**
     * The run being chased. One drive is armed; the chase starts by itself at that drive's start
     * and the attempt is recorded, so beating it can replace the target with the better run.
     */
    val ghost = com.celica.dash.data.Ghost()
    val ghostState: StateFlow<com.celica.dash.data.GhostState?> get() = ghost.state
    private val _ghostArmed = MutableStateFlow(0L)
    val ghostArmed: StateFlow<Long> = _ghostArmed.asStateFlow()
    /** The armed run's line, for the map to lay down. */
    private val _ghostRoute = MutableStateFlow<List<com.celica.dash.data.DrivePoint>?>(null)
    val ghostRoute: StateFlow<List<com.celica.dash.data.DrivePoint>?> = _ghostRoute.asStateFlow()
    private val _ghostResult = MutableStateFlow<com.celica.dash.data.GhostResult?>(null)
    val ghostResult: StateFlow<com.celica.dash.data.GhostResult?> = _ghostResult.asStateFlow()
    fun clearGhostResult() { _ghostResult.value = null }

    fun armGhost(id: Long) = viewModelScope.launch {
        val d = driveStore.load(id) ?: return@launch
        ghost.arm(d)
        _ghostArmed.value = id
        _ghostRoute.value = d.pts
        prefs.setGhostDrive(id)
    }

    fun disarmGhost() = viewModelScope.launch {
        ghost.disarm()
        _ghostArmed.value = 0L
        _ghostRoute.value = null
        prefs.setGhostDrive(0L)
    }

    /** Started and stopped by the ghost, so an attempt is always recorded and can replace it. */
    private fun wireGhost() {
        ghost.onStart = { if (_recording.value == null) startRecording() }
        ghost.onAbort = { if (_recording.value != null) stopRecording() }
        ghost.onFinish = { r ->
            _ghostResult.value = r
            viewModelScope.launch {
                val id = _recording.value?.startMs
                stopRecording()
                // the target is always your best: beat it and the new run takes its place
                if (r.beat && id != null) {
                    kotlinx.coroutines.delay(300)
                    armGhost(id)
                }
            }
        }
    }

    /** The drive just recorded, while its card is up. */
    private val _finished = MutableStateFlow<com.celica.dash.data.Drive?>(null)
    val finished: StateFlow<com.celica.dash.data.Drive?> = _finished.asStateFlow()
    fun closeCard() { _finished.value = null }

    private fun recordFix(f: Fix) {
        val r = _recording.value ?: return
        val prev = recPts.lastOrNull()
        if (prev != null && f.atMs - prev.t < 500) return
        val v = _vehicle.value
        val rpm = v.rpm ?: 0
        val step = if (prev == null) 0.0 else haversineM(prev.lat, prev.lon, f.lat, f.lon)
        if (step > 400.0 && prev != null) return                            // a GPS jump, not a drive
        val lift = if (prev != null && com.celica.dash.data.CarSpec.liftRpm?.let { rpm >= it } == true) f.atMs - prev.t else 0L
        recPts += DrivePoint(f.atMs, f.lat, f.lon, f.speedMph, f.bearing, rpm)
        if (recPts.size > 40_000) recPts.removeAt(0)
        _recording.value = Recording(r.startMs, r.distM + step, maxOf(r.maxMph, f.speedMph), r.liftMs + lift, recPts.size)
    }

    fun deleteDrive(id: Long) = viewModelScope.launch { driveStore.delete(id) }
    fun renameDrive(id: Long, name: String?) = viewModelScope.launch { driveStore.rename(id, name) }

    /** The drive being replayed on the map, once loaded; null when the map is live. */
    private val _replay = MutableStateFlow<Drive?>(null)
    val replay: StateFlow<Drive?> = _replay.asStateFlow()
    fun openReplay(id: Long) = viewModelScope.launch { _replay.value = driveStore.load(id) }
    fun closeReplay() { _replay.value = null }

    // ---- style ------------------------------------------------------------------------------
    val look: StateFlow<String> = prefs.look.stateIn(viewModelScope, SharingStarted.Eagerly, "ink")
    val zoom: StateFlow<Float> = prefs.zoom.stateIn(viewModelScope, SharingStarted.Eagerly, 1f)
    /** What the map draws by itself: the icon categories, and which of its words it shows. */
    val mapIcons: StateFlow<Set<String>> = prefs.mapIcons.stateIn(viewModelScope, SharingStarted.Eagerly, setOf("FUEL"))
    val mapStreets: StateFlow<Boolean> = prefs.mapStreets.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val mapPlaces: StateFlow<Boolean> = prefs.mapPlaces.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val mapBuildings: StateFlow<Boolean> = prefs.mapBuildings.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    fun toggleMapIcon(cat: String) = viewModelScope.launch {
        val now = mapIcons.value
        prefs.setMapIcons(if (cat in now) now - cat else now + cat)
    }
    fun setMapWords(streets: Boolean? = null, places: Boolean? = null, buildings: Boolean? = null) = viewModelScope.launch {
        streets?.let { prefs.setMapStreets(it) }; places?.let { prefs.setMapPlaces(it) }; buildings?.let { prefs.setMapBuildings(it) }
    }

    /** The app's own version, for the boot ceremony and the diagnostics. */
    val version: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?" }.getOrDefault("?")
    /** How many triangles the chosen wireframe has - read from its metadata, before it is loaded. */
    val modelFaces: Int by lazy { com.celica.dash.car.CarModel.faces(app) }

    /** Saves the look or zoom and rebuilds the screen with it. */
    fun setStyle(look: String? = null, zoom: Float? = null) = viewModelScope.launch {
        look?.let { prefs.setLook(it) }; zoom?.let { prefs.setZoom(it) }
        kotlinx.coroutines.delay(50)
        activity?.recreate()
    }

    // ---- other apps -------------------------------------------------------------------------
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()
    /** The CarPlay app: the one set in Settings, else the best guess among what is installed. */
    val carPlay: StateFlow<AppEntry?> = combine(_apps, prefs.carPlayPackage) { list, pkg ->
        pkg?.let { p -> list.firstOrNull { it.pkg == p } } ?: Apps.guessCarPlay(list)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun refreshApps() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _apps.value = Apps.installed(getApplication()) }
    /** The unit's Bluetooth music app: the one set in Settings, else a guess by name. */
    val btMusicApp: StateFlow<AppEntry?> = combine(_apps, prefs.btMusicPackage) { list, pkg ->
        pkg?.let { p -> list.firstOrNull { it.pkg == p } } ?: Apps.guessBtMusic(list)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun setBtMusicApp(pkg: String?) = viewModelScope.launch { prefs.setBtMusicPackage(pkg) }

    /** The unit's own address on the current Wi-Fi, for the phone to send to. */
    fun unitAddress(): String? = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress
    }.getOrNull()

    /**
     * Settings by name, for the PC: handy for setting a second unit up the same way as the car
     * without walking through the pages, and for trying a look without leaving the driveway.
     */
    fun setPref(key: String, value: String): String {
        viewModelScope.launch {
            when (key) {
                "look" -> prefs.setLook(value)
                "zoom" -> value.toFloatOrNull()?.let { prefs.setZoom(it) }
                "face" -> prefs.setDriveFace(value.uppercase())
                "backdrop" -> prefs.setBackdrop(value)
                "crt" -> prefs.setCrt(value != "0")
                "dial" -> prefs.setAnalogDial(value.lowercase())
                "icons" -> prefs.setMapIcons(value.split(",").filter { it.isNotBlank() }.map { it.uppercase() }.toSet())
                "streets" -> prefs.setMapStreets(value != "0")
                "places" -> prefs.setMapPlaces(value != "0")
                "buildings" -> prefs.setMapBuildings(value != "0")
                "phone_gps" -> prefs.setPhoneGps(value)
                "ghost" -> prefs.setGhostDrive(value.toLongOrNull() ?: 0L)
                "pc" -> prefs.setPcHost(value)
                "obd" -> prefs.setObdAddress(value.ifBlank { null })
                "stars" -> when (value.trim().lowercase()) {           // "on"/"off" wake or vault the client; else bytes for the lights
                    "on" -> lights.start(); "off" -> lights.stop(); else -> lights.raw(value)
                }
            }
            if (key == "look" || key == "zoom") { kotlinx.coroutines.delay(50); activity?.recreate() }
        }
        return "$key = $value"
    }

    /** What the unit is doing, in words, for when something does not work in the car. */
    fun diagnostics(): List<Pair<String, String>> = listOf(
        "Last crash" to runCatching { java.io.File(getApplication<Application>().filesDir, "last-crash.txt").readText().lines().take(6).joinToString(System.lineSeparator()) }.getOrDefault("none recorded"),
        "Build" to runCatching { getApplication<Application>().packageManager.getPackageInfo(getApplication<Application>().packageName, 0) }
            .map { "${it.versionName} · installed ${com.celica.dash.data.Units.date("d MMM HH:mm").format(java.util.Date(it.lastUpdateTime))}" }.getOrDefault("?"),
        "Unit" to com.celica.dash.apps.UnitProfile.describe(),
        "GPS" to location.status(),
        "Phone GPS" to phoneGps.status.value + (phoneGps.fix.value?.let { " · fix %.4f %.4f".format(it.lat, it.lon) } ?: ""),
        "Phone GPS (BLE)" to phoneGpsBle.describe() + (phoneGpsBle.fix.value?.let { " · fix %.4f %.4f".format(it.lat, it.lon) } ?: ""),
        "Media" to media.describeSessions(),
        "Media control" to media.describeControl(),
        "Library" to library.describe(),
        "Stars" to lights.describe(),
        "QF Bluetooth" to qf.state.value.let { q -> if (!q.available) "no vendor service" else "connected ${q.connected} · ${q.title ?: "no track"} · ${q.positionMs}/${q.durationMs} · playing ${q.playing} · call ${q.call?.kind ?: "none"}" },
        // two radios: Android's, which is ours, and the vendor's, which carries the audio and
        // is only visible through the properties its service leaves behind
        "Bluetooth (Android)" to phone.describe(),
        "Bluetooth (vendor)" to qf.describeConfig(),
        "OBD" to obd.describe(),
        "Routing graph" to if (router.available()) "imported" else "not imported",
        "Map tiles" to com.celica.dash.ui.map.tilesFile(getApplication()).let { if (it.exists()) "%.2f GB".format(it.length() / 1e9) else "none" },
        "Map layers" to com.celica.dash.ui.map.MapLayers.report,
        "Search index" to com.celica.dash.search.PlaceSearch.status(getApplication()),
        "Apps seen" to "${_apps.value.size} · CarPlay: ${carPlay.value?.pkg ?: "?"} · BT music: ${btMusicApp.value?.pkg ?: "?"}",
        "CarPlay" to phoneLink.describe(),
        "Packages" to _apps.value.joinToString("  ") { it.pkg },
        "Link log" to com.celica.dash.data.LinkLog.recent(24).joinToString("\n").ifBlank { "nothing yet" },
    )
    fun openApp(pkg: String) = Apps.launch(getApplication(), pkg)
    /** CarPlay behind the dash's own curtain; zlink's page is never seen. */
    val phoneLink = com.celica.dash.apps.PhoneLink(app)
    fun openCarPlay(pkg: String) = phoneLink.open(pkg)
    /** The kind of phone, from Setup; the link and the phone tab follow it. */
    val phoneMode: StateFlow<String> = prefs.phoneMode.stateIn(viewModelScope, SharingStarted.Eagerly, "carplay")
    fun setPhoneMode(m: String) = viewModelScope.launch { prefs.setPhoneMode(m) }
    fun setCarPlayApp(pkg: String?) = viewModelScope.launch { prefs.setCarPlayPackage(pkg) }

    /** The activity in front, for the remote screenshot; set by MainActivity. */
    @Volatile var activity: android.app.Activity? = null
    private val remote = com.celica.dash.remote.RemoteAgent(
        app, host = { pcHost.value },
        diagnostics = { diagnostics().joinToString(System.lineSeparator()) { (k, v) -> "$k: $v" } },
        music = { c -> when (c) { "next" -> media.next(); "prev" -> media.previous(); "play" -> qf.play(); "pause" -> qf.pause(); else -> media.playPause() } },
        activity = { activity },
        update = { fetchUpdate() },
        fetch = { what ->
            when (what) {
                "tiles" -> fetchTiles(); "graph" -> fetchGraph(); "art" -> fetchArt(); "faces" -> fetchFaces(); else -> fetchSearch()
            }
        },
        pref = { key, value -> setPref(key, value) },
        go = { name -> com.celica.dash.ui.shell.Section.entries.firstOrNull { it.name.equals(name, true) }?.let { _sectionRequest.value = it } },
    )
    /** A section another part of the app (or the PC) asked to show; the shell consumes it. */
    private val _sectionRequest = MutableStateFlow<com.celica.dash.ui.shell.Section?>(null)
    val sectionRequest: StateFlow<com.celica.dash.ui.shell.Section?> = _sectionRequest.asStateFlow()
    fun consumeSection() { _sectionRequest.value = null }

    // ---- navigation -------------------------------------------------------------------------
    val router = Router(app)
    private val voice = Voice(app)
    private var navigator: Navigator? = null
    private val _nav = MutableStateFlow<NavState?>(null)
    val nav: StateFlow<NavState?> = _nav.asStateFlow()
    private val _routing = MutableStateFlow(false)
    val routing: StateFlow<Boolean> = _routing.asStateFlow()
    private val _navError = MutableStateFlow<String?>(null)
    val navError: StateFlow<String?> = _navError.asStateFlow()
    private var lastReroute = 0L
    /** The road under the car, when the routing graph knows it. */
    private val _road = MutableStateFlow<String?>(null)
    val road: StateFlow<String?> = _road.asStateFlow()
    val voiceOn: StateFlow<Boolean> get() = voice.enabled
    fun toggleVoice() = voice.toggle()

    fun navigateTo(lat: Double, lon: Double, name: String) = viewModelScope.launch {
        val f = fix.value ?: run { _navError.value = "NO FIX"; return@launch }
        if (!router.available()) { _navError.value = "NO ROUTING DATA"; return@launch }
        _routing.value = true; _navError.value = null
        val r = router.route(f.lat, f.lon, lat, lon)
        _routing.value = false
        if (r == null) { _navError.value = "NO ROUTE"; return@launch }
        navigator = Navigator(r, name, lat, lon).also { _nav.value = it.update(f.lat, f.lon) }
        voice.say("Route set. " + fmtSpeech(r.distM) + " to " + name)
    }

    fun endNav() { navigator = null; _nav.value = null; voice.stop() }
    fun clearNavError() { _navError.value = null }

    private fun navFix(f: Fix) {
        val n = navigator ?: return
        val st = n.update(f.lat, f.lon)
        _nav.value = st
        voice.onProgress(st)
        if (st.arrived) { voice.say("You have arrived"); endNav(); return }
        if (st.offRoute && System.currentTimeMillis() - lastReroute > 15_000 && !_routing.value) {
            lastReroute = System.currentTimeMillis()
            viewModelScope.launch {
                _routing.value = true; _nav.value = st.copy(rerouting = true)
                val r = router.route(f.lat, f.lon, st.destLat, st.destLon)
                _routing.value = false
                if (r != null && navigator === n) { n.replace(r); _nav.value = n.update(f.lat, f.lon); voice.say("Rerouting") }
            }
        }
    }

    private fun fmtSpeech(m: Double): String = com.celica.dash.data.Units.spokenDist(m, 10)

    /** Unzips a picked routing graph into files/routing/graph; progress 0..1, null when idle. */
    // ---- the setup helper and its payload -------------------------------------------------------
    private val _helper = MutableStateFlow<com.celica.dash.data.Helper.Info?>(null)
    /** The helper found on the Wi-Fi, with what it has waiting. */
    val helper: StateFlow<com.celica.dash.data.Helper.Info?> = _helper.asStateFlow()
    private val _helperNote = MutableStateFlow("")
    val helperNote: StateFlow<String> = _helperNote.asStateFlow()
    @Volatile private var searching = false

    /** Listens for the helper's beacon, or asks the address given; the PC address follows. */
    fun findHelper(manual: String? = null) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        if (searching) return@launch
        searching = true
        try {
            val host = manual?.trim()?.takeIf { it.isNotBlank() } ?: run {
                _helperNote.value = "Listening for the helper on the Wi-Fi…"
                com.celica.dash.data.Helper.listen(getApplication()) ?: pcHost.value.takeIf { it.isNotBlank() }
            }
            if (host == null) { _helperNote.value = "Nothing heard. Is RUN ME open on the PC, on the same Wi-Fi? Its page shows the address to type."; return@launch }
            _helperNote.value = "Asking $host…"
            val info = com.celica.dash.data.Helper.probe(host)
            if (info == null) { _helperNote.value = "No answer from $host" + (if (host.contains(':')) "." else " on port ${com.celica.dash.data.Helper.PORT} or its fallbacks. Is RUN ME open on that PC?"); return@launch }
            // remembered with the port it answered on, so the next time is one ask
            prefs.setPcHost(info.host)
            _helper.value = info
            _helperNote.value = "Found ${info.name} at $host · ${info.items.size} things waiting"
            // the owner's answers from the PC come first, so the pages after this one open filled in -
            // and the dash wears them from this moment, not after a tap
            info.setup?.let { runCatching { com.celica.dash.data.Helper.applySetup(prefs, it) }; applyLive() }
        } finally { searching = false }
    }
    fun forgetHelper() { _helper.value = null; _helperNote.value = "" }

    /** Per payload kind: null idle, 0..1 under way; the done set and any error lines. */
    private val _fetchProgress = MutableStateFlow<Map<String, Float?>>(emptyMap())
    val fetchProgress: StateFlow<Map<String, Float?>> = _fetchProgress.asStateFlow()
    private val _fetchDone = MutableStateFlow<Set<String>>(emptySet())
    val fetchDone: StateFlow<Set<String>> = _fetchDone.asStateFlow()
    private val _fetchErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val fetchErrors: StateFlow<Map<String, String>> = _fetchErrors.asStateFlow()
    private fun progress(kind: String, f: Float?) { _fetchProgress.value = _fetchProgress.value + (kind to f) }

    /** Everything the helper has, one after another; tiles last since they are the big one. */
    fun fetchAll(items: List<com.celica.dash.data.Helper.Item>) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        for (it in items.sortedBy { if (it.kind == "tiles") 1 else 0 }) fetchItemNow(it)
    }
    fun fetchItem(item: com.celica.dash.data.Helper.Item) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { fetchItemNow(item) }

    private suspend fun fetchItemNow(item: com.celica.dash.data.Helper.Item) {
        val host = _helper.value?.host ?: pcHost.value.ifBlank { return }
        if (_fetchProgress.value[item.kind] != null) return
        progress(item.kind, 0f)
        _fetchErrors.value = _fetchErrors.value - item.kind
        val app = getApplication<Application>()
        runCatching {
            val (inp, total) = com.celica.dash.data.Helper.open(host, item.path)
            val counted = { done: Long -> if (total > 0) progress(item.kind, (done.toFloat() / total).coerceIn(0f, 0.999f)) }
            when (item.kind) {
                "tiles" -> inp.use { copyTiles(it, total) }
                "routing" -> inp.use { unzipGraph(it, total) }
                "search" -> inp.use { i ->
                    val dest = com.celica.dash.search.PlaceSearch.file(app); val tmp = java.io.File(dest.path + ".part")
                    tmp.outputStream().use { i.copyTo(it, 1 shl 20) }; dest.delete(); tmp.renameTo(dest); com.celica.dash.search.PlaceSearch.forget()
                }
                "faces" -> inp.use { unzipInto(it, com.celica.dash.ui.drive.backdropDir(app), setOf(".mp4"), counted) }
                "art" -> inp.use { unzipInto(it, com.celica.dash.media.artDir(app), setOf(".jpg"), counted) }
                "music" -> inp.use { i -> com.celica.dash.data.Helper.saveMusic(app, item.folder ?: "Dash", i) { n -> if (item.count > 0) progress(item.kind, (n.toFloat() / item.count).coerceIn(0f, 0.999f)) }; library.load() }
                "apk" -> { inp.close(); fetchUpdate() }
                "setup" -> { inp.use { i -> com.celica.dash.data.Helper.applySetup(prefs, org.json.JSONObject(i.bufferedReader().readText())) }; applyLive() }
                else -> inp.close()
            }
        }.onSuccess { _fetchDone.value = _fetchDone.value + item.kind }
            .onFailure { _fetchErrors.value = _fetchErrors.value + (item.kind to (it.message ?: it.javaClass.simpleName)) }
        progress(item.kind, null)
    }

    /** Every file of the given kinds out of a zip, flat, into a folder. */
    private fun unzipInto(inp: java.io.InputStream, dir: java.io.File, exts: Set<String>, counted: (Long) -> Unit): Int {
        dir.mkdirs()
        var n = 0; var done = 0L
        java.util.zip.ZipInputStream(inp).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val name = e.name.substringAfterLast('/')
                if (!e.isDirectory && exts.any { name.endsWith(it, true) }) { java.io.File(dir, name).outputStream().use { z.copyTo(it) }; n++ }
                done += e.compressedSize.coerceAtLeast(0); counted(done)
                z.closeEntry(); e = z.nextEntry
            }
        }
        return n
    }

    // ---- setup ----------------------------------------------------------------------------------
    val setupDone: StateFlow<Boolean> = prefs.setupDone.stateIn(viewModelScope, SharingStarted.Eagerly, true)
    /** Bumped when the palette changes under a running screen, so everything redraws in it. */
    private val _restyle = MutableStateFlow(0)
    val restyle: StateFlow<Int> = _restyle.asStateFlow()
    fun restyleNow() { _restyle.value++ }
    /** Everything the first frame would read, read again now: the palette, the car, the units, the region. */
    fun applyLive() {
        val st = prefs.startupNow()
        com.celica.dash.ui.theme.Ink.apply(st.look, st.accent, st.background)
        com.celica.dash.data.CarSpec.apply(st.car)
        com.celica.dash.data.Units.apply(st.units, st.locale)
        com.celica.dash.data.Region.apply(st.region, st.mapHome.first, st.mapHome.second, st.mapHome.third)
        restyleNow()
    }
    fun saveCar(c: com.celica.dash.data.CarSpec.Saved) = viewModelScope.launch { prefs.setCar(c); com.celica.dash.data.CarSpec.apply(c); restyleNow() }
    fun saveColours(look: String?, accent: String?, background: String?) = viewModelScope.launch {
        look?.let { prefs.setLook(it) }
        prefs.setColours(accent, background)
        com.celica.dash.ui.theme.Ink.apply(look ?: prefs.look.first(), accent, background); restyleNow()
    }
    fun saveUnits(system: String, locale: String) = viewModelScope.launch { prefs.setUnits(system); prefs.setLocale(locale); com.celica.dash.data.Units.apply(system, locale); restyleNow() }
    fun saveRegion(name: String, lat: Double, lon: Double, zoom: Double) = viewModelScope.launch { prefs.setRegion(name); prefs.setMapHome(lat, lon, zoom); com.celica.dash.data.Region.apply(name, lat, lon, zoom) }
    /** The end of Setup: remembered, and the dash rebuilt in everything chosen. */
    fun finishSetup() = viewModelScope.launch { prefs.setSetupDone(true); kotlinx.coroutines.delay(50); activity?.recreate() }
    fun runSetupAgain() = viewModelScope.launch { prefs.setSetupDone(false); showSettings(false) }
    fun setRemoteControl(on: Boolean) = viewModelScope.launch { prefs.setRemoteControl(on) }

    private val _graphImport = MutableStateFlow<Float?>(null)
    val graphImport: StateFlow<Float?> = _graphImport.asStateFlow()
    fun importGraph(uri: android.net.Uri) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val total = app.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        runCatching { app.contentResolver.openInputStream(uri)!!.use { unzipGraph(it, total) } }
    }

    private suspend fun unzipGraph(inp: java.io.InputStream, total: Long) {
        val dest = router.dir
        try {
            _graphImport.value = 0f
            router.reset()
            dest.deleteRecursively(); dest.mkdirs()
            var done = 0L
            java.util.zip.ZipInputStream(inp).use { z ->
                var e = z.nextEntry
                val buf = ByteArray(1 shl 20)
                while (e != null) {
                    val name = e.name.substringAfterLast('/')
                    if (!e.isDirectory && name.isNotEmpty()) {
                        java.io.File(dest, name).outputStream().use { out ->
                            var n: Int
                            while (z.read(buf).also { n = it } > 0) { out.write(buf, 0, n) }
                        }
                    }
                    done += e.compressedSize.coerceAtLeast(0)
                    if (total > 0) _graphImport.value = (done.toFloat() / total).coerceIn(0f, 0.999f)
                    z.closeEntry(); e = z.nextEntry
                }
            }
        } catch (e: Exception) {
            dest.deleteRecursively(); throw e
        } finally {
            _graphImport.value = null
        }
    }

    /**
     * Sends the PC what it needs to study this unit: a report of every installed package, and
     * the APKs of anything that looks like the vendor's Bluetooth or car service - so the
     * broadcasts that carry now-playing and the GPS can be found and read.
     */
    fun sendToPc() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        val app = getApplication<Application>()
        val host = pcHost.value.ifBlank { _fetchNote.value = "Send: no PC address set"; return@launch }
        fun post(name: String, body: java.io.InputStream, length: Long) {
            val c = java.net.URL(com.celica.dash.data.Helper.base(host) + "/upload/$name").openConnection() as java.net.HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(length); c.connectTimeout = 5000; c.readTimeout = 60000
            c.outputStream.use { out -> body.copyTo(out, 1 shl 20) }
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        }
        runCatching {
            val pm = app.packageManager
            val all = pm.getInstalledApplications(0)
            val report = StringBuilder()
            val nl = System.lineSeparator()
            diagnostics().forEach { (k, v) -> report.append(k).append(": ").append(v).append(nl) }
            report.append(nl).append("Build: ").append(android.os.Build.FINGERPRINT).append(nl)
            report.append("Bluetooth music app: ").append(btMusicApp.value?.pkg ?: "?").append(nl).append(nl)
            for (a in all.sortedBy { it.packageName }) {
                report.append(a.packageName).append("  ").append(runCatching { pm.getApplicationLabel(a).toString() }.getOrDefault("")).append("  ").append(a.sourceDir).append(nl)
            }
            val bytes = report.toString().toByteArray()
            _fetchNote.value = "Sending report…"
            post("unit-report.txt", bytes.inputStream(), bytes.size.toLong())
            // the vendor's apps: the chosen music app, and whatever names suggest the car side
            val hints = listOf("bt", "bluetooth", "music", "canbus", "mcu", "carservice", "car_service", "syu", "wwc2", "choiceway", "qf", "fyt", "sofia", "radio", "avrcp", "a2dp")
            val chosen = btMusicApp.value?.pkg
            val picked = all.filter { a ->
                val p = a.packageName.lowercase()
                !p.startsWith("com.google.") && !p.startsWith("com.android.") && !p.startsWith("android") &&
                    (p == chosen || hints.any { h -> p.contains(h) })
            }.take(20)
            for ((i, a) in picked.withIndex()) {
                val f = java.io.File(a.sourceDir)
                if (!f.canRead()) continue
                _fetchNote.value = "Sending ${a.packageName} (${i + 1}/${picked.size})…"
                f.inputStream().use { post(a.packageName + ".apk", it, f.length()) }
            }
            _fetchNote.value = "Sent: report + ${picked.size} apps"
        }.onFailure { _fetchNote.value = "Send: ${it.message}" }
    }

    // ---- pins -------------------------------------------------------------------------------
    fun addPlace(name: String, lat: Double, lon: Double) = viewModelScope.launch { placeStore.add(Place(System.currentTimeMillis(), name, lat, lon)) }
    fun removePlace(id: Long) = viewModelScope.launch { placeStore.remove(id) }
    fun renamePlace(id: Long, name: String) = viewModelScope.launch { placeStore.rename(id, name) }

    private val _settingsOpen = MutableStateFlow(false)
    val settingsOpen: StateFlow<Boolean> = _settingsOpen.asStateFlow()
    fun showSettings(open: Boolean) { _settingsOpen.value = open }

    init {
        media.start()
        wireGhost()
        viewModelScope.launch { prefs.ghostDrive.collect { id -> if (id != 0L && _ghostArmed.value != id) armGhost(id) } }
        // the PC helper may drive the unit only once the owner has said so - it is a shell on the LAN
        viewModelScope.launch { prefs.remoteControl.collect { if (it) remote.start(viewModelScope) else remote.stop() } }
        viewModelScope.launch { prefs.phoneMode.collect { phoneLink.mode = com.celica.dash.apps.PhoneLink.Mode.of(it) } }
        phoneGps.start(viewModelScope)
        phoneGpsBle.start(viewModelScope)
        // the star ceiling is vaulted: its box sits in the boot and cannot hear the unit
        // (handoff/carplay.md); `remote.py pref stars on` wakes the client for a try
        viewModelScope.launch {
            phoneGpsSetting.collect { v ->
                phoneGpsBle.enabled = v == "ble"
                phoneGps.enabled = v.isNotBlank() && v != "ble"
                phoneGps.endpoint = if (v == "auto") "" else v
            }
        }
        viewModelScope.launch { weatherRepo.loadCached() }
        viewModelScope.launch {
            combine(obd.vehicle, demo, fix) { o, d, f ->
                val gps = f != null && System.currentTimeMillis() - f.atMs < 10_000
                // a parked car's fix still wanders, and reports the wander as a walking pace; the
                // readout rounds it away but the wheels turned to it. Under 1 mph from GPS is stopped;
                // a real creep forward is above it (owner's call)
                d ?: o?.copy(gpsLive = gps) ?: VehicleState(speedMph = f?.speedMph?.let { if (it < 1f) 0f else it }, gpsLive = gps)
            }.collect { _vehicle.value = it; sessionTracker.onVehicle(it) }
        }
        // the trail: append a point whenever the car has moved a car length or so
        viewModelScope.launch {
            fix.collect { f ->
                if (f == null) return@collect
                sessionTracker.onFix(f)
                ghost.onFix(f)
                val t = _trail.value
                val last = t.lastOrNull()
                if (last == null || haversineM(last[0], last[1], f.lat, f.lon) > 15.0) {
                    _trail.value = (t + doubleArrayOf(f.lat, f.lon)).takeLast(4000)
                }
                recordFix(f)
                navFix(f)
            }
        }
        // the road under the car, once a second, when the graph is there
        viewModelScope.launch {
            var last = 0L
            fix.collect { f ->
                if (f == null || !router.available() || f.atMs - last < 1000) return@collect
                last = f.atMs
                _road.value = router.roadName(f.lat, f.lon)
            }
        }
        // the dongle follows the saved address; clearing it stops the link
        viewModelScope.launch {
            prefs.obdAddress.distinctUntilChanged().collect { a -> if (a == null) obd.stop() else obd.start(a) }
        }
        // read the stored codes on our own as soon as the dongle answers, so a fault shows itself
        viewModelScope.launch { obd.status.collect { if (it == ObdStatus.LIVE) { delay(1500); obd.readDtcs() } } }
        // weather: try on start, then every ten minutes; refresh() itself decides if it is worth it
        viewModelScope.launch {
            while (true) {
                fix.value?.let { weatherRepo.refresh(it.lat, it.lon) }
                delay(60 * 1000)          // refresh() itself waits half an hour between fetches
            }
        }
    }

    /** Called when the activity comes back to the front: permissions may have just been granted. */
    fun onResume() {
        // a grant just made in a dialog: the library can read the unit's music now
        if (library.state.value.needsStorage && library.hasStorage()) library.load()
        media.start(); media.refresh()
        location.start()
        phone.start(); phone.publish()
        refreshApps()
    }

    fun refreshWeatherNow() = viewModelScope.launch {
        fix.value?.let { weatherRepo.refresh(it.lat, it.lon, force = true) }
    }

    fun setObdDevice(address: String?) = viewModelScope.launch { prefs.setObdAddress(address) }
    fun setIssueStatus(id: String, status: String) = viewModelScope.launch { garage.setIssueStatus(id, status) }
    fun setTicked(key: String, on: Boolean) = viewModelScope.launch { garage.setTicked(key, on) }
    fun clearTicked() = viewModelScope.launch { garage.clearTicked() }
    fun addLog(e: com.celica.dash.data.ServiceEntry) = viewModelScope.launch { garage.addLog(e) }
    fun removeLog(id: Long) = viewModelScope.launch { garage.removeLog(id) }
    fun readDtcs() = viewModelScope.launch { obd.readDtcs() }
    fun clearDtcs() = viewModelScope.launch { obd.clearDtcs() }

    override fun onCleared() { voice.shutdown(); obd.stop(); phoneLink.close(); library.stop(); lights.stop() }

    private var demoJob: Job? = null
    val demoRunning get() = demoJob != null
    fun toggleDemo() {
        demoJob?.let { it.cancel(); demoJob = null; demo.value = null; demoFix.value = null; return }
        _trail.value = emptyList()
        demoJob = viewModelScope.launch {
            launch { DemoDrive.flow().collect { demo.value = it } }
            // a real road: the M1 northbound from junction 15A, routed over the imported graph
            // so the car keeps to the carriageway and not the fields; without a graph, a
            // straight line up the same stretch. Back to the start when the road runs out.
            val a = 52.1985 to -0.9770; val b = 52.2620 to -1.0040
            val road = runCatching { if (router.available()) router.route(a.first, a.second, b.first, b.second)?.points else null }.getOrNull()
                ?.map { it[0] to it[1] } ?: (0..200).map { i -> a.first + (b.first - a.first) * i / 200.0 to a.second + (b.second - a.second) * i / 200.0 }
            var i = 0; var lat = road[0].first; var lon = road[0].second; var brg = 0.0
            while (true) {
                val mph = demo.value?.speedMph ?: 0f
                var m = mph * 0.44704 * 0.2
                // walk the road by that many metres, point to point
                while (m > 0 && i + 1 < road.size) {
                    val (nlat, nlon) = road[i + 1]
                    val dLat = (nlat - lat) * 111_320.0; val dLon = (nlon - lon) * 111_320.0 * cos(Math.toRadians(lat))
                    val seg = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
                    if (seg > 1e-3) brg = (Math.toDegrees(kotlin.math.atan2(dLon, dLat)) + 360.0) % 360.0
                    if (m >= seg) { m -= seg; lat = nlat; lon = nlon; i++ }
                    else { lat += dLat / seg * m / 111_320.0; lon += dLon / seg * m / (111_320.0 * cos(Math.toRadians(lat))); m = 0.0 }
                }
                if (i + 1 >= road.size) { i = 0; lat = road[0].first; lon = road[0].second }
                demoFix.value = Fix(lat, lon, mph, brg.toFloat(), System.currentTimeMillis())
                delay(200)
            }
        }
    }
}
