package com.celica.dash.car

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The wireframe car in GLES2, the same drawing as the site's three.js viewer: a solid mesh painted
 * the panel colour writes depth so far-side lines are hidden, then the feature edges go over it.
 * Two draw calls per part. Wheels are their own parts and turn about their axles.
 *
 * Camera is fitted once to a fixed three-quarter angle with the nose to the left. Drag peeks
 * round the car and it settles back. `speed` (mph) spins the wheels at the true rate for a
 * 205/50R16; `reveal` (0..1) draws only that fraction of every part's lines, for the boot-up.
 */
class CarRenderer(private val model: CarModel, private val onNeedsFrame: () -> Unit) : GLSurfaceView.Renderer {

    // ---- public state, written from the UI thread ------------------------------------------
    @Volatile var speedMph = 0f
    @Volatile var reveal = 1f
    @Volatile var background = floatArrayOf(0.075f, 0.071f, 0.086f)     // Ink.s1
    @Volatile var lineColor = floatArrayOf(0.945f, 0.929f, 0.918f, 0.92f)
    /** Clear to nothing so whatever is under the surface shows round the car (the map). */
    @Volatile var transparent = false
    /** The road stripes; off when the car is drawn over something that is already a road. */
    @Volatile var road = true
    /** A solid colour for the whole car instead of the panel-and-lines drawing - for when it is small. */
    @Volatile var flat: FloatArray? = null

    /** Points on the car (loaded frame) to project each frame; results go to [onProjected]. */
    @Volatile var hotspots: List<FloatArray> = emptyList()
    /** Per hotspot: x, y in surface pixels, then 1 if it faces the camera else 0. Called on the GL thread. */
    @Volatile var onProjected: ((FloatArray) -> Unit)? = null
    private var surfaceW = 1
    private var surfaceH = 1

    fun drag(dx: Float, dy: Float) {
        targetAz -= dx * 0.008f
        targetEl = (targetEl + dy * 0.005f).coerceIn(-0.1f, 1.2f)
        settleAt = 0L
        onNeedsFrame()
    }
    fun release() { settleAt = SystemClock.uptimeMillis() + 2500 }

    // ---- camera -----------------------------------------------------------------------------
    private var homeAz = (Math.PI - 0.80).toFloat()   // front three-quarter from the other side: nose left
    private var homeEl = 0.24f

    /** A fixed camera instead of the home three-quarter: azimuth and elevation in radians. */
    fun setCamera(az: Float, el: Float) {
        homeAz = az; homeEl = el; targetAz = az; targetEl = el; this.az = az; this.el = el
        fit(); onNeedsFrame()
    }
    @Volatile private var targetAz = homeAz
    @Volatile private var targetEl = homeEl
    private var az = homeAz
    private var el = homeEl
    @Volatile private var settleAt = 0L
    private var dist = 0f
    private var aspect = 16f / 9f
    private val fovDeg = 30f
    private val target = floatArrayOf(0f, model.height * 0.45f, 0f)

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val modelM = FloatArray(16)
    private val tmp = FloatArray(16)

    // ---- GL objects -------------------------------------------------------------------------
    private var program = 0
    private var aPos = 0
    private var aNrm = 0
    private var uMvp = 0
    private var uModel = 0
    private var uColor = 0
    private var uShade = 0
    private val vbo = IntArray(4)
    private var normalsUp = false

    private var wheelAngle = 0f
    private var lastT = 0L
    private val tyreM = com.celica.dash.data.CarSpec.tyreRadiusM

    /** How much of the road is left at this distance down it: gone by the ends, full in the middle. */
    private fun fadeAt(x: Float) = 1f - ((abs(x) - 35f) / (roadHalf - 35f)).coerceIn(0f, 1f)

