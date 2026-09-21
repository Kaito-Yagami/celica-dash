package com.celica.dash.apps

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A root shell on the unit, through the unit's own adb daemon.
 *
 * This ROM ships with adbd running as root on TCP port 9876, with no authorisation (ro.secure=0,
 * ro.adb.secure=0 - see handoff/boot.md); tools/car.py drives the unit through it from the PC.
 * The dash can knock on the same door from inside, at 127.0.0.1, and that is the only way an
 * ordinary app here can do the few things the CarPlay connector needs done as root: stop zlink
 * dead (`am force-stop`, since its daemon is a foreground service no app may kill) and put the
 * vendor's property straight.
 *
 * What follows is just enough of the adb wire protocol for one command: CNXN, OPEN a `shell:`
 * stream, collect WRTEs, answer each with OKAY, stop at CLSE. Every message is 24 bytes of
 * little-endian header - command, arg0, arg1, length, checksum, magic - and a payload.
 *
 * On any other unit, or if a firmware update ever closes the port, [run] returns null and the
 * callers fall back to what an app can do on its own.
 */
object RootShell {
    private const val HOST = "127.0.0.1"
    private const val PORT = 9876
    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_CLSE = 0x45534c43
    private const val A_WRTE = 0x45545257
    private const val A_AUTH = 0x48545541
    private const val VERSION = 0x01000000
    private const val MAX_DATA = 256 * 1024

    /** The command's output, or null when the door is shut (no adbd, or one that wants a key). */
    fun run(cmd: String, timeoutMs: Int = 8000): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(HOST, PORT), 1500)
            s.soTimeout = timeoutMs
            val out = s.getOutputStream()
            val inp = DataInputStream(s.getInputStream())
            send(out, A_CNXN, VERSION, MAX_DATA, "host::".toByteArray() + 0)
            val hello = recv(inp)
            if (hello.command != A_CNXN) return null       // A_AUTH: not this unit's adbd
            val local = 1
            send(out, A_OPEN, local, 0, "shell:$cmd".toByteArray() + 0)
            var remote = 0
            val text = ByteArrayOutputStream()
            while (true) {
                val m = recv(inp)
                when (m.command) {
                    A_OKAY -> remote = m.arg0
                    A_WRTE -> { text.write(m.data); send(out, A_OKAY, local, m.arg0, ByteArray(0)) }
                    A_CLSE -> { send(out, A_CLSE, local, m.arg0, ByteArray(0)); break }
                    else -> return null
                }
            }
            text.toString("UTF-8")
        }
    }.getOrNull()

    /** Whether the door is open at all: a shell that answers as root. */
    fun available(): Boolean = run("id", 3000)?.contains("uid=0") == true

    private class Msg(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(out: OutputStream, command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command xor -1)
        out.write(h.array()); out.write(data); out.flush()
    }

    private fun recv(inp: InputStream): Msg {
        val raw = ByteArray(24)
        DataInputStream(inp).readFully(raw)
        val h = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = h.int; val arg0 = h.int; val arg1 = h.int; val len = h.int
        val data = ByteArray(len)
        if (len > 0) DataInputStream(inp).readFully(data)
        return Msg(command, arg0, arg1, data)
    }
}
