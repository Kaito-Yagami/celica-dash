package com.celica.dash.apps

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Another app on the unit that can be opened from the dash. */
class AppEntry(val label: String, val pkg: String, val icon: Bitmap?)

/**
 * The other apps on the unit. The dash is the launcher, so the way to anything else - CarPlay
 * above all - goes through here. The CarPlay app is guessed by name, since every head unit
 * calls its one something different (Zlink, Carlink, AutoKit, ...), and can be set by hand.
 */
object Apps {
    private val CARPLAY_HINTS = listOf("zlink", "carplay", "carlink", "autokit", "autoplay", "easyconnection", "easyconn", "carbit", "phonelink", "smartlink", "autolink", "androidauto", "android auto", "autoconnect", "carlinkit", "mirrorlink", "projection")

    fun installed(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != ctx.packageName }
                .map { AppEntry(it.loadLabel(pm).toString(), it.activityInfo.packageName, runCatching { it.loadIcon(pm).toBitmap(96) }.getOrNull()) }
                .distinctBy { it.pkg }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    private val BT_MUSIC_HINTS = listOf("btmusic", "bt_music", "bluetoothmusic", "a2dp", "btaudio", "bt music", "bluetooth music", "bluetooth audio")

    /** The vendor's Bluetooth music app, when one can be told apart by name. */
    fun guessBtMusic(apps: List<AppEntry>): AppEntry? =
        apps.firstOrNull { a -> BT_MUSIC_HINTS.any { h -> a.pkg.lowercase().contains(h.replace(" ", "")) || a.label.lowercase().contains(h) } }

    /** The app that looks like the CarPlay one, or null when none does. */
    fun guessCarPlay(apps: List<AppEntry>): AppEntry? =
        apps.firstOrNull { a -> CARPLAY_HINTS.any { h -> a.pkg.lowercase().contains(h) || a.label.lowercase().replace(" ", "").contains(h) } }

    fun launch(ctx: Context, pkg: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true }.getOrDefault(false)
    }

    /**
     * Straight into the phone's projection. zlink has its own front door with every mode on
     * it, but it also declares a CarPlay-only entry (`launcher.CarPlayActivity`, disabled in
     * its manifest and switched on with `pm enable` where root allows). That opens on the
     * CarPlay page itself, and on a paired iPhone the session simply starts. For Android Auto,
     * and anywhere that entry is missing or off, the app's front door is used instead.
     */
    fun launchProjection(ctx: Context, pkg: String, mode: PhoneLink.Mode): Boolean {
        if (pkg == ZLINK && mode == PhoneLink.Mode.CARPLAY) {
            val direct = Intent().setClassName(pkg, ZLINK_CARPLAY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { ctx.startActivity(direct); true }.getOrDefault(false)) return true
        }
        return launch(ctx, pkg)
    }

    const val ZLINK = "com.zjinnova.zlink"
    private const val ZLINK_CARPLAY = "com.zjinnova.android.zlink.features.launcher.CarPlayActivity"

    private fun Drawable.toBitmap(px: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return Bitmap.createScaledBitmap(bitmap, px, px, true)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        setBounds(0, 0, px, px); draw(c)
        return b
    }
}
