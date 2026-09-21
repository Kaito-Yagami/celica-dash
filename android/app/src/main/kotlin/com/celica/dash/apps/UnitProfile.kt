package com.celica.dash.apps

import android.content.Context

/**
 * What kind of head unit this is, asked once. The dash was written on a QF-firmware unit
 * (Qianfeng: `ro.qf.*`, zlink for CarPlay and Android Auto, the vendor's Bluetooth service,
 * and an adbd that answers as root on 127.0.0.1:9876), and everything that leans on that is
 * gated here rather than tried and logged. On any other unit the answer is "no" and the dash
 * does the plain Android thing instead, saying so where it matters.
 */
object UnitProfile {
    /** The vendor's firmware family: `ro.qf.platform` is set. */
    @Volatile var qf = false; private set
    /** zlink, the projection app the dash knows how to curtain and stop. */
    @Volatile var zlink = false; private set
    /** The vendor's Bluetooth service, for calls and now-playing off the phone. */
    @Volatile var vendorBt = false; private set
    /** A shell that answers as root - the unit's own open door, or nothing. */
    @Volatile var root = false; private set
    @Volatile var probed = false; private set

    /** The cheap answers at once; root takes a socket, so it is asked off the main thread. */
    fun probe(ctx: Context) {
        if (probed) return
        qf = prop("ro.qf.platform").isNotBlank()
        zlink = runCatching { ctx.packageManager.getPackageInfo(Apps.ZLINK, 0) }.isSuccess
        vendorBt = runCatching { ctx.packageManager.getPackageInfo(com.celica.dash.media.QfBluetooth.PKG, 0) }.isSuccess
        probed = true
        Thread { root = RootShell.available() }.start()
    }

    /** One line for the diagnostics page. */
    fun describe(): String =
        "firmware ${if (qf) "QF (${prop("ro.qf.platform")})" else "other"} · zlink ${if (zlink) "present" else "absent"} · vendor bluetooth ${if (vendorBt) "present" else "absent"} · root ${if (root) "yes" else "no"} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE}"

    fun prop(name: String): String = runCatching {
        val c = Class.forName("android.os.SystemProperties")
        c.getMethod("get", String::class.java, String::class.java).invoke(null, name, "") as String
    }.getOrDefault("")
}
