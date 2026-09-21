package com.celica.dash.lights

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** What the lights are doing, as far as the dash knows: the controller keeps no state we can read. */
data class LightsState(
    val status: String = "off the air",
    val connected: Boolean = false,
    val on: Boolean = false,
    val red: Int = 216, val green: Int = 67, val blue: Int = 60,     // the dash's red
    val brightness: Int = 100,                                       // 0..100
    val mode: Int = 0,                                               // 0 = still colour, else 1..n
    val speed: Int = 50,
    val protocol: String = "?",
)

/**
 * The star ceiling, over Bluetooth Low Energy. The controller (`LEDBLE-00-E01F`, address
 * A4:C1:38:60:E0:1F, a Telink chip in a 12 V RGBW fibre-optic source) is a BLE peripheral with
 * one write characteristic and no pairing: the dash connects and writes a few bytes per command,
 * the same kind of link as the phone's GPS, on the same radio, beside the music and the dongle.
 *
 * The bytes are the LED Lamp app's own, read from its code (`tools/uploads/ledlamp/src`,
 * `NetConnectBle.java`): service FFE0, characteristic FFE1, frames `7E FF cmd … EF` -
 *   on `7E FF 04 01 00 FF FF 00 EF`   off `7E FF 04 00 00 FF FF 00 EF`
 *   colour `7E FF 05 03 R G B FF EF`   brightness `7E FF 01 n 01 FF FF FF EF` (0-100)
 *   pattern `7E FF 03 m 03 FF FF FF EF` (m = 0x86 + 1..23)   speed `7E FF 02 n 00 FF FF FF EF`
 *   white `7E FF 05 01 w FF FF FF EF`
 * with colour writes no closer than 50 ms apart. The two cousins that share the framing
 * (`7E 00 …` on FFF3; Triones `CC 23 33` on FFD9) are kept for a controller that turns out to be
 * one of those; the write characteristic found on the device picks the dialect.
 *
 * Finding it is the odd part. On this unit's stack the modern LE scanner never hears the
 * controller's advert, while the legacy `startLeScan` - which the app uses - does, and the
 * unit's own Settings page does. So the legacy scan is what looks for it by name, and once the
 * address is known it is kept and dialled with `autoConnect`: the stack itself then waits for
 * the advert, however long, and reconnects on its own after every drop - which is what a light
 * that comes and goes with the ignition wants.
 *
 * The controller takes one client at a time: while the dash holds it the phone app cannot,
 * and the other way round. The RF remote is separate and always works.
 *
 * VAULTED (21 Sep): the box lives in the boot, behind the headliner, and from there it hears
 * the unit once in four hundred tries (the HCI capture in handoff/carplay.md); the owner keeps
 * the remote. The client is not started and the ✦ is not shown; `remote.py pref stars on`
 * wakes it for a try if the box ever moves.
 */
class StarLights(private val ctx: Context) {
    private val _state = MutableStateFlow(LightsState())
    val state: StateFlow<LightsState> = _state.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var write: BluetoothGattCharacteristic? = null
    private var wanted = false
    private val prefs = ctx.getSharedPreferences("stars", Context.MODE_PRIVATE)
    private var table = "not connected yet"
    private var lastWrite = "none"
    private val seen = linkedSetOf<String>()          // every advertiser heard, for the diagnostics
    @Volatile private var pinned: String? = prefs.getString("addr", null)   // the controller's address, once known

    private enum class Dialect { LAMP, BLEDOM, TRIONES }
    private var dialect: Dialect? = null

    /** Look for the controller and keep hold of it; called once the dash is up. */
    fun start() {
        if (wanted) return
        wanted = true
        seek()
    }

    fun stop() {
        wanted = false
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; write = null
        _state.value = _state.value.copy(connected = false, status = "off the air")
    }

    private val seekAgain = Runnable { seek() }

