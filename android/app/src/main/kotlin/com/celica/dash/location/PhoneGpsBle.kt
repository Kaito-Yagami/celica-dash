package com.celica.dash.location

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The phone's GPS over Bluetooth instead of the hotspot. GPS2IP in Bluetooth mode is a plain
 * BLE peripheral carrying the Bluetooth SIG's Location and Navigation service, so the unit is
 * an ordinary BLE central: subscribe to Location and Speed, and unpack the position, speed and
 * heading out of each packet.
 *
 * Finding it is the awkward part. iOS only puts the service in the advertisement while GPS2IP is
 * in front; backgrounded, it advertises nothing an Android scanner can recognise, though it stays
 * connectable. So there are three ways in, in order: the phone it met last time, called straight
 * by address; a listen for the service; and failing both, asking each paired device in turn
 * whether it carries it.
 *
 * This leaves the unit's Wi-Fi free for the house network, and the phone need not run a hotspot.
 *
 * It runs on Android's own radio, which is not the one the music uses: that is the vendor's
 * external module, a separate device as far as the phone is concerned. Pairing the phone here for
 * the GPS is fine so long as this side never offers it music or calls - PhoneRepository sees to
 * that - and the low-energy link below is its own thing, unaffected by either.
 */
@SuppressLint("MissingPermission")
class PhoneGpsBle(private val ctx: Context) {
    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix.asStateFlow()
    private val _status = MutableStateFlow("off")
    val status: StateFlow<String> = _status.asStateFlow()
    init { kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) { _status.collect { if (it != "off" && !it.startsWith("connecting")) com.celica.dash.data.LinkLog.note("phone gps", it) } } }
    @Volatile var enabled = false

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var connected = false
    @Volatile private var connecting = false
    private var scanning: ScanCallback? = null
    @Volatile private var lookingSince = 0L

    // The phone, once it has answered once. iOS only puts the navigation service in its
    // advertisement while the app is in front; the moment GPS2IP is tucked away the advertisement
    // says nothing an Android scanner can recognise, and no amount of listening will find it. But
    // the peripheral is still there and still connectable - so having met it once, call it by
    // address and never mind what it is advertising.
    private val store = ctx.getSharedPreferences("phone_gps_ble", Context.MODE_PRIVATE)
    private var known: String?
        get() = store.getString("addr", null)
        set(v) { store.edit().putString("addr", v).apply() }
    @Volatile private var attempt = 0
    @Volatile private var bondedAt = 0

    /** Everything the unit is paired with that could be carrying the service. */
    private fun bonded(a: BluetoothAdapter): List<BluetoothDevice> =
        runCatching {
            a.bondedDevices.orEmpty().filter {
                it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }
        }.getOrDefault(emptyList())

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (true) {
                if (!enabled) {
                    if (connected || connecting) stop()
                    _status.value = "off"; _fix.value = null
                    delay(3000); continue
                }
                // nothing advertised itself in time. Rather than give up, work through the things
                // already paired with the unit and ask each one whether it has the service - which
                // is how a backgrounded GPS2IP gets found at all
                if (connecting && !connected && System.currentTimeMillis() - lookingSince > 20_000) {
                    stopScan()
                    val a = BluetoothAdapter.getDefaultAdapter()
                    val next = a?.let { bonded(it).getOrNull(bondedAt) }
                    if (next != null) {
                        bondedAt++
                        lookingSince = System.currentTimeMillis()
                        _status.value = "asking ${next.name ?: next.address}"
                        connect(next)
                    } else {
                        bondedAt = 0; connecting = false
                        _status.value = "no phone answering over Bluetooth"
                    }
                }
                if (!connected && !connecting) {
                    val a = BluetoothAdapter.getDefaultAdapter()
                    when {
                        a == null -> _status.value = "no Bluetooth"
                        !a.isEnabled -> _status.value = "Bluetooth off"
                        else -> {
                            connecting = true
                            lookingSince = System.currentTimeMillis()
                            bondedAt = 0
                            // the phone we know, twice out of every three goes; the third is a
                            // listen, so a phone that has changed address is still found
                            val direct = known?.let { addr -> runCatching { a.getRemoteDevice(addr) }.getOrNull() }
                            if (direct != null && attempt % 3 != 2) {
                                _status.value = "calling ${direct.name ?: direct.address}"
                                connect(direct)
                            } else {
                                scan(a)
                            }
                            attempt++
                        }
                    }
                }
                delay(4000)
            }
        }
    }

    /** Listens for anything carrying the navigation service, then takes the first one that answers. */
    private fun scan(adapter: BluetoothAdapter) {
        val scanner = adapter.bluetoothLeScanner ?: run { _status.value = "no scanner"; connecting = false; return }
        _status.value = "looking for the phone"
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                val named = result.device?.name?.contains("gps", true) == true
                val carries = result.scanRecord?.serviceUuids?.any { it.uuid == LNS } == true
                if (!named && !carries) return
                stopScan()
                connect(result.device)
            }
            override fun onScanFailed(errorCode: Int) { _status.value = "scan failed ($errorCode)"; stopScan(); connecting = false }
        }
        scanning = cb
        // the service in the advertisement is the sure sign; the name is the fallback, since a
        // peripheral is free to keep its services to itself until asked. Balanced, not flat out:
        // a scan at full duty has the radio to itself, and the dongle's serial link runs on the
        // same one. The phone advertises often enough that a quarter of the time still hears it
        // within a couple of seconds.
        runCatching {
            scanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(LNS)).build(), ScanFilter.Builder().build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(), cb,
            )
        }.onFailure { _status.value = "scan refused: ${it.message}"; connecting = false }
    }

    private fun stopScan() {
        val cb = scanning ?: return
        scanning = null
        runCatching { BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner?.stopScan(cb) }
    }

    @Synchronized
    private fun connect(device: BluetoothDevice) {
        // One client at a time. A call to an address that never answers takes the stack half a
        // minute to give up on, and this loop moves on after twenty seconds; the abandoned client
        // was left open, and after a handful of those the stack hands out no more.
        gatt?.let { old -> runCatching { old.disconnect(); old.close() } }
        gatt = null; connected = false
        _status.value = "connecting to ${device.name ?: device.address}"
        // TRANSPORT_LE, and not left to Android to choose. The phone is also paired the ordinary
        // way, so it is a bonded BR/EDR device as far as the stack is concerned; left to itself
        // the stack answers a plain connectGatt over BR/EDR, which has no navigation service on
        // it and never finishes connecting.
        val g = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                // under the same lock as connect(), so a quick answer cannot arrive before the
                // client it answers is on record and be mistaken for a superseded one
                val mine = synchronized(this@PhoneGpsBle) { gatt === g }
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (!mine) { runCatching { g.disconnect(); g.close() }; return }   // a superseded attempt
                    connected = true
                    if (!g.requestMtu(120)) g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    runCatching { g.close() }
                    if (!mine) return                                         // an old client, already replaced
                    gatt = null; connected = false; connecting = false
                    _status.value = if (status == 0) "disconnected" else "disconnected ($status)"
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val ch = g.getService(LNS)?.getCharacteristic(LOCATION_AND_SPEED)
                if (ch == null) {
                    _status.value = "no navigation service on that device"
                    if (known == g.device?.address) known = null      // it has stopped carrying it
                    runCatching { g.disconnect() }
                    return
                }
                known = g.device?.address                             // this is the one, from now on
                // a position a second needs no more than that; the default interval has the radio
                // servicing this link every thirty milliseconds, and on this unit the same radio
                // is carrying the phone's music and the dongle's serial port
                runCatching { g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER) }
                g.setCharacteristicNotification(ch, true)
                ch.getDescriptor(CCCD)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
                _status.value = "listening over Bluetooth"
            }

            @Deprecated("the byte array is handed over on older Android")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                if (ch.uuid == LOCATION_AND_SPEED) parse(ch.value ?: return)
            }
        }, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        if (g == null) { connecting = false; _status.value = "the stack would not open a link" }
    }

    @Synchronized
    private fun stop() {
        stopScan()
        runCatching { gatt?.disconnect(); gatt?.close() }
        gatt = null; connected = false; connecting = false
    }

    /** Where the phone is expected, for the diagnostics: the address it answered from last. */
    fun describe(): String = _status.value + (known?.let { " · last answered from $it" } ?: "")

    /**
     * One Location and Speed packet: flags, then whichever of the fields the flags claim, in the
     * order the specification lists them. Only the first few are of any use in a car.
     */
    private fun parse(v: ByteArray) {
        if (v.size < 2) return
        val flags = u16(v, 0)
        var i = 2
        var mph = _fix.value?.speedMph ?: 0f
        if (flags and 0x01 != 0) { if (i + 2 > v.size) return; mph = u16(v, i) / 100f * 2.23694f; i += 2 }
        if (flags and 0x02 != 0) i += 3                                   // total distance, not wanted
        var lat = 0.0
        var lon = 0.0
        if (flags and 0x04 != 0) {
            if (i + 8 > v.size) return
            lat = s32(v, i) / 1e7; lon = s32(v, i + 4) / 1e7; i += 8
        } else return                                                     // without a position there is nothing to do
        if (flags and 0x08 != 0) i += 3                                   // elevation
        var bearing = _fix.value?.bearing ?: 0f
        if (flags and 0x10 != 0 && i + 2 <= v.size) { bearing = u16(v, i) / 100f; i += 2 }
        if ((flags shr 7) and 0x03 == 0) return                           // the phone says it has no position
        _fix.value = Fix(lat, lon, mph, bearing, System.currentTimeMillis())
    }

    private fun u16(v: ByteArray, i: Int) = (v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8)
    private fun s32(v: ByteArray, i: Int) =
        ((v[i].toInt() and 0xFF) or ((v[i + 1].toInt() and 0xFF) shl 8) or ((v[i + 2].toInt() and 0xFF) shl 16) or (v[i + 3].toInt() shl 24)).toDouble()

    companion object {
        private val LNS: UUID = UUID.fromString("00001819-0000-1000-8000-00805f9b34fb")
        private val LOCATION_AND_SPEED: UUID = UUID.fromString("00002a67-0000-1000-8000-00805f9b34fb")
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
