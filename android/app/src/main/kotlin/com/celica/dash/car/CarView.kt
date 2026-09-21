package com.celica.dash.car

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.MotionEvent
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

/**
 * The view that hosts [CarRenderer]: a TextureView with its own GL thread rather than a
 * GLSurfaceView. A SurfaceView is a hole in the window that the compositor fills, and on the
 * head unit that hole came up black until touched and blanked what sat above it; a TextureView
 * is an ordinary view, drawn where it is laid out, translucent when asked. Drag-to-peek and
 * 4x MSAA when the GPU offers it, as before.
 */
@SuppressLint("ViewConstructor")
class CarSurface(ctx: Context, model: CarModel, private val interactive: Boolean = true, private val transparent: Boolean = false) :
    TextureView(ctx), TextureView.SurfaceTextureListener {
    val renderer: CarRenderer
    /** The last camera handed to the renderer, so the update pass only sends changes. */
    var camera: Pair<Float, Float>? = null
    private var thread: GlThread? = null
    private var lastX = 0f
    private var lastY = 0f

    init {
        isOpaque = !transparent
        renderer = CarRenderer(model) { requestRender() }
        renderer.transparent = transparent
        surfaceTextureListener = this
    }

    fun requestRender() { thread?.requestRender() }
    fun queueEvent(r: Runnable) { thread?.queue(r) }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        thread = GlThread(st, renderer, width, height, transparent).also { it.start() }
    }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) { thread?.resize(width, height) }
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { thread?.finish(); thread = null; return true }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    // The unit sleeps with the ignition and wakes with it. The texture keeps its last frame
    // through that on paper; on this unit it came back empty, and since frames are only drawn
    // when asked for, it stayed empty until a touch or a change of tab happened to ask. So a
    // frame is asked for whenever the window comes back, or this view does.
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); if (visibility == VISIBLE) requestRender() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) { super.onWindowFocusChanged(hasWindowFocus); if (hasWindowFocus) requestRender() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) requestRender() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; parent?.requestDisallowInterceptTouchEvent(true) }
            MotionEvent.ACTION_MOVE -> { renderer.drag(e.x - lastX, e.y - lastY); lastX = e.x; lastY = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> renderer.release()
        }
        return true
    }
}

/**
 * One thread per surface: sets EGL up on the texture, draws a frame whenever one is asked for
 * (the renderer asks for the next itself while anything moves), and tears down when the
 * texture goes. Events run on the GL thread before the next frame, like GLSurfaceView's.
 */
private class GlThread(
    private val texture: SurfaceTexture, private val renderer: CarRenderer, private var w: Int, private var h: Int, private val alpha: Boolean,
) : Thread("car-gl") {
    private val lock = Object()
    private var wantFrame = true
    private var sizeChanged = true
    private var done = false
    private val events = ArrayDeque<Runnable>()

    fun requestRender() = synchronized(lock) { wantFrame = true; lock.notifyAll() }
    fun queue(r: Runnable) = synchronized(lock) { events.add(r); lock.notifyAll() }
    fun resize(width: Int, height: Int) = synchronized(lock) { w = width; h = height; sizeChanged = true; wantFrame = true; lock.notifyAll() }
    fun finish() { synchronized(lock) { done = true; lock.notifyAll() }; runCatching { join(1500) } }

    override fun run() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return
        val config = chooseConfig(display) ?: return
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        val surface = EGL14.eglCreateWindowSurface(display, config, texture, intArrayOf(EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE || !EGL14.eglMakeCurrent(display, surface, surface, context)) {
            tearDown(display, surface, context); return
        }
        renderer.onSurfaceCreated(null, null)
        while (true) {
            var batch: List<Runnable> = emptyList()
            var resize = false
            val stop = synchronized(lock) {
                while (!done && !wantFrame && events.isEmpty() && !sizeChanged) lock.wait()
                if (!done) {
                    batch = events.toList(); events.clear()
                    resize = sizeChanged; sizeChanged = false
                    wantFrame = false
                }
                done
            }
            if (stop) break
            batch.forEach { runCatching { it.run() } }
            if (resize) renderer.onSurfaceChanged(null, w, h)
            renderer.onDrawFrame(null)
            if (!EGL14.eglSwapBuffers(display, surface)) break
        }
        tearDown(display, surface, context)
    }

    /** 8/8/8 colour (alpha when translucent), 16-bit depth, ES2, 4 samples if offered, else plain. */
    private fun chooseConfig(display: EGLDisplay): EGLConfig? {
        val base = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, if (alpha) 8 else 0,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
        )
        for (attrs in listOf(base + intArrayOf(EGL14.EGL_SAMPLE_BUFFERS, 1, EGL14.EGL_SAMPLES, 4, EGL14.EGL_NONE), base + intArrayOf(EGL14.EGL_NONE))) {
            val out = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (EGL14.eglChooseConfig(display, attrs, 0, out, 0, 1, n, 0) && n[0] > 0) return out[0]
        }
        return null
    }

    private fun tearDown(display: EGLDisplay, surface: EGLSurface?, context: EGLContext?) {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != null && surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}

/**
 * The car in Compose. Loads the model once off the main thread, then hosts the GL surface.
 * `speedMph` turns the wheels; `reveal` draws in the lines (0..1) for the boot-up.
 *
 * `camera` (azimuth, elevation in radians) pins the view instead of the home three-quarter and
 * turns dragging off; with `overlay` the surface is see-through round the car, the road is not
 * drawn and the car is a solid red - the way the replay puts it on the map.
 */
@Composable
fun Car(
    speedMph: Float,
    reveal: Float = 1f,
    modifier: Modifier = Modifier,
    hotspots: List<FloatArray> = emptyList(),
    onProjected: ((FloatArray) -> Unit)? = null,
    camera: Pair<Float, Float>? = null,
    overlay: Boolean = false,
) {
    val ctx = LocalContext.current
    var model by remember { mutableStateOf<CarModel?>(null) }
    LaunchedEffect(Unit) { model = withContext(Dispatchers.IO) { CarModel.get(ctx) } }
    val m = model ?: return
    AndroidView(
        factory = {
            CarSurface(it, m, interactive = camera == null, transparent = overlay).apply {
                renderer.road = !overlay
                // the panel colour follows the look: the tile surface of whichever palette is on
                val bg = com.celica.dash.ui.theme.Ink.s1
                renderer.background = floatArrayOf(bg.red, bg.green, bg.blue)
                val tx = com.celica.dash.ui.theme.Ink.tx
                renderer.lineColor = floatArrayOf(tx.red, tx.green, tx.blue, 0.92f)
                // small on a map, the car is a solid red shape; the lines would only be noise
                if (overlay) renderer.flat = floatArrayOf(0.847f, 0.263f, 0.235f)
            }
        },
        modifier = modifier,
        update = { v ->
            val r = v.renderer
            val changed = r.speedMph != speedMph || r.reveal != reveal || r.hotspots !== hotspots
            r.speedMph = speedMph; r.reveal = reveal; r.hotspots = hotspots
            r.onProjected = onProjected?.let { cb -> { arr -> v.post { cb(arr) } } }
            if (camera != null && v.camera != camera) { v.camera = camera; v.queueEvent { r.setCamera(camera.first, camera.second) } }
            if (changed) v.requestRender()
        },
    )
}
