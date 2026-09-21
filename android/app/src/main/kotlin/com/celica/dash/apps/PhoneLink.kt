package com.celica.dash.apps

import android.app.ActivityManager
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.celica.dash.ui.phone.PhoneCurtain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/**
 * The phone's projection - CarPlay or Android Auto - with zlink kept out of sight and out of
 * the way. Which of the two is the owner's choice in Setup ([mode]); the dance is the same.
 *
 * zlink is the CarPlay stack on this unit: Apple's protocol lives in its own native libraries
 * (`libzjAirPlay`, `libusbmuxd`, `libauthagent`), and there is no module beside it for anything
 * else to talk to. Once a session is up the screen is the iPhone's, streamed - zlink draws
 * nothing over it but a hidden pull-tab. The one zlink screen anyone sees is its "please connect
 * your phone" page, up while it shakes hands. So the dash covers that: a curtain in the dash's
 * own dress goes over the whole screen, zlink's CarPlay entry is started underneath it, and the
 * curtain comes down onto the iPhone's picture once the session is reported up.
 *
 * The report is the vendor's own integration point, read out of its Bluetooth app: zlink
 * broadcasts `com.zjinnova.zlink` with `status` CONNECTED/DISCONNECT and a `phoneMode`, and the
 * vendor writes `sys.qf.carplay.connect` from it. Both are watched here.
 *
 * The other half is keeping zlink quiet. Left to itself its daemon stays up after a session,
 * its page sits in front after the ignition comes back, and it reconnects the phone when it
 * likes - and while the iPhone is in CarPlay its music is off Bluetooth, so the dash's own
 * player has nothing. So zlink lives only between the button and the end of the session:
 *  - CUT ends a session on purpose: zlink is asked to power down, then stopped dead;
 *  - a session that ends by itself (the phone leaves, the ignition goes) is followed by the
 *    same sweep a moment later;
 *  - at every wake (ACC on, screen on) with no session, zlink is swept and the dash brought
 *    to the front, so the ignition never opens onto its page.
 * Stopping a foreground service is root's to do; [RootShell] is the unit's own door for that.
 * The vendor's property is put straight at the same time, since a stopped zlink says nothing.
 * A stopped package also gets no implicit broadcasts, so nothing wakes zlink but the button.
 *
 * The curtain is a window over other apps, which needs SYSTEM_ALERT_WINDOW; on the unit root
 * grants it once (`tools/car.py install`). Without it the entry is opened bare and zlink's page
 * shows for those seconds.
 */
class PhoneLink(private val app: Application) {
    /** What the phone is, and so what the button opens and the curtain waits for. */
    enum class Mode(val key: String, val word: String, val phone: String) {
        CARPLAY("carplay", "CarPlay", "iPhone"), ANDROID_AUTO("android_auto", "Android Auto", "phone"), NONE("none", "", "");
        companion object { fun of(key: String) = entries.firstOrNull { it.key == key } ?: CARPLAY }
    }
    @Volatile var mode: Mode = Mode.CARPLAY

