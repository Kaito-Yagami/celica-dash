package com.celica.dash

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.celica.dash.ui.Shell
import com.celica.dash.ui.theme.CelicaTheme

class CelicaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The head unit's Bluetooth sink profiles - the ones that would take the phone's music and
        // calls onto Android's side, where nothing plays them - are hidden API. Lifting the
        // restriction here is what lets PhoneRepository keep them switched off for the phone.
        if (Build.VERSION.SDK_INT >= 28) runCatching { org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/bluetooth/") }
        // A crash in the car is only ever seen as a restart. The trace goes to a file at the
        // moment it happens (nothing is read here, and the write simply fails if storage is
        // still locked), and the diagnostics page shows it, so a restart has a reason next to it.
        val before = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    java.text.SimpleDateFormat("d MMM HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date()) + " on ${t.name}" + System.lineSeparator() + android.util.Log.getStackTraceString(e)
                )
            }
            before?.uncaughtException(t, e)
        }
        // nothing else here: at boot this runs before the user's storage is unlocked (the
        // activity is direct-boot aware), and preferences, files and the wallpaper are all off
        // limits until MainActivity sees the unlock
    }
}

/**
 * The one activity. Full-screen, landscape, never sleeps while it is showing - it is the dash,
 * and the head unit already turns the screen off with the ignition.
 */
class MainActivity : ComponentActivity() {
    private var unlockWatch: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // Started before the user's storage is unlocked - the unit does this a few seconds into
        // every boot, and the dash is what Android starts in that window so that its own
        // stand-in launcher never gets to paint "Device is starting". Nothing of ours can be
        // read yet, so: black, and come back the moment the unlock is announced.
        if (!getSystemService(android.os.UserManager::class.java).isUserUnlocked) {
            setContentView(android.view.View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
            unlockWatch = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: android.content.Context, i: android.content.Intent) { unwatchUnlock(); recreate() }
            }
            ContextCompat.registerReceiver(this, unlockWatch, android.content.IntentFilter(android.content.Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED)
            return
        }
        com.celica.dash.apps.UnitProfile.probe(this)
        val start = com.celica.dash.data.Prefs(this).startupNow()
        com.celica.dash.ui.theme.Ink.apply(start.look, start.accent, start.background)
        com.celica.dash.data.CarSpec.apply(start.car)
        com.celica.dash.data.Units.apply(start.units, start.locale)
        com.celica.dash.data.Region.apply(start.region, start.mapHome.first, start.mapHome.second, start.mapHome.third)
        setContent {
            CelicaTheme(start.zoom) { Shell() }
        }
        // location for GPS speed and weather; Bluetooth for the OBD dongle. Asked once, up front.
        val ask = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { wanted += Manifest.permission.BLUETOOTH_CONNECT; wanted += Manifest.permission.BLUETOOTH_SCAN }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        // on a first run the Setup wizard asks, with a reason beside each; this is for later
        if (missing.isNotEmpty() && start.setupDone) ask.launch(missing.toTypedArray())
    }

    private fun unwatchUnlock() { unlockWatch?.let { runCatching { unregisterReceiver(it) } }; unlockWatch = null }

    override fun onDestroy() { unwatchUnlock(); super.onDestroy() }

    companion object {
        /**
         * A black wallpaper. The wallpaper is what shows behind anything that appears between
         * the boot animation's last frame and the dash's first; with a vendor's gradient there
         * it is a whole other screen. The animation ends on black, the dash's starting window
         * is black (the theme), and the ceremony starts from black. Offered in Setup, since it
         * changes something of the unit's that is not the dash's to change unasked.
         */
        fun setBlackWallpaper(ctx: android.content.Context): Boolean = runCatching {
            val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLACK) }
            android.app.WallpaperManager.getInstance(ctx).setBitmap(bmp)
            true
        }.getOrDefault(false)
    }
}