    // motion: the road is dashes on the ground streaming past at road speed; the body rides on
    // a little suspension. Both fade in with speed so a parked car is a still drawing.
    private val unitsPerMetre = 66.3f / com.celica.dash.data.CarSpec.lengthM     // model length over the real car's
    private var roadOffset = 0f
    private var motion = 0f                               // 0..1, eased
    private var bobT = 0f
    private val dashLen = 6f
    private val dashGap = 14f
    private val roadHalf = 130f
    private val laneHalf = 27f          // a UK lane is ~3.65 m; the car is centred in it
    private val stripeW = 0.75f         // painted line width, ~5 cm at this scale
    private val segLen = 11f            // the lane edge is drawn in lengths, so it can fade out
    private var roadVbo = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = link(VS, FS)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNrm = GLES20.glGetAttribLocation(program, "aNrm")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uShade = GLES20.glGetUniformLocation(program, "uShade")

        val rb = IntArray(1); GLES20.glGenBuffers(1, rb, 0); roadVbo = rb[0]
        // painted stripes as thin quads (GL line width is unreliable): verts 0-3 a centre dash,
        // 4-7 a lane edge the full length of the road, both as triangle strips
        val hw = stripeW / 2
        // 0-3 a centre dash, 4-7 one length of lane edge; the lamp has a buffer of its own
        val verts = floatArrayOf(
            0f, 0.02f, -hw, 0f, 0.02f, hw, dashLen, 0.02f, -hw, dashLen, 0.02f, hw,
            0f, 0.02f, -hw, 0f, 0.02f, hw, segLen, 0.02f, -hw, segLen, 0.02f, hw,
        )
        val road = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        road.position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, roadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, road, GLES20.GL_STATIC_DRAW)

        GLES20.glGenBuffers(4, vbo, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, model.positions.capacity() * 4, model.positions, GLES20.GL_STATIC_DRAW)
        normalsUp = false
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vbo[1])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, model.faces.capacity() * 2, model.faces, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vbo[2])
        GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, model.edges.capacity() * 2, model.edges, GLES20.GL_STATIC_DRAW)

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glLineWidth(1f)
        lastT = SystemClock.uptimeMillis()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        surfaceW = w; surfaceH = h
        aspect = w.toFloat() / max(1, h)
        Matrix.perspectiveM(proj, 0, fovDeg, aspect, 0.5f, 1000f)
        fit()
    }

    /**
     * One camera distance for the home angle only - the car is parked, not turning. Pulled in by
     * K because a bounding box's corners are empty air. (Ported from the mockup viewer.)
     */
    private fun fit() {
        val k = 0.9f
        val e = model.extent
        val corners = ArrayList<FloatArray>(8)
        for (cx in 0..1) for (cy in 0..1) for (cz in 0..1) {
            corners += floatArrayOf((if (cx == 1) e[1] else e[0]) * k, if (cy == 1) e[2] * k else 0f, (if (cz == 1) e[4] else e[3]) * k)
        }
        val ty = tan(Math.toRadians(fovDeg / 2.0)).toFloat(); val tx = ty * aspect
        val d = dirOf(homeAz, homeEl)
        val right = norm(cross(floatArrayOf(0f, 1f, 0f), d)); val up = norm(cross(d, right))
        var worst = 0f
        for (c in corners) {
            val rel = floatArrayOf(c[0] - target[0], c[1] - target[1], c[2] - target[2])
            val depth = dot(rel, d)
            worst = max(worst, max(abs(dot(rel, right)) / tx + depth, abs(dot(rel, up)) / ty + depth))
        }
        dist = worst * 1.04f
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = SystemClock.uptimeMillis()
        val dt = ((now - lastT).coerceAtMost(50)) / 1000f
        lastT = now

        if (settleAt != 0L && now > settleAt) { targetAz = homeAz; targetEl = homeEl }
        az += (targetAz - az) * 0.1f
        el += (targetEl - el) * 0.1f
        val mps = speedMph * 0.44704f
        val omega = mps / tyreM
        if (omega > 0f) wheelAngle -= omega * dt
        // ease the motion cue in and out so it never snaps
        val want = (speedMph / 12f).coerceIn(0f, 1f)
        motion += (want - motion) * (if (want > motion) 0.08f else 0.04f)
        if (motion < 0.005f) motion = 0f
        roadOffset = (roadOffset + mps * unitsPerMetre * dt) % (dashLen + dashGap)
        bobT += dt

        val bg = background
        if (transparent) GLES20.glClearColor(0f, 0f, 0f, 0f) else GLES20.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val d = dirOf(az, el)
        Matrix.setLookAtM(view, 0, target[0] + d[0] * dist, target[1] + d[1] * dist, target[2] + d[2] * dist, target[0], target[1], target[2], 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(program)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[0])
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)

        val rv = reveal.coerceIn(0f, 1f)
        val lc = lineColor
        val solid = flat
        if (solid != null) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo[3])
            if (!normalsUp) {
                // the solid drawing is lit, so it wants normals; the line drawing never does
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, model.normals.capacity() * 4, model.normals, GLES20.GL_STATIC_DRAW)
                normalsUp = true
            }
            GLES20.glEnableVertexAttribArray(aNrm)
            GLES20.glVertexAttribPointer(aNrm, 3, GLES20.GL_FLOAT, false, 0, 0)
        } else {
            GLES20.glDisableVertexAttribArray(aNrm)
            GLES20.glVertexAttrib3f(aNrm, 0f, 1f, 0f)
        }
        for (p in model.parts) {
            // model matrix: identity, or a turn about the axle (the z axis through the pivot)
            Matrix.setIdentityM(modelM, 0)
            if (p.pivot == null && motion > 0f) {
                // two slow sines that never quite repeat; a hair of pitch with it
                val bob = motion * (0.07f * sin(bobT * 2.9f) + 0.04f * sin(bobT * 4.7f + 1.3f))
                val pitch = motion * 0.12f * sin(bobT * 2.1f)
                Matrix.translateM(modelM, 0, 0f, bob + 0.02f * motion, 0f)
                Matrix.rotateM(modelM, 0, pitch, 0f, 0f, 1f)
            }
            p.pivot?.let { pv ->
                Matrix.translateM(modelM, 0, pv[0], pv[1], pv[2])
                Matrix.rotateM(modelM, 0, Math.toDegrees(wheelAngle.toDouble()).toFloat(), 0f, 0f, 1f)
                Matrix.translateM(modelM, 0, -pv[0], -pv[1], -pv[2])
            }
            Matrix.multiplyMM(mvp, 0, vp, 0, modelM, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, modelM, 0)

            // occluder: panel colour, pushed back so lines on the surface win the depth test -
            // or, solid, the lit body itself
            GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glPolygonOffset(1.4f, 1.4f)
            if (solid != null) { GLES20.glUniform4f(uColor, solid[0], solid[1], solid[2], 1f); GLES20.glUniform1f(uShade, 1f) }
            else GLES20.glUniform4f(uColor, bg[0], bg[1], bg[2], 1f)
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vbo[1])
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, p.faceCount * 3, GLES20.GL_UNSIGNED_SHORT, p.faceStart * 3 * 2)
            GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
            GLES20.glUniform1f(uShade, 0f)

            // lines
            val n = (p.edgeCount * rv).toInt() * 2
            if (n > 0 && solid == null) {
                GLES20.glUniform4f(uColor, lc[0], lc[1], lc[2], lc[3])
                GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, vbo[2])
                GLES20.glDrawElements(GLES20.GL_LINES, n, GLES20.GL_UNSIGNED_SHORT, p.edgeStart * 2 * 2)
            }
        }
        // the road, quietly: two solid lane edges and a centre dash streaming toward the tail,
        // all in the hairline grey so the car stays the drawing
        if (motion > 0f && road) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, roadVbo)
            GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
            // everything on the road is drawn in lengths and faded out towards the ends, so the
            // road goes off into the dark rather than stopping at a line
            val place = { atX: Float, atZ: Float, mirror: Boolean ->
                Matrix.setIdentityM(modelM, 0)
                Matrix.translateM(modelM, 0, atX, 0f, atZ)
                if (mirror) Matrix.scaleM(modelM, 0, 1f, 1f, -1f)
                Matrix.multiplyMM(mvp, 0, vp, 0, modelM, 0)
                GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            }
            for (side in floatArrayOf(-laneHalf, laneHalf)) {
                var x = roadHalf
                while (x > -roadHalf) {
                    GLES20.glUniform4f(uColor, 0.6f, 0.58f, 0.56f, motion * fadeAt(x) * 0.22f)
                    place(x - segLen, side, false)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 4, 4)
                    x -= segLen
                }
            }
            val period = dashLen + dashGap
            var x = roadHalf - roadOffset
            while (x > -roadHalf) {
                GLES20.glUniform4f(uColor, 0.6f, 0.58f, 0.56f, motion * fadeAt(x) * 0.3f)
                place(x - dashLen, 0f, false)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                x -= period
            }
        }
        GLES20.glDisableVertexAttribArray(aPos)

        // hotspots -> screen, for the callouts drawn over the surface
        val hs = hotspots
        val cb = onProjected
        if (hs.isNotEmpty() && cb != null) {
            val out = FloatArray(hs.size * 3)
            val v = FloatArray(4); val r = FloatArray(4)
            for (i in hs.indices) {
                val h = hs[i]
                v[0] = h[0]; v[1] = h[1]; v[2] = h[2]; v[3] = 1f
                Matrix.multiplyMV(r, 0, vp, 0, v, 0)
                val ndcX = r[0] / r[3]; val ndcY = r[1] / r[3]
                out[i * 3] = (ndcX * 0.5f + 0.5f) * surfaceW
                out[i * 3 + 1] = (-ndcY * 0.5f + 0.5f) * surfaceH
                // facing: the point is on the camera's side of the car's centre line
                val facing = (h[0] - target[0]) * d[0] + (h[1] - target[1]) * d[1] + (h[2] - target[2]) * d[2]
                out[i * 3 + 2] = if (facing > -2f) 1f else 0f
            }
            cb(out)
        }

        val moving = omega > 0f || motion > 0f || abs(targetAz - az) > 1e-4f || abs(targetEl - el) > 1e-4f || (settleAt != 0L && now <= settleAt)
        if (moving) onNeedsFrame()
    }

    // ---- small vector helpers ---------------------------------------------------------------
    private fun dirOf(a: Float, e: Float) = norm(floatArrayOf(sin(a) * cos(e), sin(e), cos(a) * cos(e)))
    private fun norm(v: FloatArray): FloatArray { val l = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).coerceAtLeast(1e-9f); return floatArrayOf(v[0] / l, v[1] / l, v[2] / l) }
    private fun dot(a: FloatArray, b: FloatArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun link(vs: String, fs: String): Int {
        fun compile(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { "shader: " + GLES20.glGetShaderInfoLog(s) }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "program: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    companion object {
        private const val VS = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNrm;
            varying vec3 vN;
            void main() { gl_Position = uMvp * vec4(aPos, 1.0); vN = mat3(uModel) * aNrm; }
        """
        // lit from high up and a little ahead of the car, half-Lambert so the shadow side still
        // reads as the colour, with a small sheen on the panels facing the light
        private const val FS = """
            precision mediump float;
            uniform vec4 uColor;
            uniform float uShade;
            varying vec3 vN;
            void main() {
                vec3 n = normalize(vN);
                vec3 l = normalize(vec3(0.35, 1.0, 0.55));
                float d = dot(n, l) * 0.5 + 0.5;
                float lit = 0.35 + 0.75 * d * d + 0.25 * pow(max(dot(n, l), 0.0), 24.0);
                vec3 c = mix(uColor.rgb, uColor.rgb * lit, uShade);
                gl_FragColor = vec4(c, uColor.a);
            }
        """
    }
}
