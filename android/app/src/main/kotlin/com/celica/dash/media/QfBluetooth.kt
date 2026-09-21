package com.celica.dash.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A phone call as the unit's Bluetooth service reports it. */
data class Call(val kind: Kind, val number: String?, val name: String?) {
    enum class Kind { INCOMING, OUTGOING, ACTIVE }
}

/** What the vendor's Bluetooth service tells us: the track, its progress, the link, a call. */
data class QfState(
    val available: Boolean = false,         // the vendor service exists on this unit
    val connected: Boolean = false,
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val positionMs: Long = 0, val durationMs: Long = 0, val positionAt: Long = 0,
    val playing: Boolean = false,
    val call: Call? = null,
    val linkedAddress: String? = null,      // the phone on the vendor's radio, when one is
    val localName: String? = null,          // what that radio calls itself - the name the phone sees
    val localAddress: String? = null,
    val carPlay: Boolean = false,           // zlink has CarPlay up
)

/**
 * The head unit's own Bluetooth (QF / Qianfeng firmware): the phone's music and calls go
 * through the vendor's TechBTService, not through anything Android publishes. Read from its
 * decompiled code (tools/uploads):
 *
 *  - com.qf.action.BT.MUSIC.INFO      broadcast: songName / songSinger / songAlbum
 *  - com.qf.action.BT.MUSIC.CONTROL   broadcast in: command 1 prev, 2 next, 3 play/pause, 4 play, 5 pause
 *  - com.qf.action.BT_STATE           broadcast: state (4 connected, 5 disconnected), value
 *  - com.qf.action.bt.call.state      broadcast: callState 1 incoming, 2 outgoing, 3 active, 4 hung up; callNumber, callName
 *  - sys.qf.bt.music.progress         system property "position,duration", refreshed as the track plays
 *  - sys.qf.bt.is.connect             system property, "true" while the phone's hands-free link is up
 *  - persist.sys.qf.bt.connect.addr   system property, the address of that phone (blank when none)
 *  - persist.sys.qf.bt.local.name     system property, the name the vendor's radio shows the phone
 *  - sys.qf.carplay.connect           system property, "true" while zlink has CarPlay up
 *  - com.qf.bluetooth.TechBTService   bindable: callAccept(2) callReject(1) hangUp(3) dialing(4) dialCancel(5)
 *
 * Play state is not broadcast, so it is inferred: the position moving means playing. The link is
 * read from the property rather than trusted to the broadcast, which is only heard if the dash
 * happens to be running when the phone arrives.
 *
 * This is a second radio. The unit has two: Android's own, which the dash uses for the dongle and
 * the phone's GPS, and this one, an external module on a serial port that carries the music, the
 * calls and the CarPlay handshake. The phone sees them as two separate devices.
 */
