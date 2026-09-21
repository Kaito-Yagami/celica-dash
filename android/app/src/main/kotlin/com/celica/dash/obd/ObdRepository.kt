package com.celica.dash.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** One paired thing that might be a dongle, as the settings list shows it. */
data class ObdDevice(val name: String, val address: String, val kind: String)

/** Where the dongle conversation is at, for the strip dot and the Drive screen. */
enum class ObdStatus { OFF, CONNECTING, LIVE, FAILED }

/**
 * Owns the dongle: connects to the chosen paired device, polls the fast PIDs in a loop, and
 * publishes a [VehicleState]. Reads and clears fault codes on request. Reconnects on its own
 * when the link drops - the ignition going off and on again is the common case.
 */
class ObdRepository(private val ctx: Context, private val scope: CoroutineScope) {
    private val _vehicle = MutableStateFlow<VehicleState?>(null)
    val vehicle: StateFlow<VehicleState?> = _vehicle.asStateFlow()
    private val _status = MutableStateFlow(ObdStatus.OFF)
    val status: StateFlow<ObdStatus> = _status.asStateFlow()
    private val _dtcs = MutableStateFlow<List<String>?>(null)
    val dtcs: StateFlow<List<String>?> = _dtcs.asStateFlow()
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()
    /** The dongle the dash is working with, if any. */
    @Volatile var current: String? = null
        private set

    private var job: Job? = null
    private var elm: Elm327? = null

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<ObdDevice> = try {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        // phones are paired too; only the things that could be a dongle are offered
        bm.adapter?.bondedDevices?.filter { it.bluetoothClass?.majorDeviceClass != android.bluetooth.BluetoothClass.Device.Major.PHONE }
            ?.map { ObdDevice(it.name ?: it.address, it.address, kindOf(it)) } ?: emptyList()
    } catch (e: SecurityException) { emptyList() }

    /**
     * A dongle that offers both talks over the classic radio; the low-energy half carries no
     * serial port at all, so choosing it can only ever fail. Worth saying so in the list.
     */
    @SuppressLint("MissingPermission")
    private fun kindOf(d: BluetoothDevice): String = when (d.type) {
        BluetoothDevice.DEVICE_TYPE_LE -> "low energy - no serial port, pick the other one"
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> "classic"
        BluetoothDevice.DEVICE_TYPE_DUAL -> "classic and low energy"
        else -> "unknown"
    }

    /** The chosen dongle in one line, for the diagnostics - the car is not where one debugs. */
    @SuppressLint("MissingPermission")
    fun describe(): String {
        val a = current ?: return "no dongle chosen"
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val d = runCatching { bm.adapter?.getRemoteDevice(a) }.getOrNull() ?: return "$a · unknown to the adapter"
        val bonded = d.bondState == BluetoothDevice.BOND_BONDED
        return listOfNotNull(
            "${d.name ?: "?"} ($a)", kindOf(d), if (bonded) "paired" else "NOT PAIRED",
            status.value.name.lowercase(), _info.value, _error.value?.let { "last error: $it" },
        ).joinToString(" · ")
    }

