package com.celica.dash.routing

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Spoken turns through whatever text-to-speech engine the unit has, if any. Says each turn
 * twice: once about a third of a mile out with the road name, and once close in. Quiet when
 * there is no engine, and muted with a tap.
 */
class Voice(ctx: Context) {
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private var ready = false
    private var tts: TextToSpeech? = null
    private var spokenFar: Turn? = null
    private var spokenNear: Turn? = null

    private val ctx = ctx
    private var started = false

    /** The engine is only brought up when a route is set - never at boot, never near the music. */
    private fun ensure() {
        if (started) return
        started = true
        runCatching {
            tts = TextToSpeech(ctx) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val r = tts?.setLanguage(com.celica.dash.data.Units.locale)
                    ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                }
            }
        }
    }

    fun toggle() { _enabled.value = !_enabled.value; if (!_enabled.value) stop() }

    fun say(text: String) {
        if (!_enabled.value) return
        ensure()
        if (!ready) return
        runCatching { tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "nav") }
    }

    fun onProgress(st: NavState) {
        val t = st.turn ?: return
        if (t.sign == 4 && st.toTurnM > 60) return
        if (st.toTurnM in 250.0..600.0 && spokenFar !== t) { spokenFar = t; say("In ${fmtSpeechDist(st.toTurnM)}, ${turnWords(t).lowercase()}") }
        if (st.toTurnM < 90.0 && spokenNear !== t) { spokenNear = t; say(turnWords(t)) }
    }

    private fun fmtSpeechDist(m: Double) = com.celica.dash.data.Units.spokenDist(m, 50)

    fun stop() { runCatching { tts?.stop() } }
    fun shutdown() { runCatching { tts?.shutdown() } }
}