    private fun seek() {
        if (!wanted || gatt != null) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run { _state.value = _state.value.copy(status = "no bluetooth"); return }
        if (!adapter.isEnabled) { _state.value = _state.value.copy(status = "bluetooth off"); main.postDelayed(seekAgain, 15_000); return }
        // a known address is dialled and left ringing: the stack does its own listening for the
        // advert, which is not the app scanner's, and answers whenever the lights are powered
        // dialled directly first - the link layer answers the controller's next advert, which
        // needs no scan response - and only after a few misses left ringing with autoConnect
        pinned?.let { a -> runCatching { adapter.getRemoteDevice(a) }.getOrNull()?.let { connect(it, wait = directTries >= 3); directTries++; return } }
        _state.value = _state.value.copy(status = "looking for the lights")
        @Suppress("DEPRECATION")
        val cb = BluetoothAdapter.LeScanCallback { device, rssi, record ->
            val name = device.name ?: nameIn(record)
            val addr = device.address
            if (seen.size < 40 && seen.none { it.contains(addr) }) {
                val raw = record?.take(31)?.joinToString("") { "%02x".format(it) } ?: ""
                seen += (name ?: "?") + "@" + addr + "/" + rssi + (if (name == null) " [$raw]" else "")
            }
            if (name?.startsWith(NAME, ignoreCase = true) != true) return@LeScanCallback
            main.post {
                stopScan()
                pinned = addr
                prefs.edit().putString("addr", addr).apply()
                connect(device, wait = false)
            }
        }
        legacy = cb
        @Suppress("DEPRECATION")
        val ok = runCatching { adapter.startLeScan(cb) }.getOrDefault(false)
        if (!ok) { _state.value = _state.value.copy(status = "scan refused"); legacy = null; main.postDelayed(seekAgain, 30_000); return }
        main.postDelayed({ if (legacy === cb) { stopScan(); _state.value = _state.value.copy(status = "lights not seen"); main.postDelayed(seekAgain, 45_000) } }, 25_000)
    }

    /** The name out of a raw advert, for a stack that has not cached one: AD types 0x08 and 0x09. */
    private fun nameIn(record: ByteArray?): String? {
        if (record == null) return null
        var i = 0
        while (i + 1 < record.size) {
            val len = record[i].toInt() and 0xFF
            if (len == 0) break
            val type = record[i + 1].toInt() and 0xFF
            if ((type == 0x08 || type == 0x09) && i + 1 + len <= record.size) return String(record, i + 2, len - 1, Charsets.UTF_8)
            i += len + 1
        }
        return null
    }

    @Suppress("DEPRECATION")
    private var legacy: BluetoothAdapter.LeScanCallback? = null

    @Suppress("DEPRECATION")
    private fun stopScan() {
        val cb = legacy ?: return
        legacy = null
        runCatching { BluetoothAdapter.getDefaultAdapter()?.stopLeScan(cb) }
    }

    private var directTries = 0