    fun start(address: String) {
        stop()
        current = address
        job = scope.launch(Dispatchers.IO) {
            var misses = 0
            while (isActive) {
                _status.value = ObdStatus.CONNECTING
                try {
                    val t = BluetoothElmTransport(ctx, address)
                    val e = Elm327(t)
                    e.init()
                    elm = e
                    _error.value = null
                    _info.value = listOfNotNull(t.way, e.version, e.protocol?.let { "protocol $it" }).joinToString(" · ")
                    _status.value = ObdStatus.LIVE
                    com.celica.dash.data.LinkLog.note("obd", "live · ${_info.value}")
                    misses = 0                      // it answered, so trouble starts from quick again
                    poll(e)
                } catch (ex: Exception) {
                    _status.value = ObdStatus.FAILED
                    _error.value = ex.message ?: ex.toString()
                    com.celica.dash.data.LinkLog.note("obd", "lost · ${_error.value}")
                    _vehicle.value = null
                    misses++
                }
                elm?.let { runCatching { it.close() } }; elm = null

                // Quick to begin with, then further apart. A car sitting with the ignition off is
                // never going to answer, and a flat four seconds meant four sockets opened and
                // shut every four seconds for as long as it stayed parked - which this unit's
                // Bluetooth does not survive. It takes the music down with it, and the phone's
                // GPS with that. Left alone it now settles to a try every half minute.
                //
                // It costs nothing on a real start: the unit powers up with the car, so the first
                // attempt of all is immediate.
                val wait = when {
                    misses <= 2 -> 4_000L
                    misses <= 4 -> 10_000L
                    misses <= 6 -> 20_000L
                    else -> 30_000L
                }
                if (misses > 2) _error.value = "${_error.value ?: "no answer"}  ·  next try in ${wait / 1000}s"
                delay(wait)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        current = null
        elm?.let { runCatching { it.close() } }; elm = null
        _status.value = ObdStatus.OFF
        _vehicle.value = null
    }

    /** The fast loop: rpm and speed every pass, the slow-moving ones one at a time after. */
    private suspend fun poll(e: Elm327) {
        var n = 0
        var misses = 0
        var rpm: Int? = null
        var speed: Float? = null
        var coolant: Int? = null; var intake: Int? = null; var throttle: Int? = null; var maf: Float? = null
        var st: Float? = null; var lt: Float? = null
        while (true) {
            // A dropped reply is not a dropped dongle. Every slower reading below already shrugs
            // one off and keeps its last value; these two, read on every single pass, did not - so
            // one hiccup threw clean out of here, tore the ELM session down, shut the socket and
            // put the whole thing through a fresh connect. On a moving car that is most of them,
            // and it is why the dongle kept dropping. Now it takes five in a row to count.
            val r = runCatching { e.query(0x01, Pids.RPM).let(Pids::rpm) }
            val sp = runCatching { e.query(0x01, Pids.SPEED).let(Pids::speedMph) }
            if (r.isFailure && sp.isFailure) {
                misses++
                if (misses >= 5) throw r.exceptionOrNull() ?: IllegalStateException("the dongle stopped answering")
                delay(200)
            } else {
                misses = 0
            }
            rpm = r.getOrNull() ?: rpm
            speed = sp.getOrNull() ?: speed
            if (n % 6 == 0) coolant = runCatching { e.query(0x01, Pids.COOLANT).let(Pids::tempC) }.getOrNull() ?: coolant
            if (n % 6 == 1) intake = runCatching { e.query(0x01, Pids.INTAKE).let(Pids::tempC) }.getOrNull() ?: intake
            if (n % 6 == 2) throttle = runCatching { e.query(0x01, Pids.THROTTLE).let(Pids::percent) }.getOrNull() ?: throttle
            if (n % 6 == 3) st = runCatching { e.query(0x01, Pids.SHORT_TRIM_1).let(Pids::trimPct) }.getOrNull() ?: st
            if (n % 6 == 4) lt = runCatching { e.query(0x01, Pids.LONG_TRIM_1).let(Pids::trimPct) }.getOrNull() ?: lt
            // the air reading, for the miles to the gallon; not every engine answers it
            if (n % 6 == 5) maf = runCatching { e.query(0x01, Pids.MAF).let(Pids::mafGps) }.getOrNull() ?: maf
            _vehicle.value = VehicleState(
                speedMph = speed, rpm = rpm, coolantC = coolant, intakeC = intake, throttlePct = throttle, mafGps = maf,
                shortTrimPct = st, longTrimPct = lt, obdLive = true, gpsLive = _vehicle.value?.gpsLive ?: false,
            )
            n++
        }
    }

    suspend fun readDtcs() {
        val e = elm ?: return
        _dtcs.value = runCatching { Pids.dtcs(e.query(0x03, -1, 4000)) }.getOrElse { emptyList() }
    }

    suspend fun clearDtcs() {
        val e = elm ?: return
        runCatching { e.query(0x04, -1, 4000) }
        readDtcs()
    }
}

/**
 * RFCOMM to a paired ELM327 - the classic serial-port profile every dongle speaks. Opened on the
 * IO dispatcher; reads poll the stream with a short sleep so a timeout can interrupt them.
 */
class BluetoothElmTransport(private val ctx: Context, private val address: String) : ObdTransport {
    override val name = address
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var socket: android.bluetooth.BluetoothSocket? = null

    /** Which of the four ways in worked, for the diagnostics line. */
    @Volatile var way: String? = null
        private set

    /**
     * Cheap ELM327 clones are fussy about how the serial port is opened: most want an insecure
     * socket, a few only answer the secure one, and the worst of them publish no service record
     * at all and have to be met on channel 1 by reflection. Each is tried in turn, and what each
     * one said is carried up so the reason shows in the diagnostics rather than in a log nobody
     * can reach from the driver's seat.
     */
    @SuppressLint("MissingPermission")
    override suspend fun open() = withContext(Dispatchers.IO) {
        val bm = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bm.adapter ?: throw ObdException("no bluetooth")
        val dev: BluetoothDevice = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()
        val ways: List<Pair<String, () -> android.bluetooth.BluetoothSocket>> = listOf(
            "insecure" to { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            "secure" to { dev.createRfcommSocketToServiceRecord(SPP) },
            "channel 1 insecure" to { reflect(dev, "createInsecureRfcommSocket") },
            "channel 1" to { reflect(dev, "createRfcommSocket") },
        )
        val notes = ArrayList<String>()
        for ((how, make) in ways) {
            var s: android.bluetooth.BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                socket = s; input = s.inputStream; output = s.outputStream
                way = how
                return@withContext
            } catch (e: Exception) {
                notes += "$how: ${e.message}"
                runCatching { s?.close() }
                delay(500)                       // the stack wants a moment between attempts
            }
        }
        throw ObdException(notes.joinToString(" · "))
    }

    private fun reflect(dev: BluetoothDevice, method: String): android.bluetooth.BluetoothSocket =
        dev.javaClass.getMethod(method, Int::class.javaPrimitiveType).invoke(dev, 1) as android.bluetooth.BluetoothSocket

    override suspend fun write(bytes: ByteArray) = withContext(Dispatchers.IO) {
        (output ?: throw ObdException("closed")).apply { write(bytes); flush() }
        Unit
    }

    override suspend fun read(timeoutMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val ins = input ?: throw ObdException("closed")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = ins.available()
            if (n > 0) {
                val b = ByteArray(n)
                val r = ins.read(b)
                return@withContext if (r == n) b else b.copyOf(r.coerceAtLeast(0))
            }
            delay(15)
        }
        ByteArray(0)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null; input = null; output = null
    }

    companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
