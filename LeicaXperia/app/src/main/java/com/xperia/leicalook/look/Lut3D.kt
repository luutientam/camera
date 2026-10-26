package com.xperia.leicalook.look

import android.content.res.AssetManager
import android.opengl.GLES30
import android.util.Half
import com.xperia.leicalook.gl.Gl
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A 3D colour lookup table, R varying fastest, values in [0,1].
 * Stored either as 8-bit RGBA (bank LUTs, which are 8-bit at the source) or half-float RGBA.
 */
class Lut3D private constructor(
    val size: Int,
    private val rgba8: ByteBuffer?,
    private val rgba16f: ByteBuffer?,
) {
    /** Uploads into a new GL_TEXTURE_3D; must be called on a GL thread. */
    fun upload(): Int {
        val tex = Gl.genTexture(GLES30.GL_TEXTURE_3D)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        if (rgba16f != null) {
            rgba16f.position(0)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA16F, size, size, size, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, rgba16f,
            )
        } else {
            rgba8!!.position(0)
            GLES30.glTexImage3D(
                GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGBA8, size, size, size, 0,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba8,
            )
        }
        Gl.check("upload lut $size")
        return tex
    }

    /** Re-uploads into an existing texture of the same size (no reallocation). */
    fun update(tex: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        if (rgba16f != null) {
            rgba16f.position(0)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, rgba16f)
        } else {
            rgba8!!.position(0)
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_3D, 0, 0, 0, 0, size, size, size,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rgba8)
        }
    }

    /** CPU trilinear sample, used by the offline (non-GL) paths and tests. */
    fun sample(r: Float, g: Float, b: Float, out: FloatArray) {
        val n1 = size - 1
        val fr = r.coerceIn(0f, 1f) * n1; val fg = g.coerceIn(0f, 1f) * n1; val fb = b.coerceIn(0f, 1f) * n1
        val r0 = fr.toInt().coerceAtMost(n1 - 1); val g0 = fg.toInt().coerceAtMost(n1 - 1); val b0 = fb.toInt().coerceAtMost(n1 - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        for (c in 0 until 3) {
            fun v(ri: Int, gi: Int, bi: Int) = value(((bi * size + gi) * size + ri) * 4 + c)
            val c00 = v(r0, g0, b0) * (1 - dr) + v(r0 + 1, g0, b0) * dr
            val c10 = v(r0, g0 + 1, b0) * (1 - dr) + v(r0 + 1, g0 + 1, b0) * dr
            val c01 = v(r0, g0, b0 + 1) * (1 - dr) + v(r0 + 1, g0, b0 + 1) * dr
            val c11 = v(r0, g0 + 1, b0 + 1) * (1 - dr) + v(r0 + 1, g0 + 1, b0 + 1) * dr
            out[c] = (c00 * (1 - dg) + c10 * dg) * (1 - db) + (c01 * (1 - dg) + c11 * dg) * db
        }
    }

    private fun value(i: Int): Float =
        if (rgba16f != null) Half.toFloat(rgba16f.getShort(i * 2)) else (rgba8!!.get(i).toInt() and 0xff) / 255f

    companion object {
        private const val HALF_ONE: Short = 0x3C00

        fun identity(n: Int = 17): Lut3D {
            val buf = ByteBuffer.allocateDirect(n * n * n * 4).order(ByteOrder.nativeOrder())
            for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
                buf.put((r * 255 / (n - 1)).toByte()); buf.put((g * 255 / (n - 1)).toByte())
                buf.put((b * 255 / (n - 1)).toByte()); buf.put(0xff.toByte())
            }
            return Lut3D(n, buf, null)
        }

        /** 8-bit RGB triplets, R fastest (Leica filter-param banks). */
        fun fromRgb8(n: Int, src: ByteArray, offset: Int): Lut3D {
            val count = n * n * n
            val buf = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            var p = offset
            repeat(count) {
                buf.put(src[p]); buf.put(src[p + 1]); buf.put(src[p + 2]); buf.put(0xff.toByte())
                p += 3
            }
            return Lut3D(n, buf, null)
        }

        /**
         * Original "CLU1" files: BE magic, BE int N, 3 BE float domain min, 3 BE float domain max,
         * N^3 * 3 BE float, R fastest.
         */
        fun fromClu1(assets: AssetManager, path: String): Lut3D =
            DataInputStream(assets.open(path).buffered(1 shl 16)).use { input ->
                val magic = input.readInt()
                require(magic == 0x434C5531) { "not a CLU1 file: $path" }
                val n = input.readInt()
                val min = FloatArray(3) { input.readFloat() }
                val max = FloatArray(3) { input.readFloat() }
                val count = n * n * n
                val buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder())
                repeat(count) {
                    for (c in 0 until 3) {
                        val v = (input.readFloat() - min[c]) / (max[c] - min[c])
                        buf.putShort(Half.toHalf(v))
                    }
                    buf.putShort(HALF_ONE)
                }
                Lut3D(n, null, buf)
            }

        fun fromFloats(n: Int, rgb: FloatArray): Lut3D {
            val count = n * n * n
            val buf = ByteBuffer.allocateDirect(count * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                buf.putShort(Half.toHalf(rgb[i * 3])); buf.putShort(Half.toHalf(rgb[i * 3 + 1]))
                buf.putShort(Half.toHalf(rgb[i * 3 + 2])); buf.putShort(HALF_ONE)
            }
            return Lut3D(n, null, buf)
        }
    }
}
