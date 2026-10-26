package com.xperia.leicalook.look

import android.content.res.AssetManager
import com.xperia.leicalook.camera.SceneEstimator
import com.xperia.leicalook.gl.LookTextures
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Thread-safe owner of LUT data. The camera thread feeds scene estimates; when the Leica bank
 * selection moves it re-blends a 17^3 LUT (cheap) and bumps a version that both GL contexts pick up.
 */
class LookEngine(private val assets: AssetManager) : LookTextures.LutSource {

    private val creative = ConcurrentHashMap<String, Lut3D>()
    private val banks = ConcurrentHashMap<String, LeicaBank>()
    private val bankLuts = ConcurrentHashMap<Int, Lut3D>()
    private val styleLuts = ConcurrentHashMap<Int, Lut3D>()
    private val version = AtomicInteger(0)
    private val styleVersionGen = AtomicInteger(0)

    @Volatile var currentBankVersion = 0; private set
    @Volatile var currentStyleVersion = 0; private set
    private var lastKey: String? = null
    private var lastLux = -1f
    private var lastCct = -1f

    fun bank(asset: String): LeicaBank = banks.getOrPut(asset) { LeicaBank.load(assets, asset) }

    override fun creative(asset: String): Lut3D = creative.getOrPut(asset) { Lut3D.fromClu1(assets, asset) }

    override fun bank(version: Int): Lut3D? = bankLuts[version]

    override fun style(version: Int): Lut3D? = styleLuts[version]

    /** Returns the bank version to render for [look] at [scene]; re-blends only when it moved enough. */
    @Synchronized
    fun updateBank(look: Look, scene: SceneEstimator.Scene?): Int {
        val asset = look.bank ?: return 0
        val lux = scene?.luxIndex ?: 200f
        val cct = scene?.cct ?: 5000f
        val key = asset + "#" + look.bankScene
        if (key == lastKey && abs(lux - lastLux) < 4f && abs(cct - lastCct) < 60f) return currentBankVersion
        val lut = blendBank(asset, look.bankScene, lux, cct)
        val v = version.incrementAndGet()
        bankLuts[v] = lut
        bankLuts.keys.filter { it < v - 4 }.forEach { bankLuts.remove(it) }
        lastKey = key; lastLux = lux; lastCct = cct
        currentBankVersion = v
        return v
    }

    fun blendBank(asset: String, scene: String, lux: Float, cct: Float): Lut3D {
        val b = bank(asset)
        val w = b.weights(b.scene(scene), lux, cct)
        val data = FloatArray(b.grid * b.grid * b.grid * 3)
        b.blend(w, data)
        return Lut3D.fromFloats(b.grid, data)
    }

    /** Registers a one-off bank LUT (blended from a still's own metadata) and returns its version. */
    fun publishBankForStill(lut: Lut3D): Int {
        val v = version.incrementAndGet()
        bankLuts[v] = lut
        return v
    }

    fun publishStyle(lut: Lut3D): Int {
        val v = styleVersionGen.incrementAndGet()
        styleLuts[v] = lut
        styleLuts.keys.filter { it < v - 4 }.forEach { styleLuts.remove(it) }
        currentStyleVersion = v
        return v
    }

    fun toneCount(): Int = assets.list("looks")?.count { it.startsWith("tone-") && it.endsWith(".lut") } ?: 0
}
