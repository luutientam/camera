package com.xperia.leicalook.neural

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Half
import android.util.Log
import com.xperia.leicalook.look.LookEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Leica M9 neural style from the port's m9_distill.onnx.
 *
 * The network is an HDRNet-style bilateral affine model:
 *   out = clip( A(x)·rgb + b(x) + clip(R(x), -16, 16), 0, 1 )
 * where A (3x3) and b (3) come from a 12-channel grid at lowres/16, bilinearly resized to full/16 and
 * then to full resolution, and R is a 3-channel residual at lowres/4 resized to full resolution.
 * Only the low-resolution encoder runs in ONNX Runtime (a few ms); the per-pixel application runs in
 * the look shader with GL_LINEAR sampling, which is the same half-pixel bilinear as the ONNX Resize.
 * That makes the exact model usable both in the 60 fps preview and on 12 MP stills (the full graph
 * would need ~1.8 GB on a 12 MP input).
 */
class M9Style private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val engine: LookEngine,
) : AutoCloseable {

    /** Coefficient maps ready for upload: 3 RGBA16F grids (12 channels) + 1 RGBA16F residual. */
    class Maps(
        val gridW: Int, val gridH: Int, val grid: Array<ByteBuffer>,
        val resW: Int, val resH: Int, val residual: ByteBuffer,
    )

    @Volatile var ready = true; private set
    private val lock = Any()
    private var smooth: FloatArray? = null
    private var smoothRes: FloatArray? = null
    private var smoothKey = 0L

    /**
     * Runs the encoder on an upright RGBA8 frame (row 0 = top) and returns maps for an output of
     * [outW] x [outH] pixels. [temporal] blends with the previous result to keep the preview calm.
     */
    fun maps(rgba: ByteBuffer, w: Int, h: Int, outW: Int, outH: Int, temporal: Boolean): Maps? = synchronized(lock) {
        val t0 = System.nanoTime()
        val input = FloatBuffer.allocate(3 * w * h)
        val plane = w * h
        rgba.position(0)
        for (i in 0 until plane) {
            input.put(i, (rgba.get(i * 4).toInt() and 0xff) / 255f)
            input.put(plane + i, (rgba.get(i * 4 + 1).toInt() and 0xff) / 255f)
            input.put(2 * plane + i, (rgba.get(i * 4 + 2).toInt() and 0xff) / 255f)
        }
        val tensor = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong()))
        val (grid, gw, gh, res, rw, rh) = tensor.use {
            session.run(mapOf("lowres" to it)).use { r ->
                @Suppress("UNCHECKED_CAST")
                val g = r[0] as OnnxTensor; val d = r[1] as OnnxTensor
                val gs = g.info.shape; val ds = d.info.shape
                Six(g.floatBuffer.toArray(), gs[3].toInt(), gs[2].toInt(), d.floatBuffer.toArray(), ds[3].toInt(), ds[2].toInt())
            }
        }
        // Stage 1 of the model's two-step resize: grid -> floor(full/16), half-pixel bilinear.
        val sw = maxOf(1, outW / 16); val sh = maxOf(1, outH / 16)
        var g1 = resize(grid, 12, gw, gh, sw, sh)
        var r1 = res
        if (temporal) {
            val key = (sw.toLong() shl 32) or sh.toLong() xor (rw.toLong() shl 48) xor (rh.toLong() shl 16)
            val prev = smooth; val prevR = smoothRes
            if (prev != null && prevR != null && key == smoothKey && prev.size == g1.size && prevR.size == r1.size) {
                for (i in g1.indices) g1[i] = prev[i] + (g1[i] - prev[i]) * 0.35f
                for (i in r1.indices) r1[i] = prevR[i] + (r1[i] - prevR[i]) * 0.35f
            }
            smooth = g1; smoothRes = r1; smoothKey = key
        }
        val maps = Maps(sw, sh, Array(3) { k -> pack(g1, 12, sw, sh, k * 4, 4) }, rw, rh, pack(r1, 3, rw, rh, 0, 3))
        if (DEBUG) Log.d(TAG, "maps ${w}x$h -> ${sw}x$sh in ${(System.nanoTime() - t0) / 1e6} ms")
        return maps
    }

    /** Computes maps for the preview and publishes them through the engine. */
    fun updatePreview(rgba: ByteBuffer, w: Int, h: Int, outW: Int, outH: Int) {
        maps(rgba, w, h, outW, outH, temporal = true)?.let { engine.publishStyle(it) }
    }

    override fun close() {
        ready = false
        synchronized(lock) { session.close() }
    }

    private data class Six(val a: FloatArray, val b: Int, val c: Int, val d: FloatArray, val e: Int, val f: Int)

    companion object {
        private const val TAG = "M9Style"
        private const val DEBUG = false
        /** Long side of the network input; the port feeds the encoder a ~256 px thumbnail. */
        const val LOWRES_LONG = 256

        fun create(context: Context, engine: LookEngine): M9Style? = try {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val bytes = context.assets.open("models/m9_distill_maps.onnx").use { it.readBytes() }
            M9Style(env, env.createSession(bytes, opts), engine)
        } catch (e: Throwable) {
            Log.e(TAG, "ONNX init failed", e)
            null
        }

        fun lowresSize(w: Int, h: Int): Pair<Int, Int> =
            if (w >= h) LOWRES_LONG to maxOf(16, Math.round(LOWRES_LONG * h / w.toFloat()))
            else maxOf(16, Math.round(LOWRES_LONG * w / h.toFloat())) to LOWRES_LONG

        private fun FloatBuffer.toArray(): FloatArray { val a = FloatArray(remaining()); get(a); return a }

        /** ONNX Resize linear, half_pixel, edge-clamped. Layout CHW. */
        fun resize(src: FloatArray, c: Int, iw: Int, ih: Int, ow: Int, oh: Int): FloatArray {
            val out = FloatArray(c * ow * oh)
            val x0 = IntArray(ow); val x1 = IntArray(ow); val fx = FloatArray(ow)
            for (x in 0 until ow) {
                val s = ((x + 0.5f) * iw / ow - 0.5f).coerceIn(0f, (iw - 1).toFloat())
                x0[x] = s.toInt(); x1[x] = minOf(x0[x] + 1, iw - 1); fx[x] = s - x0[x]
            }
            for (y in 0 until oh) {
                val s = ((y + 0.5f) * ih / oh - 0.5f).coerceIn(0f, (ih - 1).toFloat())
                val y0 = s.toInt(); val y1 = minOf(y0 + 1, ih - 1); val fy = s - y0
                for (ch in 0 until c) {
                    val b = ch * iw * ih; val r0 = b + y0 * iw; val r1 = b + y1 * iw
                    val o = ch * ow * oh + y * ow
                    for (x in 0 until ow) {
                        val a = src[r0 + x0[x]] * (1 - fx[x]) + src[r0 + x1[x]] * fx[x]
                        val d = src[r1 + x0[x]] * (1 - fx[x]) + src[r1 + x1[x]] * fx[x]
                        out[o + x] = a * (1 - fy) + d * fy
                    }
                }
            }
            return out
        }

        /** CHW floats -> interleaved RGBA16F (row 0 = top), taking [n] channels from [first]. */
        private fun pack(src: FloatArray, c: Int, w: Int, h: Int, first: Int, n: Int): ByteBuffer {
            val buf = ByteBuffer.allocateDirect(w * h * 8).order(ByteOrder.nativeOrder())
            val plane = w * h
            for (i in 0 until plane) {
                for (k in 0 until 4) {
                    val v = if (k < n && first + k < c) src[(first + k) * plane + i] else 0f
                    buf.putShort(Half.toHalf(v))
                }
            }
            buf.position(0)
            return buf
        }
    }
}
