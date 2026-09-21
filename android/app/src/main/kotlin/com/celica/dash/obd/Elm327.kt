package com.celica.dash.obd

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The ELM327 conversation: AT commands to set the adapter up, then OBD requests as hex, replies
 * read up to the '>' prompt and cleaned of echo, whitespace, "SEARCHING..." and the rest of the
 * chatter clones produce.
 *
 * The 2003 Celica's ECU speaks ISO 9141-2 (or KWP2000 on some), which is slow to wake up: the
 * first request after ATSP0 can take several seconds while the adapter tries protocols, so the
 * timeouts here are generous and the init retries once.
 */
class Elm327(private val io: ObdTransport) {
    private val lock = Mutex()
    var protocol: String? = null
        private set
    var version: String? = null
        private set

    suspend fun init() {
        io.open()
        version = at("ATZ", 3000).lines().lastOrNull { it.startsWith("ELM") }
        at("ATE0")           // no echo
        at("ATL0")           // no linefeeds
        at("ATS0")           // no spaces in hex
        at("ATH0")           // no headers
        at("ATSP0")          // let it find the protocol
        // wake the bus with a request that every ECU answers; this is the slow one
        var ok = false
        repeat(2) { if (!ok) ok = runCatching { query(0x01, 0x00, 8000) }.isSuccess }
        if (!ok) throw ObdException("ECU did not answer")
        protocol = at("ATDPN").trim().trimStart('A')
    }

    fun close() = io.close()

    /** Send an AT command, return the cleaned reply. */
    suspend fun at(cmd: String, timeoutMs: Long = 2000): String = lock.withLock {
        io.write((cmd + "\r").toByteArray())
        readReply(timeoutMs).joinToString("\n")
    }

    /**
     * Mode 01 request for one PID (or mode 03/04 with pid -1). Returns the data bytes after the
     * "41 xx" (or "43") header, or throws when the ECU says NO DATA.
     */
    suspend fun query(mode: Int, pid: Int, timeoutMs: Long = 1500): IntArray = lock.withLock {
        val req = if (pid >= 0) "%02X%02X".format(mode, pid) else "%02X".format(mode)
        io.write((req + "\r").toByteArray())
        val lines = readReply(timeoutMs)
        parseReply(lines, mode, pid)
    }

    private suspend fun readReply(timeoutMs: Long): List<String> {
        val buf = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val chunk = io.read(deadline - System.currentTimeMillis())
            if (chunk.isEmpty()) continue
            buf.append(String(chunk, Charsets.US_ASCII))
            if (buf.indexOf(">") >= 0) break
        }
        if (buf.indexOf(">") < 0) throw ObdException("timeout")
        return clean(buf.toString())
    }

    companion object {
        /** Strip the prompt, blank lines and adapter chatter; keep hex and status words. */
        fun clean(raw: String): List<String> = raw
            .replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING") && it != "BUS INIT: OK" && it != "BUS INIT: ...OK" }

        /**
         * Turn reply lines into data bytes. Handles spaced and unspaced hex, the response header
         * (mode+0x40, then the PID for mode 01), and the usual error words.
         */
        fun parseReply(lines: List<String>, mode: Int, pid: Int): IntArray {
            for (l in lines) {
                val u = l.uppercase()
                if (u.contains("NO DATA") || u.contains("UNABLE TO CONNECT") || u.contains("CAN ERROR") || u.contains("BUS ERROR")) {
                    throw ObdException(u)
                }
            }
            val hex = lines.joinToString("") { it.replace(" ", "") }.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }
            if (hex.length < 2 || hex.length % 2 != 0) throw ObdException("bad reply: $lines")
            val bytes = IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
            val expectHead = mode + 0x40
            var i = bytes.indexOfFirst { it == expectHead }
            if (i < 0) throw ObdException("no response header in $lines")
            i += 1
            if (pid >= 0) {
                if (i >= bytes.size || bytes[i] != pid) throw ObdException("wrong pid in $lines")
                i += 1
            }
            return bytes.copyOfRange(i, bytes.size)
        }
    }
}
