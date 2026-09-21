package com.celica.dash.phone

import android.Manifest
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A Bluetooth device as the pairing page shows it. */
data class BtDevice(val name: String, val address: String, val phone: Boolean, val bonded: Boolean)

/**
 * What Android's own radio knows about the phone: whether one is linked, over which profiles,
 * its battery when the stack tells us, and the devices around while scanning.
 */
data class PhoneState(
    val enabled: Boolean = false,
    val localName: String? = null,              // what this radio calls itself - the name the phone sees
    val localAddress: String? = null,
    val bonded: List<BtDevice> = emptyList(),
    val found: List<BtDevice> = emptyList(),
    val scanning: Boolean = false,
    val pairing: String? = null,                // address mid-bond
    val connected: BtDevice? = null,            // the phone with a link up
    val audio: Boolean = false,                 // A2DP: music
    val calls: Boolean = false,                 // HFP: the unit's phone app can take calls
    val contacts: Boolean = false,              // PBAP: the phone book
    val battery: Int? = null,                   // 0..100 when the phone reports it
    val audioKeptOff: List<String> = emptyList(), // phones whose audio profiles this side refuses
)

/**
 * The classic-Bluetooth side of the phone link, on Android's own radio. Pairing is the system's
 * job (it shows the passkey dialog); this asks for it, watches the answer, and reads which
 * profiles are up.
 *
 * The profile numbers past HEADSET are the car-side ones (A2DP sink, HFP client, PBAP client);
 * they are not in the public API but the head unit's stack serves them through the same call.
 *
 * On this unit those three must never take the phone. The music, the calls and the CarPlay
 * handshake all run on the head unit's *other* radio - the vendor's external module, which the
 * phone sees as a second device with its own name. Android's radio is only wanted for the dongle
 * and the phone's GPS. But the moment the phone is paired here (which the GPS may need), Android
 * offers it music and calls too, the phone takes whichever of the two answered last, and when
 * that is this one the music goes into a sink nothing plays: "connected, but no audio". So while
 * the vendor's service is present, every paired phone has its audio profiles switched off on this
 * side, and is put off them if it arrives on one anyway.
 */
class PhoneRepository(private val ctx: Context, private val vendorAudio: () -> Boolean = { false }) {
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val adapter: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private val proxies = HashMap<Int, BluetoothProfile>()
    private var started = false
    private val found = LinkedHashMap<String, BtDevice>()

    private val profiles = mapOf(
        BluetoothProfile.A2DP to "audio", A2DP_SINK to "audio",       // source, sink
        BluetoothProfile.HEADSET to "calls", HFP_CLIENT to "calls",   // AG, client
        PBAP_CLIENT to "contacts",                                    // PBAP client
    )
    /** The car-side profiles: the ones that would carry the phone's music and calls here. */
    private val sinkProfiles = listOf(A2DP_SINK, HFP_CLIENT, PBAP_CLIENT)

