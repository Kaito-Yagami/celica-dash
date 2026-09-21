package com.celica.dash.ui.drive

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File

/**
 * A film playing behind a face, silent and looping. A TextureView rather than a SurfaceView, for
 * the same reason the car is one: on this firmware a surface punched through the window comes up
 * black and takes the strip above it with it.
 *
 * The film is centre-cropped to fill, whatever shape it was made in, so a 16:9 clip sits happily
 * on a 1024x600 screen.
 *
 * `rate` drives it at the engine's pace: a loop of something turning or streaming plays slowly at
 * idle and quickly at the stop, which is the difference between a wallpaper and a window.
 */
@Composable
fun VideoBackdrop(file: File, rate: Float = 1f, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current
    val holder = androidx.compose.runtime.remember(file.path) { Playback(file) }
    androidx.compose.runtime.LaunchedEffect(rate) { holder.rate(rate) }
    DisposableEffect(lifecycle, file.path) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_PAUSE -> holder.pause()
                Lifecycle.Event.ON_RESUME -> holder.resume()
                else -> {}
            }
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs); holder.release() }
    }
    AndroidView(modifier = modifier, factory = { ctx -> holder.view(ctx) })
}

/** Owns the player and the texture it draws on, and keeps the two in step. */
private class Playback(private val file: File) {
    private var player: MediaPlayer? = null
    private var texture: TextureView? = null
    private var videoW = 0
    private var videoH = 0
    private var wanted = true
    private var speed = 1f

    fun view(ctx: Context): TextureView = TextureView(ctx).also { tv ->
        texture = tv
        tv.isOpaque = true
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                start(Surface(st))
                fit()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = fit()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun start(surface: Surface) {
        runCatching {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setSurface(surface)
                isLooping = true
                setVolume(0f, 0f)
                runCatching { playbackParams = playbackParams.setSpeed(speed) }
                setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fit() }
                setOnPreparedListener { if (wanted) it.start() }
                prepareAsync()
            }
        }
    }

    /** Scales the film so it covers the view, cropping whichever way it is the wrong shape. */
    private fun fit() {
        val tv = texture ?: return
        if (videoW <= 0 || videoH <= 0 || tv.width == 0 || tv.height == 0) return
        val scale = maxOf(tv.width.toFloat() / videoW, tv.height.toFloat() / videoH)
        val w = videoW * scale
        val h = videoH * scale
        tv.setTransform(
            Matrix().apply {
                setScale(w / tv.width, h / tv.height)
                postTranslate((tv.width - w) / 2f, (tv.height - h) / 2f)
            },
        )
    }

    /** The engine's pace. Ignored when it has barely moved, since each change is a seek. */
    fun rate(r: Float) {
        val want = r.coerceIn(0.3f, 2.6f)
        if (kotlin.math.abs(want - speed) < 0.04f) return
        speed = want
        val p = player ?: return
        runCatching {
            p.playbackParams = p.playbackParams.setSpeed(want)
            if (!wanted) p.pause()                     // setting the speed starts it on some builds
        }
    }

    fun pause() { wanted = false; runCatching { player?.pause() } }
    fun resume() { wanted = true; runCatching { player?.start() } }
    fun release() { runCatching { player?.release() }; player = null }
}
