package com.celica.dash.ui.setup

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.celica.dash.apps.RootShell
import com.celica.dash.apps.UnitProfile

/**
 * Everything the dash needs granting, asked for in the app rather than by a root shell from
 * the PC. Each entry knows whether it is granted and how to ask; the Setup wizard and the
 * Settings page show them as rows. Where the unit's own door is open (root), the grants that
 * only root can make - the overlay appop, the doze whitelist, compiling the dash to native -
 * are made quietly on the way through.
 */
object Permissions {
    enum class Kind { LOCATION, BLUETOOTH, STORAGE, OVERLAY, NOTIFICATIONS, INSTALL, HOME }

    class Item(val kind: Kind, val title: String, val why: String, val optional: Boolean = false)

    val items: List<Item> = listOf(
        Item(Kind.LOCATION, "Location", "the unit's own GPS, for speed, the map and the weather"),
        Item(Kind.BLUETOOTH, "Bluetooth", "the OBD dongle, the phone's GPS, and pairing from the Phone tab"),
        Item(Kind.STORAGE, "Music on the unit", "the songs Setup copies over, played by the dash itself", optional = true),
        Item(Kind.OVERLAY, "Draw over other apps", "the curtain over the phone's handshake, so the vendor's page never shows", optional = true),
        Item(Kind.NOTIFICATIONS, "Notification access", "what is playing over Bluetooth, and the buttons to drive it", optional = true),
        Item(Kind.INSTALL, "Install updates", "later builds fetched from the PC helper", optional = true),
        Item(Kind.HOME, "Home app", "the dash is what the unit boots into"),
    )

    /** The runtime permissions a plain request dialog can grant. */
    fun runtime(kind: Kind): Array<String> = when (kind) {
        Kind.LOCATION -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        Kind.BLUETOOTH -> if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else emptyArray()
        Kind.STORAGE -> if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> emptyArray()
    }

    fun granted(ctx: Context, kind: Kind): Boolean = when (kind) {
        Kind.LOCATION, Kind.BLUETOOTH, Kind.STORAGE -> runtime(kind).all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        Kind.OVERLAY -> Settings.canDrawOverlays(ctx)
        Kind.NOTIFICATIONS -> NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
        Kind.INSTALL -> ctx.packageManager.canRequestPackageInstalls()
        Kind.HOME -> isHome(ctx)
    }

    /** The settings page that grants what a dialog cannot; null where a dialog does. */
    fun settingsIntent(ctx: Context, kind: Kind): Intent? = when (kind) {
        Kind.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
        Kind.NOTIFICATIONS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        Kind.INSTALL -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
        Kind.HOME -> homeIntent(ctx)                  // no NEW_TASK: the role request must know who asked
        else -> null
    }?.let { if (kind == Kind.HOME) it else it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

    fun isHome(ctx: Context): Boolean {
        val r = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        return r?.activityInfo?.packageName == ctx.packageName
    }

    /** Android 10 and up can ask for the Home role outright; before that, the chooser page. */
    private fun homeIntent(ctx: Context): Intent {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) return rm.createRequestRoleIntent(RoleManager.ROLE_HOME)
        }
        return Intent(Settings.ACTION_HOME_SETTINGS)
    }

    /**
     * What root can do for the dash on a unit whose door is open: the overlay appop without a
     * settings page, the doze whitelist so the link and the recording live through a long idle,
     * and the dash compiled to native so a cold start is a second rather than nine. Silent; no
     * harm if any of it fails or there is no root.
     */
    fun rootExtras(ctx: Context): String? {
        if (!UnitProfile.root) return null
        val pkg = ctx.packageName
        return RootShell.run(
            "appops set $pkg SYSTEM_ALERT_WINDOW allow; pm grant $pkg android.permission.READ_EXTERNAL_STORAGE; " +
                "dumpsys deviceidle whitelist +$pkg; cmd package compile -m speed -f $pkg >/dev/null 2>&1; echo done", 120_000,
        )
    }

    /** Opens the page for a permission a dialog cannot grant. */
    fun ask(activity: Activity, kind: Kind) {
        val i = settingsIntent(activity, kind) ?: return
        // the Home role dialog is only shown to a caller it can name, which startActivityForResult provides
        @Suppress("DEPRECATION")
        runCatching { if (kind == Kind.HOME) activity.startActivityForResult(i, 7) else activity.startActivity(i) }
    }
}