    fun granted() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val dev = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            when (i.action) {
                BluetoothDevice.ACTION_FOUND -> dev?.let { d -> toDevice(d)?.let { found[it.address] = it }; publish() }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> { found.clear(); _state.value = _state.value.copy(scanning = true, found = emptyList()) }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> _state.value = _state.value.copy(scanning = false)
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                    _state.value = _state.value.copy(pairing = if (st == BluetoothDevice.BOND_BONDING) dev?.address else null)
                    if (st == BluetoothDevice.BOND_BONDED) { dev?.address?.let { found.remove(it) }; keepAudioOffPhones() }
                    publish()
                }
                BATTERY_CHANGED -> {
                    val lvl = i.getIntExtra(BATTERY_EXTRA, -1)
                    if (dev != null && dev.address == _state.value.connected?.address) _state.value = _state.value.copy(battery = lvl.takeIf { it in 0..100 })
                }
                // adapter state, ACL, profile connection changes: a phone may just have arrived
                // on a profile it must not have
                else -> { keepAudioOffPhones(); publish() }
            }
        }
    }

    /** Safe to call again; binds the receiver and profile proxies once permission exists. */
    fun start() {
        val adapter = adapter ?: return
        if (started || !granted()) return
        started = true
        val f = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND); addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED); addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED); addAction(BluetoothDevice.ACTION_ACL_CONNECTED); addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headsetclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.pbapclient.profile.action.CONNECTION_STATE_CHANGED")
            addAction(BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(ctx, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        for (p in profiles.keys) {
            runCatching {
                adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) { proxies[profile] = proxy; keepAudioOffPhones(); publish() }
                    override fun onServiceDisconnected(profile: Int) { proxies.remove(profile); publish() }
                }, p)
            }
        }
        publish()
    }

    fun scan() {
        val a = adapter ?: return
        runCatching { if (a.isDiscovering) a.cancelDiscovery(); a.startDiscovery() }
    }

    fun stopScan() { runCatching { adapter?.cancelDiscovery() } }

    fun pair(address: String) {
        val a = adapter ?: return
        runCatching { a.cancelDiscovery(); a.getRemoteDevice(address).createBond() }
        _state.value = _state.value.copy(pairing = address)
    }

    /** Un-pairs through the hidden call; false when the system will not let an app do it. */
    fun forget(address: String): Boolean {
        val a = adapter ?: return false
        return runCatching {
            val d = a.getRemoteDevice(address)
            d.javaClass.getMethod("removeBond").invoke(d) as Boolean
        }.getOrDefault(false).also { publish() }
    }

    /** Renames this radio - what the phone will list it as. The vendor's radio has its own name. */
    fun rename(name: String): Boolean {
        val a = adapter ?: return false
        return runCatching { a.setName(name.trim()) }.getOrDefault(false).also { publish() }
    }

    // ---- keeping the phone's audio on the other radio ---------------------------------------

    private var keptOff = HashSet<String>()

    /**
     * Every paired phone: audio profiles off on this side, and disconnected from them if it is
     * on one. Only while the vendor's service is present - on a unit without it, Android's radio
     * is the only one there is, and must be left to do the music.
     */
    @Synchronized
    private fun keepAudioOffPhones() {
        val a = adapter ?: return
        if (!vendorAudio() || !granted()) return
        // phones, and anything dual-mode: a phone whose class the stack has not read yet still
        // looks like one to the profiles. The dongle is classic only and is left alone
        val phones = runCatching { a.bondedDevices.orEmpty().filter { isPhone(it) || it.type == BluetoothDevice.DEVICE_TYPE_DUAL } }.getOrDefault(emptyList())
        val done = HashSet<String>()
        for (d in phones) {
            var ok = true
            for (p in sinkProfiles) {
                val proxy = proxies[p] ?: continue
                ok = keepOff(proxy, d) && ok
            }
            if (ok) done += d.address
        }
        keptOff = done
    }

    /** Priority off (the stack then refuses the phone on it), and off the profile if it is on. */
    private fun keepOff(proxy: BluetoothProfile, d: BluetoothDevice): Boolean = runCatching {
        val cls = proxy.javaClass
        // R renamed priority to connection policy; the values for "off" agree
        val get = cls.methods.firstOrNull { it.name == "getConnectionPolicy" && it.parameterCount == 1 }
            ?: cls.methods.firstOrNull { it.name == "getPriority" && it.parameterCount == 1 }
        val set = cls.methods.firstOrNull { it.name == "setConnectionPolicy" && it.parameterCount == 2 }
            ?: cls.methods.firstOrNull { it.name == "setPriority" && it.parameterCount == 2 }
        val current = get?.invoke(proxy, d) as? Int
        var ok = true
        if (current != POLICY_OFF) ok = (set?.invoke(proxy, d, POLICY_OFF) as? Boolean) == true
        if (proxy.connectedDevices.orEmpty().any { it.address == d.address }) {
            cls.methods.firstOrNull { it.name == "disconnect" && it.parameterCount == 1 }?.invoke(proxy, d)
        }
        ok
    }.getOrDefault(false)

    private fun isPhone(d: BluetoothDevice) = runCatching { d.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.PHONE }.getOrDefault(false)

    // ---- state -------------------------------------------------------------------------------

    fun publish() {
        val a = adapter
        if (a == null || !granted()) { _state.value = PhoneState(); return }
        val enabled = runCatching { a.isEnabled }.getOrDefault(false)
        val bonded = runCatching { a.bondedDevices.mapNotNull(::toDevice) }.getOrDefault(emptyList())
        // which profiles have a device up, and which device that is
        val up = HashMap<String, MutableSet<String>>()
        for ((p, kind) in profiles) {
            val proxy = proxies[p] ?: continue
            runCatching { proxy.connectedDevices }.getOrNull()?.forEach { d -> up.getOrPut(d.address) { HashSet() }.add(kind) }
        }
        val linkAddr = up.keys.maxByOrNull { up[it]!!.size }
        val link = linkAddr?.let { addr -> bonded.firstOrNull { it.address == addr } ?: runCatching { toDevice(a.getRemoteDevice(addr)) }.getOrNull() }
        val kinds = linkAddr?.let { up[it] } ?: emptySet()
        val old = _state.value
        _state.value = old.copy(
            enabled = enabled,
            localName = runCatching { a.name }.getOrNull(), localAddress = runCatching { a.address }.getOrNull(),
            bonded = bonded.sortedByDescending { it.phone }, found = found.values.filter { f -> bonded.none { it.address == f.address } },
            connected = link, audio = "audio" in kinds, calls = "calls" in kinds, contacts = "contacts" in kinds,
            battery = if (link?.address == old.connected?.address) old.battery else null,
            audioKeptOff = bonded.filter { it.address in keptOff }.map { it.name },
        )
    }

    /** One line for the diagnostics: this radio, and what it is holding. */
    fun describe(): String {
        val st = _state.value
        if (!st.enabled) return "OFF"
        val phones = st.bonded.filter { it.phone }.map { it.name }
        val off = when {
            !vendorAudio() -> "no separate vendor radio, so audio is this side's and is left alone"
            phones.isEmpty() -> "no paired phone"
            st.audioKeptOff.isEmpty() -> "could NOT switch audio profiles off for ${phones.joinToString(", ")} (hidden API refused?)"
            else -> "audio profiles kept off for ${st.audioKeptOff.joinToString(", ")}"
        }
        return "on · this radio \"${st.localName ?: "?"}\" ${st.localAddress ?: ""} · linked: ${st.connected?.name ?: "none"} · audio ${st.audio} · calls ${st.calls} · $off"
    }

    private fun toDevice(d: BluetoothDevice): BtDevice? = runCatching {
        val name = d.name ?: return null
        BtDevice(name, d.address, isPhone(d), d.bondState == BluetoothDevice.BOND_BONDED)
    }.getOrNull()

    companion object {
        private const val BATTERY_CHANGED = "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
        private const val BATTERY_EXTRA = "android.bluetooth.device.extra.BATTERY_LEVEL"
        // BluetoothProfile's hidden car-side numbers
        private const val A2DP_SINK = 11
        private const val HFP_CLIENT = 16
        private const val PBAP_CLIENT = 17
        /** PRIORITY_OFF before R, CONNECTION_POLICY_FORBIDDEN from R: both zero. */
        private const val POLICY_OFF = 0
    }
}
