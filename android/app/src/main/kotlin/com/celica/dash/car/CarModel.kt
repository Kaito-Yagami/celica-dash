package com.celica.dash.car

import android.content.Context
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * The car as tools/model.py packs it: one shared vertex array, and per part a run of triangle
 * indices (the occluder) and a run of edge indices (the lines). Wheels carry a pivot.
 *
 * Loaded into the same frame the site viewer uses - X along the car with the nose at +x, Y up
 * with the ground at 0, Z across with the driver's side at +z - so anything measured on the site
 * (hotspots, pivots) means the same thing here.
 */
class CarModel private constructor(
    val positions: FloatBuffer,      // xyz per vertex
    val faces: ShortBuffer,          // 3 per triangle, all parts back to back
    val edges: ShortBuffer,          // 2 per line, all parts back to back
    val parts: List<Part>,
    val extent: FloatArray,          // minX, maxX, maxY, minZ, maxZ  (minY is 0)
) {
    class Part(
        val name: String,
        val faceStart: Int, val faceCount: Int,
        val edgeStart: Int, val edgeCount: Int,
        val pivot: FloatArray?,      // in the loaded frame, or null for a fixed part
    ) {
        val turns get() = pivot != null
    }

    val height get() = extent[2]

    /** Smooth vertex normals from the faces, for the shaded (solid) drawing. Built on first use. */
    val normals: FloatBuffer by lazy {
        val n = positions.capacity()
        val acc = FloatArray(n)
        val p = positions
        for (f in 0 until faces.capacity() / 3) {
            val a = faces.get(f * 3).toInt() and 0xFFFF; val b = faces.get(f * 3 + 1).toInt() and 0xFFFF; val c = faces.get(f * 3 + 2).toInt() and 0xFFFF
            val ax = p.get(a * 3); val ay = p.get(a * 3 + 1); val az = p.get(a * 3 + 2)
            val ux = p.get(b * 3) - ax; val uy = p.get(b * 3 + 1) - ay; val uz = p.get(b * 3 + 2) - az
            val vx = p.get(c * 3) - ax; val vy = p.get(c * 3 + 1) - ay; val vz = p.get(c * 3 + 2) - az
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            for (i in intArrayOf(a, b, c)) { acc[i * 3] += nx; acc[i * 3 + 1] += ny; acc[i * 3 + 2] += nz }
        }
        for (i in 0 until n / 3) {
            val l = Math.sqrt((acc[i * 3] * acc[i * 3] + acc[i * 3 + 1] * acc[i * 3 + 1] + acc[i * 3 + 2] * acc[i * 3 + 2]).toDouble()).toFloat()
            if (l > 1e-9f) { acc[i * 3] /= l; acc[i * 3 + 1] /= l; acc[i * 3 + 2] /= l }
        }
        java.nio.ByteBuffer.allocateDirect(n * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().put(acc).also { it.position(0) }
    }

    companion object {
        @Volatile private var cached: Pair<String, CarModel>? = null

        /** Loaded once per process for the chosen wireframe; Home and Garage both draw the same car. */
        fun get(ctx: Context): CarModel {
            val want = com.celica.dash.data.CarSpec.model
            cached?.let { if (it.first == want) return it.second }
            return synchronized(this) {
                cached?.takeIf { it.first == want }?.second ?: load(ctx, want).also { cached = want to it }
            }
        }

        /** How many triangles the wireframe has, from its metadata alone - for the boot ceremony. */
        fun faces(ctx: Context, variant: String = com.celica.dash.data.CarSpec.model): Int =
            runCatching { JSONObject(ctx.assets.open("model/$variant/car.json").bufferedReader().readText()).getInt("tris") }.getOrDefault(0)

        fun load(ctx: Context, variant: String = com.celica.dash.data.CarSpec.model): CarModel {
            val meta = JSONObject(ctx.assets.open("model/$variant/car.json").bufferedReader().readText())
            val bin = ctx.assets.open("model/$variant/car.bin").readBytes()
            val counts = meta.getJSONObject("counts")
            val nPos = counts.getInt("pos"); val nIdx = counts.getInt("idx"); val nEdg = counts.getInt("edg")
            val u16 = ByteBuffer.wrap(bin).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

            val mn = meta.getJSONArray("min").let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }
            val sp = meta.getJSONArray("span").let { floatArrayOf(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat()) }

            // dequantise straight into the viewing frame: (X, Y, Z)stl -> (X, Z, -Y)
            val nv = nPos / 3
            val raw = FloatArray(nPos)
            var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
            var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            var minZ = Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in 0 until nv) {
                val qx = (u16.get(i * 3).toInt() and 0xFFFF) / 65535f
                val qy = (u16.get(i * 3 + 1).toInt() and 0xFFFF) / 65535f
                val qz = (u16.get(i * 3 + 2).toInt() and 0xFFFF) / 65535f
                val x = mn[0] + qx * sp[0]; val y = mn[2] + qz * sp[2]; val z = -(mn[1] + qy * sp[1])
                raw[i * 3] = x; raw[i * 3 + 1] = y; raw[i * 3 + 2] = z
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y); minZ = min(minZ, z); maxZ = max(maxZ, z)
            }
            // centre in x and z, sit on the ground
            val cx = (minX + maxX) / 2; val cz = (minZ + maxZ) / 2
            for (i in 0 until nv) { raw[i * 3] -= cx; raw[i * 3 + 1] -= minY; raw[i * 3 + 2] -= cz }
            val pos = ByteBuffer.allocateDirect(nPos * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(raw)
            pos.position(0)

            fun slice(offset: Int, n: Int): ShortBuffer {
                val b = ByteBuffer.allocateDirect(n * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
                for (k in 0 until n) b.put(u16.get(offset + k))
                b.position(0); return b
            }
            val faces = slice(nPos, nIdx)
            val edges = slice(nPos + nIdx, nEdg)

            val pj = meta.getJSONArray("parts")
            val parts = (0 until pj.length()).map { i ->
                val p = pj.getJSONObject(i)
                val pivot = p.optJSONArray("pivot")?.let {
                    // same transform as the vertices
                    floatArrayOf(
                        it.getDouble(0).toFloat() - cx,
                        it.getDouble(2).toFloat() - minY,
                        -it.getDouble(1).toFloat() - cz,
                    )
                }
                Part(p.getString("name"), p.getInt("faceStart"), p.getInt("faceCount"), p.getInt("edgeStart"), p.getInt("edgeCount"), pivot)
            }
            return CarModel(pos, faces, edges, parts, floatArrayOf(minX - cx, maxX - cx, maxY - minY, minZ - cz, maxZ - cz))
        }
    }
}
