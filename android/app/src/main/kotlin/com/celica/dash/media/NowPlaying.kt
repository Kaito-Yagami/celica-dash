package com.celica.dash.media

import android.graphics.Bitmap

/** What is playing, from whichever app or Bluetooth source currently holds the media session. */
data class NowPlaying(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val art: Bitmap? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val positionAt: Long = 0,           // uptime when positionMs was true, for extrapolation
    val source: String? = null,         // package label, e.g. "Spotify" or "Bluetooth audio"
    val needsAccess: Boolean = false,   // notification access not granted yet
    val hasSession: Boolean = false,
)
