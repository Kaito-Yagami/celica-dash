package com.celica.dash.remote

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * A remote hand on the unit for the PC, since this firmware offers no ADB over the network:
 * while the owner has switched it on in Settings › Advanced, the dash asks the PC's helper for a command every couple
 * of seconds and posts the answer back. Commands are what tools/remote.py sends - diagnostics,
 * the app's own log, a screenshot, media buttons, a broadcast, an activity, a property, a
 * shell command run as the app's own user, a data file to fetch, a setting to change.
 * Nothing here can do more than the app itself can.
 */
class RemoteAgent(
    private val ctx: Context,
    private val host: () -> String,
    private val diagnostics: () -> String,
    private val music: (String) -> Unit,
    private val activity: () -> Activity?,
    private val update: () -> Unit,
    private val go: (String) -> Unit,
    private val fetch: (String) -> Unit,
    private val pref: (String, String) -> String,
) {
    private var running = false
    /** The emulator answers its own queue, so a command for the car never lands on the desk. */
    private val who = if (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.HARDWARE.contains("ranchu")) "?who=emu" else ""

    fun stop() { running = false }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        scope.launch(Dispatchers.IO) {
            while (running) {
                val h = host()
                if (h.isNotBlank()) {
                    runCatching {
                        val c = URL(com.celica.dash.data.Helper.base(h) + "/cmd$who").openConnection() as HttpURLConnection
                        c.connectTimeout = 3000; c.readTimeout = 5000
                        if (c.responseCode == 200) {
                            val id = c.getHeaderField("X-Command-Id") ?: "0"
                            val cmd = c.inputStream.bufferedReader().readText().trim()
                            val out = runCatching { run(cmd) }.getOrElse { ("error: " + it).toByteArray() }
                            post(h, id, out)
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    private fun post(h: String, id: String, body: ByteArray) {
        val c = URL(com.celica.dash.data.Helper.base(h) + "/result/$id").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(body.size); c.connectTimeout = 3000; c.readTimeout = 30000
        c.outputStream.use { it.write(body) }
        c.responseCode
    }

    private suspend fun run(cmd: String): ByteArray {
        val parts = cmd.split(" ").filter { it.isNotEmpty() }
        val args = parts.drop(1)
        return when (parts.firstOrNull()) {
            "diag" -> diagnostics().toByteArray()
            "logcat" -> exec(listOf("logcat", "-d", "-v", "time", "-t", args.firstOrNull() ?: "800"))
            "screenshot" -> screenshot()
            "music" -> { music(args.firstOrNull() ?: "toggle"); "ok".toByteArray() }
            "update" -> { update(); "fetching the build; the installer opens on the unit".toByteArray() }
            "fetch" -> { val what = args.firstOrNull() ?: "search"; fetch(what); "fetching $what".toByteArray() }
            "pref" -> pref(args.firstOrNull() ?: "", args.drop(1).joinToString(" ")).toByteArray()
            "go" -> { go(args.firstOrNull() ?: "home"); "ok".toByteArray() }
            "tap" -> {
                val a = activity() ?: return "no activity".toByteArray()
                val x = args[0].toFloat(); val y = args[1].toFloat()
                val long = args.getOrNull(2) == "long"
                val h = Handler(Looper.getMainLooper())
                h.post {
                    val t = android.os.SystemClock.uptimeMillis()
                    a.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t, android.view.MotionEvent.ACTION_DOWN, x, y, 0))
                    h.postDelayed({
                        val t2 = android.os.SystemClock.uptimeMillis()
                        a.dispatchTouchEvent(android.view.MotionEvent.obtain(t, t2, android.view.MotionEvent.ACTION_UP, x, y, 0))
                    }, if (long) 900 else 60)
                }
                "tapped".toByteArray()
            }
            "broadcast" -> {
                val i = Intent(args.first())
                for (kv in args.drop(1)) {
                    val (k, v) = kv.split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                    v.toIntOrNull()?.let { i.putExtra(k, it) } ?: i.putExtra(k, v)
                }
                ctx.sendBroadcast(i); "sent $i".toByteArray()
            }
            "start" -> {
                val target = args.first()
                val i = if ("/" in target) Intent().setClassName(target.substringBefore("/"), target.substringAfter("/").let { if (it.startsWith(".")) target.substringBefore("/") + it else it })
                else ctx.packageManager.getLaunchIntentForPackage(target) ?: return "no launch intent for $target".toByteArray()
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); "started".toByteArray()
            }
            "prop" -> runCatching {
                val c = Class.forName("android.os.SystemProperties")
                (c.getMethod("get", String::class.java, String::class.java).invoke(null, args.first(), "") as String).toByteArray()
            }.getOrElse { ("error: " + it).toByteArray() }
            "shell" -> exec(listOf("sh", "-c", args.joinToString(" ")))
            "ls" -> (File(args.firstOrNull() ?: "/").listFiles()?.joinToString("\n") { (if (it.isDirectory) "d " else "f ") + it.length() + "  " + it.name } ?: "cannot list").toByteArray()
            "read" -> runCatching { File(args.first()).readBytes().take(200_000).toByteArray() }.getOrElse { ("error: " + it).toByteArray() }
            else -> "unknown command: $cmd".toByteArray()
        }
    }

    private fun exec(cmd: List<String>): ByteArray {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes()
        p.waitFor()
        return out.take(400_000).toByteArray()
    }

    /** The app's window as a PNG (the map's own surface comes out black; the rest is faithful). */
    private suspend fun screenshot(): ByteArray {
        val a = activity() ?: return "no activity".toByteArray()
        val v = a.window.decorView
        val bmp = Bitmap.createBitmap(v.width.coerceAtLeast(1), v.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val ok = suspendCancellableCoroutine<Boolean> { cont ->
            runCatching { PixelCopy.request(a.window, bmp, { r -> cont.resume(r == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper())) }
                .onFailure { cont.resume(false) }
        }
        if (!ok) return "screenshot failed".toByteArray()
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }.toByteArray()
    }
}
