package com.celica.dash.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store: DataStore<Preferences> by preferencesDataStore("dash")

/** The handful of things the user sets once. */
class Prefs(private val ctx: Context) {
    private val kObd = stringPreferencesKey("obd_address")
    private val kLift = intPreferencesKey("lift_rpm")
    private val kCarPlay = stringPreferencesKey("carplay_package")
    private val kPc = stringPreferencesKey("pc_host")
    private val kBtMusic = stringPreferencesKey("btmusic_package")
    private val kLook = stringPreferencesKey("look")
    private val kZoom = stringPreferencesKey("zoom")
    private val kCrt = stringPreferencesKey("crt")                     // the tube over everything
    private val kAnalogDial = stringPreferencesKey("analog_dial")      // retro or modern rev counter
    private val kPhoneGps = stringPreferencesKey("phone_gps")          // "" off, "auto" gateway, or host:port
    private val kMapIcons = stringPreferencesKey("map_icons")          // the categories the map draws
    private val kMapStreets = stringPreferencesKey("map_streets")      // street names on the map
    private val kMapPlaces = stringPreferencesKey("map_places")        // town and village names
    private val kMapBuildings = stringPreferencesKey("map_buildings")
    private val kDriveFace = stringPreferencesKey("drive_face")
    private val kBackdrop = stringPreferencesKey("backdrop")
    private val kGhost = stringPreferencesKey("ghost_drive")
    // ---- the owner's choices from Setup ----
    private val kSetupDone = stringPreferencesKey("setup_done")
    private val kAccent = stringPreferencesKey("accent")             // hex, e.g. D8433C
    private val kBackground = stringPreferencesKey("background")     // hex
    private val kUnits = stringPreferencesKey("units")               // uk | us | metric
    private val kLocale = stringPreferencesKey("locale")             // a language tag, en-GB
    private val kMapHome = stringPreferencesKey("map_home")          // "lat,lon,zoom" the map opens on with no fix
    private val kRegion = stringPreferencesKey("region")             // the name of the region the tiles cover
    private val kPhoneMode = stringPreferencesKey("phone_mode")      // carplay | android_auto | none
    private val kRemote = stringPreferencesKey("remote_control")     // the PC helper may drive the unit
    private val kCarPreset = stringPreferencesKey("car_preset")
    private val kCarName = stringPreferencesKey("car_name")
    private val kCarAlt = stringPreferencesKey("car_alt")
    private val kCarCode = stringPreferencesKey("car_code")
    private val kCarSub = stringPreferencesKey("car_sub")
    private val kCarNote = stringPreferencesKey("car_note")
    private val kCarRedline = stringPreferencesKey("car_redline")
    private val kCarGears = stringPreferencesKey("car_gears")        // ratios, comma-separated; blank for no gear readout
    private val kCarFinal = stringPreferencesKey("car_final")
    private val kCarCirc = stringPreferencesKey("car_circ")          // metres a turn of the wheel covers
    private val kCarLen = stringPreferencesKey("car_len")            // metres, nose to tail
    private val kCarNoLift = stringPreferencesKey("car_nolift")      // "1": no hot zone on the tach at all
    private val kCarModel = stringPreferencesKey("car_model")        // stock | wing

    val obdAddress: Flow<String?> = ctx.store.data.map { it[kObd] }
    /** The car's lift (or shift) point; [CarSpec] carries it at runtime, this is where it is kept. */
    val liftRpm: Flow<Int> = ctx.store.data.map { it[kLift] ?: CarSpec.liftRpm ?: 6000 }
    /** The package the CarPlay button opens; null means guess it from the installed apps. */
    val carPlayPackage: Flow<String?> = ctx.store.data.map { it[kCarPlay] }

