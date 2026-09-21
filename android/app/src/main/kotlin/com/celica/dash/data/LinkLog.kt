package com.celica.dash.data

import com.celica.dash.data.Units
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * What the links did, and when: the dongle coming and going, the phone's GPS link, the audio
 * session, CarPlay. Kept in memory, the last sixty, shown on the diagnostics page. A drop in
 * the car is only ever noticed after the fact, and "when, and what else was happening" is the
 * whole of the question - so every source notes its turns here, and the page has the answer.
 */
object LinkLog {
    private val clock get() = Units.date("HH:mm:ss")
    private val lines = ArrayDeque<String>()
    private var last: String? = null

    /** One line; the same line twice in a row is not written twice. */
    @Synchronized
    fun note(source: String, what: String) {
        val key = "$source: $what"
        if (key == last) return
        last = key
        lines.addFirst("${clock.format(Date())} $key")
        while (lines.size > 60) lines.removeLast()
    }

    /** Newest first. */
    @Synchronized
    fun recent(n: Int = 60): List<String> = lines.take(n)
}