    @Synchronized
    private fun connect(device: BluetoothDevice, wait: Boolean) {
        gatt?.let { old -> runCatching { old.disconnect(); old.close() } }
        gatt = null; write = null
        _state.value = _state.value.copy(status = if (wait) "waiting for ${device.name ?: device.address}" else "connecting to ${device.name ?: device.address}")
        val g = device.connectGatt(ctx, wait, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                val mine = synchronized(this@StarLights) { gatt === g }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!mine) { runCatching { g.disconnect(); g.close() }; return }
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    if (!mine) return
                    gatt = null; write = null
                    _state.value = _state.value.copy(connected = false, status = if (status == 0) "disconnected" else "disconnected ($status)")
                    if (wanted) main.postDelayed(seekAgain, if (wait) 2_000 else 5_000)
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                // the whole table, for the diagnostics page
                table = g.services.joinToString(" | ") { s ->
                    short(s.uuid) + ":" + s.characteristics.joinToString(",") { c -> short(c.uuid) + props(c.properties) }
                }
                val pick = WRITE_CANDIDATES.firstNotNullOfOrNull { (uuid, d) -> g.services.firstNotNullOfOrNull { s -> s.getCharacteristic(uuid) }?.let { it to d } }
                if (pick == null) {
                    _state.value = _state.value.copy(status = "no known write characteristic: $table")
                    runCatching { g.disconnect() }
                    return
                }
                write = pick.first
                dialect = pick.second
                pick.first.writeType = if (pick.first.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
                directTries = 0
                _state.value = _state.value.copy(connected = true, status = "linked", protocol = pick.second.name.lowercase())
            }
        }, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (g == null) { _state.value = _state.value.copy(status = "the stack would not open a link"); main.postDelayed(seekAgain, 10_000) }
    }

    // ---- commands ---------------------------------------------------------------------------

    fun on() { send(when (dialect) { Dialect.LAMP -> bytes(0x7E, 0xFF, 0x04, 0x01, 0x00, 0xFF, 0xFF, 0x00, 0xEF); Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x04, 0xF0, 0x00, 0x01, 0xFF, 0x00, 0xEF); Dialect.TRIONES -> bytes(0xCC, 0x23, 0x33); null -> return }); _state.value = _state.value.copy(on = true) }
    fun off() { send(when (dialect) { Dialect.LAMP -> bytes(0x7E, 0xFF, 0x04, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0xEF); Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x04, 0x00, 0x00, 0x00, 0xFF, 0x00, 0xEF); Dialect.TRIONES -> bytes(0xCC, 0x24, 0x33); null -> return }); _state.value = _state.value.copy(on = false) }

    private var lastColorAt = 0L
    fun color(r: Int, g: Int, b: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastColorAt < 50) return
        lastColorAt = now
        send(when (dialect) {
            Dialect.LAMP -> bytes(0x7E, 0xFF, 0x05, 0x03, r, g, b, 0xFF, 0xEF)
            Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x05, 0x03, r, g, b, 0x00, 0xEF)
            Dialect.TRIONES -> bytes(0x56, r, g, b, 0x00, 0xF0, 0xAA)
            null -> return
        })
        _state.value = _state.value.copy(red = r, green = g, blue = b, mode = 0, on = true)
    }

    /** The white channel on its own, 0..255 - the RGBW's fourth bead. */
    fun white(w: Int) {
        send(when (dialect) {
            Dialect.LAMP -> bytes(0x7E, 0xFF, 0x05, 0x01, w.coerceIn(0, 255), 0xFF, 0xFF, 0xFF, 0xEF)
            Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x05, 0x01, w.coerceIn(0, 255), 0x00, 0x00, 0x00, 0xEF)
            Dialect.TRIONES -> bytes(0x56, 0x00, 0x00, 0x00, w.coerceIn(0, 255), 0x0F, 0xAA)
            null -> return
        })
        _state.value = _state.value.copy(mode = 0, on = true)
    }

    /** 0..100. */
    fun brightness(pct: Int) {
        val p = pct.coerceIn(0, 100)
        send(when (dialect) {
            Dialect.LAMP -> bytes(0x7E, 0xFF, 0x01, p, 0x01, 0xFF, 0xFF, 0xFF, 0xEF)
            Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x01, p, 0x00, 0x00, 0x00, 0x00, 0xEF)
            Dialect.TRIONES -> { val s = _state.value; val k = p / 100f; bytes(0x56, (s.red * k).toInt(), (s.green * k).toInt(), (s.blue * k).toInt(), 0x00, 0xF0, 0xAA) }
            null -> return
        })
        _state.value = _state.value.copy(brightness = p)
    }

    /** A built-in pattern, 1..n; the numbering is the controller's, learnt by trying. */
    fun mode(n: Int) {
        send(when (dialect) {
            Dialect.LAMP -> bytes(0x7E, 0xFF, 0x03, 0x86 + n, 0x03, 0xFF, 0xFF, 0xFF, 0xEF)
            Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x03, 0x86 + n, 0x03, 0x00, 0x00, 0x00, 0xEF)
            Dialect.TRIONES -> bytes(0xBB, 0x24 + n, _state.value.speed.coerceIn(1, 31), 0x44)
            null -> return
        })
        _state.value = _state.value.copy(mode = n, on = true)
    }

    fun speed(pct: Int) {
        val p = pct.coerceIn(0, 100)
        send(when (dialect) {
            Dialect.LAMP -> bytes(0x7E, 0xFF, 0x02, p, 0x00, 0xFF, 0xFF, 0xFF, 0xEF)
            Dialect.BLEDOM -> bytes(0x7E, 0x00, 0x02, p, 0x00, 0x00, 0x00, 0x00, 0xEF)
            Dialect.TRIONES -> { _state.value = _state.value.copy(speed = p); if (_state.value.mode > 0) mode(_state.value.mode); return }
            null -> return
        })
        _state.value = _state.value.copy(speed = p)
    }

    /** Raw bytes, from the diagnostics page, for learning a controller that speaks otherwise. */
    /**
     * The other way of looking: the classic discovery the unit's own Settings page uses - an
     * inquiry and the stack's own LE sweep - which on this stack hears things the app-level LE
     * scanner does not. It costs the radio a dozen seconds of attention (music can catch), so it
     * is run only when the scan has come up empty. Whatever answers to the name is taken by
     * address from then on.
     */
    fun discover(): String {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return "no bluetooth"
        if (discovering) return "already looking"
        discovering = true
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                when (i.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                        val name = i.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: d.name
                        val type = when (d.type) { BluetoothDevice.DEVICE_TYPE_LE -> "le"; BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"; BluetoothDevice.DEVICE_TYPE_DUAL -> "dual"; else -> "?" }
                        found += "${name ?: "?"}@${d.address}/$type"
                        if (name?.startsWith(NAME, ignoreCase = true) == true && pinned == null) {
                            pinned = d.address
                            runCatching { adapter.cancelDiscovery() }
                            main.postDelayed({ connect(d, wait = false) }, 1500)     // once the inquiry has let go of the radio
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { discovering = false; runCatching { ctx.unregisterReceiver(this) } }
                }
            }
        }
        val f = android.content.IntentFilter().apply { addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        androidx.core.content.ContextCompat.registerReceiver(ctx, rx, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
        stopScan()
        found.clear()
        return if (adapter.startDiscovery()) "discovering" else { discovering = false; "discovery refused" }
    }
    @Volatile private var discovering = false
    private val found = linkedSetOf<String>()

    /** "connect AA:BB:CC:DD:EE:FF" pins an address, "discover" runs the other kind of look; anything else is bytes for the lights. */
    fun raw(hex: String): String {
        if (hex.trim().equals("discover", ignoreCase = true)) return discover()
        if (hex.trim().startsWith("connect ", ignoreCase = true)) {
            pinned = hex.trim().substringAfter(' ').trim().uppercase()
            prefs.edit().putString("addr", pinned).apply()
            stopScan(); runCatching { gatt?.disconnect(); gatt?.close() }; gatt = null; write = null
            main.removeCallbacks(seekAgain); main.post(seekAgain)
            return "looking for $pinned"
        }
        val b = runCatching { hex.trim().split(Regex("[\\s,]+")).map { it.toInt(16) } }.getOrNull() ?: return "bad hex"
        send(bytes(*b.toIntArray()))
        return "sent $lastWrite"
    }

    private fun send(data: ByteArray) {
        val g = gatt; val c = write
        lastWrite = data.joinToString(" ") { "%02X".format(it) }
        if (g == null || c == null) { _state.value = _state.value.copy(status = "not linked"); return }
        c.value = data
        if (!g.writeCharacteristic(c)) _state.value = _state.value.copy(status = "write refused")
    }

    fun describe(): String = _state.value.let { "${it.status} · ${it.protocol} · address ${pinned ?: "unknown"} · last write $lastWrite · table $table · discovery found: ${found.joinToString(", ").ifBlank { "-" }} · scan heard: ${seen.joinToString(", ").ifBlank { "nothing" }}" }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { (v[it] and 0xFF).toByte() }
    private fun short(u: UUID) = "%04X".format((u.mostSignificantBits shr 32) and 0xFFFF)
    private fun props(p: Int) = buildString {
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) append('w')
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) append('n')
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) append('!')
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) append('r')
    }

    companion object {
        const val NAME = "LEDBLE"
        private fun ble(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")
        private val WRITE_CANDIDATES = listOf(
            ble("ffe1") to Dialect.LAMP,
            ble("fff3") to Dialect.BLEDOM,
            ble("ffd9") to Dialect.TRIONES,
            ble("ffe9") to Dialect.TRIONES,
        )
    }
}