    suspend fun setObdAddress(a: String?) = ctx.store.edit { if (a == null) it.remove(kObd) else it[kObd] = a }
    suspend fun setLiftRpm(r: Int) = ctx.store.edit { it[kLift] = r }
    /** The PC on the Wi-Fi running the setup helper (RUN ME), as host or host:port. */
    /** On the emulator the PC is always 10.0.2.2, so the remote link works without typing. */
    val pcHost: Flow<String> = ctx.store.data.map { it[kPc] ?: if (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu")) "10.0.2.2" else "" }
    suspend fun setPcHost(h: String) = ctx.store.edit { it[kPc] = h }
    val look: Flow<String> = ctx.store.data.map { it[kLook] ?: "ink" }
    val zoom: Flow<Float> = ctx.store.data.map { it[kZoom]?.toFloatOrNull() ?: 1f }
    suspend fun setLook(l: String) = ctx.store.edit { it[kLook] = l }
    suspend fun setZoom(z: Float) = ctx.store.edit { it[kZoom] = z.toString() }
    val crt: Flow<Boolean> = ctx.store.data.map { it[kCrt] == "1" }
    suspend fun setCrt(on: Boolean) = ctx.store.edit { it[kCrt] = if (on) "1" else "0" }

    /** Which rev counter the Analogue face shows: "retro" for the turned dial, "modern" for Sweep's. */
    val analogDial: Flow<String> = ctx.store.data.map { it[kAnalogDial] ?: "retro" }
    suspend fun setAnalogDial(k: String) = ctx.store.edit { it[kAnalogDial] = k }
    /** Everything the first frame needs: the look, the zoom, the palette and the car. */
    class Startup(
        val look: String, val zoom: Float, val accent: String?, val background: String?, val car: CarSpec.Saved,
        val units: String, val locale: String, val setupDone: Boolean, val region: String, val mapHome: Triple<Double, Double, Double>,
    )

    /** Read once, blocking, before the first frame. */
    fun startupNow(): Startup = kotlinx.coroutines.runBlocking {
        val p = ctx.store.data.first()
        Startup(
            look = p[kLook] ?: "ink", zoom = p[kZoom]?.toFloatOrNull() ?: 1f,
            accent = p[kAccent], background = p[kBackground],
            car = carOf(p), units = p[kUnits] ?: "uk", locale = p[kLocale] ?: "en-GB",
            setupDone = p[kSetupDone] == "1", region = p[kRegion] ?: "", mapHome = parseHome(p[kMapHome]),
        )
    }
    private fun carOf(p: Preferences) = CarSpec.Saved(
        preset = p[kCarPreset] ?: CarSpec.PRESET_2ZZ, name = p[kCarName] ?: "", alt = p[kCarAlt], code = p[kCarCode] ?: "",
        sub = p[kCarSub] ?: "", note = p[kCarNote] ?: "", liftRpm = p[kLift], noLift = p[kCarNoLift] == "1", redline = p[kCarRedline]?.toIntOrNull(),
        gears = p[kCarGears], finalDrive = p[kCarFinal]?.toDoubleOrNull(), circumferenceM = p[kCarCirc]?.toDoubleOrNull(), lengthM = p[kCarLen]?.toFloatOrNull(),
        model = p[kCarModel] ?: "",
    )
    val car: Flow<CarSpec.Saved> = ctx.store.data.map { carOf(it) }
    suspend fun setCar(c: CarSpec.Saved) = ctx.store.edit {
        it[kCarPreset] = c.preset; it[kCarName] = c.name; it[kCarCode] = c.code; it[kCarSub] = c.sub; it[kCarNote] = c.note
        if (c.alt == null) it.remove(kCarAlt) else it[kCarAlt] = c.alt
        if (c.liftRpm == null) it.remove(kLift) else it[kLift] = c.liftRpm
        if (c.redline == null) it.remove(kCarRedline) else it[kCarRedline] = c.redline.toString()
        if (c.gears == null) it.remove(kCarGears) else it[kCarGears] = c.gears
        if (c.finalDrive == null) it.remove(kCarFinal) else it[kCarFinal] = c.finalDrive.toString()
        if (c.circumferenceM == null) it.remove(kCarCirc) else it[kCarCirc] = c.circumferenceM.toString()
        if (c.lengthM == null) it.remove(kCarLen) else it[kCarLen] = c.lengthM.toString()
        it[kCarNoLift] = if (c.noLift) "1" else "0"
        it[kCarModel] = c.model
    }

    val setupDone: Flow<Boolean> = ctx.store.data.map { it[kSetupDone] == "1" }
    suspend fun setSetupDone(done: Boolean) = ctx.store.edit { it[kSetupDone] = if (done) "1" else "0" }
    val accent: Flow<String?> = ctx.store.data.map { it[kAccent] }
    val background: Flow<String?> = ctx.store.data.map { it[kBackground] }
    /** Hex colours, or null for the look's own. */
    suspend fun setColours(accent: String?, background: String?) = ctx.store.edit {
        if (accent == null) it.remove(kAccent) else it[kAccent] = accent
        if (background == null) it.remove(kBackground) else it[kBackground] = background
    }
    val units: Flow<String> = ctx.store.data.map { it[kUnits] ?: "uk" }
    suspend fun setUnits(u: String) = ctx.store.edit { it[kUnits] = u }
    val locale: Flow<String> = ctx.store.data.map { it[kLocale] ?: "en-GB" }
    suspend fun setLocale(tag: String) = ctx.store.edit { it[kLocale] = tag }
    /** Where the map opens before there is a fix: lat, lon, zoom. */
    val mapHome: Flow<Triple<Double, Double, Double>> = ctx.store.data.map { parseHome(it[kMapHome]) }
    suspend fun setMapHome(lat: Double, lon: Double, zoom: Double) = ctx.store.edit { it[kMapHome] = "$lat,$lon,$zoom" }
    val region: Flow<String> = ctx.store.data.map { it[kRegion] ?: "" }
    suspend fun setRegion(name: String) = ctx.store.edit { it[kRegion] = name }
    private fun parseHome(s: String?): Triple<Double, Double, Double> {
        val p = s?.split(",")?.mapNotNull { it.trim().toDoubleOrNull() }
        return if (p != null && p.size == 3) Triple(p[0], p[1], p[2]) else Triple(54.0, -2.5, 5.5)
    }
    /** Which kind of phone the projection button is for: carplay, android_auto, or none. */
    val phoneMode: Flow<String> = ctx.store.data.map { it[kPhoneMode] ?: "carplay" }
    suspend fun setPhoneMode(m: String) = ctx.store.edit { it[kPhoneMode] = m }
    /** Off unless the owner turns it on: the PC helper can then run commands on the unit. */
    val remoteControl: Flow<Boolean> = ctx.store.data.map { it[kRemote] == "1" }
    suspend fun setRemoteControl(on: Boolean) = ctx.store.edit { it[kRemote] = if (on) "1" else "0" }
    /** Blank is the unit's own GPS; "ble" or "auto" (or host:port) take the phone's instead. */
    val phoneGps: Flow<String> = ctx.store.data.map { it[kPhoneGps] ?: "" }
    suspend fun setPhoneGps(v: String) = ctx.store.edit { it[kPhoneGps] = v }
    val btMusicPackage: Flow<String?> = ctx.store.data.map { it[kBtMusic] }
    suspend fun setBtMusicPackage(p: String?) = ctx.store.edit { if (p == null) it.remove(kBtMusic) else it[kBtMusic] = p }
    /** What the map draws of its own accord: the icon categories, and which words it shows. */
    val mapIcons: Flow<Set<String>> = ctx.store.data.map { p -> (p[kMapIcons] ?: "FUEL").split(",").filter { it.isNotBlank() }.toSet() }
    suspend fun setMapIcons(c: Set<String>) = ctx.store.edit { it[kMapIcons] = c.joinToString(",") }
    val mapStreets: Flow<Boolean> = ctx.store.data.map { it[kMapStreets] != "0" }
    val mapPlaces: Flow<Boolean> = ctx.store.data.map { it[kMapPlaces] != "0" }
    val mapBuildings: Flow<Boolean> = ctx.store.data.map { it[kMapBuildings] != "0" }
    suspend fun setMapStreets(on: Boolean) = ctx.store.edit { it[kMapStreets] = if (on) "1" else "0" }
    suspend fun setMapPlaces(on: Boolean) = ctx.store.edit { it[kMapPlaces] = if (on) "1" else "0" }
    suspend fun setMapBuildings(on: Boolean) = ctx.store.edit { it[kMapBuildings] = if (on) "1" else "0" }

    /** Which face the Drive screen wears. */
    val driveFace: Flow<String> = ctx.store.data.map { it[kDriveFace] ?: "CLASSIC" }
    suspend fun setDriveFace(f: String) = ctx.store.edit { it[kDriveFace] = f }
    /** The recorded drive armed as the ghost, or 0 for none. */
    val ghostDrive: Flow<Long> = ctx.store.data.map { it[kGhost]?.toLongOrNull() ?: 0L }
    suspend fun setGhostDrive(id: Long) = ctx.store.edit { it[kGhost] = id.toString() }

    /** The film the Theatre face plays; blank means whichever one is there. */
    val backdrop: Flow<String> = ctx.store.data.map { it[kBackdrop] ?: "" }
    suspend fun setBackdrop(f: String) = ctx.store.edit { it[kBackdrop] = f }

    suspend fun setCarPlayPackage(p: String?) = ctx.store.edit { if (p == null) it.remove(kCarPlay) else it[kCarPlay] = p }
}
