package com.xperia.leicalook.gl

import com.xperia.leicalook.look.LookState
import com.xperia.leicalook.look.Lut3D

/**
 * Owns the 3D LUT textures of one GL context. LUT data arrives from other threads through
 * [LutSource]; uploads happen lazily on the GL thread inside [sync].
 */
class LookTextures {

    interface LutSource {
        fun creative(asset: String): Lut3D
        /** Blended bank LUT for the current scene, null when the version is unknown. */
        fun bank(version: Int): Lut3D?
        fun style(version: Int): Lut3D?
    }

    var source: LutSource? = null

    var bankTex = 0; private set
    var lutTex = 0; private set
    var styleTex = 0; private set
    var lutSize = 33f; private set
    var bankSize = 17f; private set
    var styleSize = 17f; private set

    private var bankVersion = -1
    private var lutAsset: String? = null
    private var styleVersion = -1
    private var identity = 0

    fun onContextCreated() {
        bankTex = 0; lutTex = 0; styleTex = 0; identity = 0
        bankVersion = -1; lutAsset = null; styleVersion = -1
    }

    fun identityTex(): Int {
        if (identity == 0) identity = Lut3D.identity(2).upload()
        return identity
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
        if (s.styleAmount > 0f && s.styleVersion != styleVersion) {
            src.style(s.styleVersion)?.let { lut ->
                if (styleTex == 0) styleTex = lut.upload() else lut.update(styleTex)
                styleSize = lut.size.toFloat(); styleVersion = s.styleVersion
            }
        }
    }
}
