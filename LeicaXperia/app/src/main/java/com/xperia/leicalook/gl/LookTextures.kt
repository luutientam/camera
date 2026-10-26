package com.xperia.leicalook.gl

import android.opengl.GLES30
import com.xperia.leicalook.look.LookState
import com.xperia.leicalook.look.Lut3D
import com.xperia.leicalook.neural.M9Style
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns the look textures of one GL context. Data comes from [Source] (thread-safe); uploads happen
 * lazily on the GL thread inside [sync] whenever a new version appears.
 */
class LookTextures {

    interface Source {
        fun creative(asset: String): Lut3D
        fun bank(version: Int): Lut3D?
        fun style(version: Int): M9Style.Maps?
    }

    var source: Source? = null

    var bankTex = 0; private set
    var lutTex = 0; private set
    val styleTex = IntArray(4)
    var styleReady = false; private set
    var lutSize = 33f; private set
    var bankSize = 17f; private set

    private var bankVersion = -1
    private var lutAsset: String? = null
    private var styleVersion = -1
    private var styleW = 0; private var styleH = 0; private var resW = 0; private var resH = 0
    private var identity = 0
    private var zero2d = 0

    fun onContextCreated() {
        bankTex = 0; lutTex = 0; identity = 0; zero2d = 0
        styleTex.fill(0); styleReady = false
        bankVersion = -1; lutAsset = null; styleVersion = -1
    }

    fun identityTex(): Int {
        if (identity == 0) identity = Lut3D.identity(2).upload()
        return identity
    }

    fun zeroTex2D(): Int {
        if (zero2d == 0) {
            zero2d = Gl.genTexture(GLES30.GL_TEXTURE_2D)
            val b = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, 1, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, b)
        }
        return zero2d
    }

    fun sync(s: LookState) {
        val src = source ?: return
        if (s.lutAsset != null && s.lutAsset != lutAsset) {
            val lut = src.creative(s.lutAsset)
            Gl.deleteTexture(lutTex)
            lutTex = lut.upload(); lutSize = lut.size.toFloat(); lutAsset = s.lutAsset
        }
        if (s.bankKey != null && s.bankVersion != bankVersion) {
            src.bank(s.bankVersion)?.let { lut ->
                if (bankTex == 0) bankTex = lut.upload() else lut.update(bankTex)
                bankSize = lut.size.toFloat(); bankVersion = s.bankVersion
            }
        }
        if (s.style && s.styleVersion != styleVersion) {
            src.style(s.styleVersion)?.let { uploadStyle(it); styleVersion = s.styleVersion }
        }
    }

    private fun uploadStyle(m: M9Style.Maps) {
        val realloc = styleTex[0] == 0 || m.gridW != styleW || m.gridH != styleH || m.resW != resW || m.resH != resH
        for (k in 0 until 4) {
            if (styleTex[k] == 0) styleTex[k] = Gl.genTexture(GLES30.GL_TEXTURE_2D)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, styleTex[k])
            val w = if (k < 3) m.gridW else m.resW
            val h = if (k < 3) m.gridH else m.resH
            val buf = if (k < 3) m.grid[k] else m.residual
            buf.position(0)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            if (realloc) {
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, w, h, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf)
            } else {
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, buf)
            }
        }
        styleW = m.gridW; styleH = m.gridH; resW = m.resW; resH = m.resH
        styleReady = true
        Gl.check("upload style maps")
    }
}