    sealed class Phase {
        object Idle : Phase()
        data class Waiting(val since: Long) : Phase()
        object Up : Phase()                 // the session is reported up; the curtain is on its way down
        object Silent : Phase()             // nothing answered within the patience
        object Cutting : Phase()            // ending the session, stopping zlink
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    /** The session, as last reported by zlink's broadcast or the vendor's property. */
    private val _up = MutableStateFlow(false)
    val up: StateFlow<Boolean> = _up.asStateFlow()

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var curtain: ComposeView? = null
    private var owner: CurtainOwner? = null
    private var pkg: String? = null
    private var lastReport = "none yet"
    private var lastSweep = "never"
    @Volatile private var rootSeen: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                ACTION_ZLINK -> {
                    val status = i.getStringExtra("status") ?: return
                    val pm = i.getStringExtra("phoneMode") ?: ""
                    lastReport = "$status $pm"
                    // zlink names the mode: carplay_wireless, auto_wireless, auto_wired, and others
                    // this dash does not run; only the owner's kind counts
                    val wanted = when (mode) { Mode.CARPLAY -> "carplay"; Mode.ANDROID_AUTO -> "auto_"; Mode.NONE -> return }
                    if (pm.isNotEmpty() && !pm.contains(wanted, ignoreCase = true)) return
                    val now = status == "CONNECTED"
                    val was = _up.value
                    _up.value = now
                    // a session that ended on its own: zlink is swept once it has said its goodbyes
                    if (was && !now && _phase.value is Phase.Idle) main.postDelayed({ if (!_up.value && _phase.value is Phase.Idle) sweep("session ended") }, 2500)
                }
                ACTION_ACC_OFF -> main.postDelayed({ if (_phase.value is Phase.Idle) sweep("ignition off") }, 1500)
                ACTION_ACC_ON, Intent.ACTION_SCREEN_ON -> main.postDelayed({ wake() }, 800)
            }
        }
    }

    init {
        val f = IntentFilter().apply { addAction(ACTION_ZLINK); addAction(ACTION_ACC_ON); addAction(ACTION_ACC_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        ContextCompat.registerReceiver(app, receiver, f, ContextCompat.RECEIVER_EXPORTED)
        _up.value = flagUp()
    }

    /** The vendor's own word on a session - written for CarPlay; Android Auto has only the broadcast. */
    private fun flagUp(): Boolean = mode == Mode.CARPLAY && prop(PROP_CARPLAY) == "true"

    /** Whether the curtain may be drawn at all. */
    val canCover: Boolean get() = Settings.canDrawOverlays(app)

    /**
     * Whether this unit's projection app is one the dash can choreograph: zlink, whose
     * handshake it can curtain and whose session it can see. On any other unit the button
     * simply opens the app the owner picked and the dash gets out of the way.
     */
    val choreographed: Boolean get() = UnitProfile.zlink && mode != Mode.NONE

    /**
     * The whole move: curtain up, zlink's CarPlay entry started under it, then the wait. A
     * session that is already up still gets the curtain for the moment zlink's window takes to
     * appear, so the eye sees the dash, then CarPlay, and nothing between.
     */
    fun open(pkg: String) {
        if (_phase.value !is Phase.Idle) return
        this.pkg = pkg
        if (!choreographed && pkg.isNotBlank()) { Apps.launch(app, pkg); return }
        _up.value = flagUp()
        if (!canCover) { if (pkg.isNotBlank()) Apps.launchProjection(app, pkg, mode); return }
        raise()
        _phase.value = Phase.Waiting(System.currentTimeMillis())
        // no CarPlay app here (the emulator): the curtain alone, run through as if a phone
        // answered after a few seconds, so the choreography can be judged on a desk
        if (pkg.isBlank()) { main.postDelayed({ if (_phase.value is Phase.Waiting) _up.value = true }, 3500); main.postDelayed(watch, 300); return }
        // the curtain's first frame before zlink's window comes up behind it
        main.postDelayed({ Apps.launchProjection(app, pkg, mode) }, 120)
        main.postDelayed(watch, 300)
    }

    private val watch = object : Runnable {
        override fun run() {
            val ph = _phase.value
            if (ph is Phase.Idle || ph is Phase.Cutting) return
            if (flagUp()) _up.value = true
            if (_up.value && ph !is Phase.Up) {
                _phase.value = Phase.Up
                main.postDelayed({ if (_phase.value is Phase.Up) lower() }, GRACE_MS)
                return
            }
            if (ph is Phase.Waiting && System.currentTimeMillis() - ph.since > PATIENCE_MS) _phase.value = Phase.Silent
            main.postDelayed(this, 300)
        }
    }

    /** Another turn of the patience, from the curtain's own button. */
    fun retry() {
        if (_phase.value !is Phase.Silent) return
        _phase.value = Phase.Waiting(System.currentTimeMillis())
        pkg?.let { Apps.launchProjection(app, it, mode) }
        main.postDelayed(watch, 300)
    }

    /** Back to the dash from the curtain: nothing came of it, so zlink goes too. */
    fun dismiss() {
        main.removeCallbacks(watch)
        _phase.value = Phase.Idle
        drop(fade = false)
        home()
        sweep("gave up waiting")
    }

    /**
     * The end of a session, on purpose. zlink is told the unit is powering down - its one
     * documented way to be asked to let the phone go - given a moment to say so, then stopped
     * dead, and the dash comes to the front. The iPhone, seeing its link gone, comes back to
     * Bluetooth on its own.
     */
    fun cut() {
        if (_phase.value !is Phase.Idle || !choreographed) return
        _phase.value = Phase.Cutting
        app.sendBroadcast(Intent(ACTION_POWER_OFF).setPackage(ZLINK))
        io.execute {
            val t = System.currentTimeMillis()
            while (System.currentTimeMillis() - t < 3000 && flagUp()) Thread.sleep(200)
            stopZlink("cut")
            main.post { _phase.value = Phase.Idle; home() }
        }
    }

    /** zlink stopped when nothing wants it: after a session, at the ignition, on a wake. */
    private fun sweep(why: String) {
        if (!choreographed) return
        io.execute { stopZlink(why) }
    }

    /** The unit is back: no session means no zlink, and the dash in front rather than its page. */
    private fun wake() {
        if (_phase.value !is Phase.Idle || !choreographed) return
        // the vendor's flag alone decides: our own memory of the session can be stale after a
        // restart, and a sweep on a live session would drop the owner out of CarPlay. Android
        // Auto has no flag; the broadcast is all there is, so its memory has to do
        if (flagUp() || (mode == Mode.ANDROID_AUTO && _up.value)) { _up.value = true; return }
        if (prop(PROP_TOP).startsWith(ZLINK)) home()
        sweep("wake")
    }

    private fun stopZlink(why: String) {
        val r = RootShell.run("am force-stop $ZLINK; setprop $PROP_CARPLAY false; echo done")
        rootSeen = r != null
        if (r == null) runCatching { (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(ZLINK) }
        lastSweep = "$why · ${if (r != null) "stopped" else "no root: background kill only"} · ${com.celica.dash.data.Units.date("HH:mm:ss").format(java.util.Date())}"
        com.celica.dash.data.LinkLog.note("carplay", "zlink swept · $why · ${if (r != null) "root ok" else "NO ROOT"}")
        main.post { _up.value = false }
    }

    private fun home() {
        runCatching { app.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun raise() {
        if (curtain != null) return
        val o = CurtainOwner().also { owner = it }
        val v = ComposeView(app).apply {
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            setContent {
                val ph by phase.collectAsState()
                PhoneCurtain(ph, mode = mode, unitName = unitName(), onBack = ::dismiss, onRetry = ::retry)
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; title = "Phone curtain" }
        if (runCatching { wm.addView(v, lp) }.isFailure) { owner = null; return }
        o.start()
        curtain = v
    }

    /** Down onto the iPhone's picture, with a short fade so the cut is not a cut. */
    private fun lower() {
        _phase.value = Phase.Idle
        drop(fade = true)
    }

    private fun drop(fade: Boolean) {
        val v = curtain ?: return
        val o = owner
        curtain = null; owner = null
        val gone = Runnable { runCatching { wm.removeViewImmediate(v) }; o?.stop() }
        if (fade) v.animate().alpha(0f).setDuration(FADE_MS).withEndAction(gone).start() else gone.run()
    }

    fun describe(): String =
        "${mode.word.ifBlank { "no phone" }} · ${if (choreographed) "zlink, choreographed" else "plain launch"} · session ${if (_up.value) "up" else "down"} · ${_phase.value::class.simpleName} · curtain ${if (canCover) "allowed" else "NOT allowed (appops set ${app.packageName} SYSTEM_ALERT_WINDOW allow)"}" +
            " · root ${when (rootSeen) { null -> "untried"; true -> "ok"; false -> "NOT answering" }} · last report $lastReport · last sweep $lastSweep"

    fun close() {
        runCatching { app.unregisterReceiver(receiver) }
        main.removeCallbacks(watch)
        drop(fade = false)
        io.shutdown()
    }

    /** The name the iPhone sees: zlink's, which is what it advertises; the vendor's as a fallback. */
    private fun unitName(): String? = NAME_PROPS.map { prop(it).trim() }.firstOrNull { it.isNotBlank() }

    private fun prop(name: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as String
    }.getOrDefault("")

    /** A window of our own needs a lifecycle of its own for Compose to run in it. */
    private class CurtainOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        fun start() { saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
        fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
    }

    companion object {
        const val ZLINK = Apps.ZLINK
        const val ACTION_ZLINK = "com.zjinnova.zlink"
        private const val ACTION_POWER_OFF = "com.zjinnova.zlink.action.POWER_OFF"
        private const val ACTION_ACC_ON = "com.qf.action.ACC_ON"
        private const val ACTION_ACC_OFF = "com.qf.action.ACC_OFF"
        private const val PROP_CARPLAY = "sys.qf.carplay.connect"
        private const val PROP_TOP = "sys.qf.top_pkg_name"
        private val NAME_PROPS = listOf("persist.zlink.bt.name", "rw.zlink.bt.name", "persist.sys.qf.bt.local.name")
        private const val GRACE_MS = 900L           // the first frame of the iPhone's picture after the report
        private const val PATIENCE_MS = 40_000L
        private const val FADE_MS = 350L
    }
}