class QfBluetooth(private val ctx: Context) {
    private val _state = MutableStateFlow(QfState())
    val state: StateFlow<QfState> = _state.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var binder: IBinder? = null
    private var lastPos = -1L
    private var lastMoveAt = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_INFO -> _state.value = _state.value.copy(
                    title = i.getStringExtra("songName")?.takeIf { it.isNotBlank() },
                    artist = i.getStringExtra("songSinger")?.takeIf { it.isNotBlank() },
                    album = i.getStringExtra("songAlbum")?.takeIf { it.isNotBlank() },
                )
                ACTION_STATE -> when (i.getIntExtra("state", -1)) {
                    4 -> _state.value = _state.value.copy(connected = true)
                    5, 3 -> _state.value = _state.value.copy(connected = false, title = null, artist = null, album = null, playing = false)
                }
                ACTION_CALL -> {
                    val kind = when (i.getIntExtra("callState", -1)) { 1 -> Call.Kind.INCOMING; 2 -> Call.Kind.OUTGOING; 3 -> Call.Kind.ACTIVE; else -> null }
                    _state.value = _state.value.copy(call = kind?.let { Call(it, i.getStringExtra("callNumber"), i.getStringExtra("callName")) })
                }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { binder = service }
        override fun onServiceDisconnected(name: ComponentName) { binder = null; main.postDelayed({ bind() }, 3000) }
    }

    /** Only does anything on a unit that has the vendor service. */
    fun start() {
        if (started) return
        val present = runCatching { ctx.packageManager.getPackageInfo(PKG, 0) }.isSuccess
        _state.value = _state.value.copy(available = present)
        if (!present) return
        started = true
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter().apply { addAction(ACTION_INFO); addAction(ACTION_STATE); addAction(ACTION_CALL) }, ContextCompat.RECEIVER_EXPORTED)
        bind()
        main.post(poll)
    }

    private fun bind() {
        runCatching { ctx.bindService(Intent(SERVICE).setPackage(PKG), connection, Context.BIND_AUTO_CREATE) }
    }

    /** Progress from the property; the position moving is the only word we get that it plays. */
    private val poll = object : Runnable {
        override fun run() {
            val raw = prop(PROP_PROGRESS)
            val parts = raw.split(",")
            val pos = parts.getOrNull(0)?.trim()?.toLongOrNull()
            val dur = parts.getOrNull(1)?.trim()?.toLongOrNull()
            val now = SystemClock.uptimeMillis()
            // the link, as the vendor service itself records it
            val linked = prop(PROP_CONNECTED) == "true"
            val addr = prop(PROP_CONNECT_ADDR).trim().takeIf { it.isNotBlank() && linked }
            val name = prop(PROP_LOCAL_NAME).trim().takeIf { it.isNotBlank() }
            val local = prop(PROP_LOCAL_ADDR).trim().takeIf { it.isNotBlank() }
            val carPlay = prop(PROP_CARPLAY) == "true"
            var s = _state.value
            if (pos != null) {
                if (pos != lastPos) { lastMoveAt = now; lastPos = pos }
                val playing = now - lastMoveAt < 2500
                if (s.positionMs != pos || s.durationMs != (dur ?: 0) || s.playing != playing) {
                    s = s.copy(positionMs = pos, durationMs = dur ?: 0, positionAt = now, playing = playing)
                }
            }
            if (s.connected != linked) com.celica.dash.data.LinkLog.note("phone audio", if (linked) "linked · ${addr ?: "?"}" else "gone")
            if (s.carPlay != carPlay) com.celica.dash.data.LinkLog.note("carplay", if (carPlay) "session up" else "session down")
            if (s.connected != linked || s.linkedAddress != addr || s.localName != name || s.localAddress != local || s.carPlay != carPlay) {
                s = s.copy(connected = linked, linkedAddress = addr, localName = name, localAddress = local, carPlay = carPlay)
                if (!linked) s = s.copy(title = null, artist = null, album = null, playing = false)
            }
            if (s !== _state.value) _state.value = s
            main.postDelayed(this, 1000)
        }
    }

    /**
     * Whether the vendor's service drives a radio of its own, or Android's. This is the vendor
     * app's own decision (BlueToothApp.getCurrentBTModuleType), copied: a named external module,
     * or the "is external" flag, and it is on a serial port; anything else is Android's radio
     * dressed up. Everything that keeps the phone's audio off Android's side hangs on this being
     * true, because on a one-radio unit Android's sink *is* the music.
     */
    fun externalRadio(): Boolean {
        if (!_state.value.available) return false
        if (prop("persist.sys.is.ext.bt") == "true") return true
        return prop("persist.sys.btmodel.choose").trim() in setOf("wq5876", "cq8761", "sd8761", "sd936")
    }

    /**
     * How the vendor's Bluetooth is set up, for the diagnostics: which module it drives, whether
     * the factory "dual Bluetooth" is on, and what zlink has been told. None of it is ours to
     * change - it is the head unit's firmware - but all of it decides what the phone sees.
     */
    fun describeConfig(): String {
        if (!_state.value.available) return "no vendor service"
        val module = prop("persist.sys.btmodel.choose").ifBlank { "default" }
        val external = prop("persist.sys.is.ext.bt")
        val s = _state.value
        return listOf(
            "platform ${prop("ro.qf.platform").ifBlank { "?" }}",
            "module $module" + when (external) { "true" -> " (is.ext.bt true)"; "false" -> " (is.ext.bt false)"; else -> "" },
            if (externalRadio()) "TWO RADIOS: the vendor drives its own" else "ONE RADIO: the vendor drives Android's",
            "dual bt ${prop("persist.sys.double_bt").ifBlank { "unset" }}",
            "a2dp route ${prop("persist.sys.qf.use.a2dp_route").ifBlank { "unset" }}",
            "internal link type ${prop("persist.sys.interbt.link_type").ifBlank { "unset" }}",
            "zlink bt ${prop("rw.zlink.bt.type").ifBlank { "?" }} as \"${prop("rw.zlink.bt.name").ifBlank { "?" }}\"",
            "radio \"${s.localName ?: "?"}\" ${s.localAddress ?: ""}".trim(),
            "phone ${s.linkedAddress ?: "none"}",
            "carplay ${s.carPlay}",
        ).joinToString(" · ")
    }

    // ---- music ------------------------------------------------------------------------------
    fun previous() = control(1).also { lastMoveAt = SystemClock.uptimeMillis() }
    fun next() = control(2).also { lastMoveAt = SystemClock.uptimeMillis() }
    fun playPause() = control(3)
    fun play() = control(4)
    fun pause() = control(5)
    private fun control(cmd: Int) {
        ctx.sendBroadcast(Intent(ACTION_CONTROL).setPackage(PKG).putExtra("command", cmd))
        ctx.sendBroadcast(Intent(ACTION_CONTROL).putExtra("command", cmd))
    }

    // ---- calls ------------------------------------------------------------------------------
    fun accept() = transact(2)
    fun reject() = transact(1)
    fun hangUp() = transact(3)
    fun dial(number: String) = transact(4) { it.writeString(number); it.writeString("") }

    private fun transact(code: Int, args: ((Parcel) -> Unit)? = null): Boolean {
        val b = binder ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            args?.invoke(data)
            b.transact(code, data, reply, 0)
            reply.readException()
            true
        } catch (e: Exception) { false } finally { data.recycle(); reply.recycle() }
    }

    private fun prop(name: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as String
    }.getOrDefault("")

    companion object {
        const val PKG = "com.qf.bluetooth"
        private const val SERVICE = "com.qf.bluetooth.TechBTService"
        private const val DESCRIPTOR = "com.qf.bluetoothsdk.aidl.BluetoothBinder"
        private const val ACTION_INFO = "com.qf.action.BT.MUSIC.INFO"
        private const val ACTION_CONTROL = "com.qf.action.BT.MUSIC.CONTROL"
        private const val ACTION_STATE = "com.qf.action.BT_STATE"
        private const val ACTION_CALL = "com.qf.action.bt.call.state"
        private const val PROP_PROGRESS = "sys.qf.bt.music.progress"
        private const val PROP_CONNECTED = "sys.qf.bt.is.connect"
        private const val PROP_CONNECT_ADDR = "persist.sys.qf.bt.connect.addr"
        private const val PROP_LOCAL_NAME = "persist.sys.qf.bt.local.name"
        private const val PROP_LOCAL_ADDR = "persist.sys.qf.bt.local.addr"
        private const val PROP_CARPLAY = "sys.qf.carplay.connect"
    }
}
