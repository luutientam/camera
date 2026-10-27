package com.xperia.leicalook.look

import android.content.res.AssetManager
import com.xperia.leicalook.camera.SceneEstimator
import com.xperia.leicalook.gl.LookTextures
import com.xperia.leicalook.neural.M9Style
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thread-safe owner of look data shared by the preview and still GL contexts. Producers (camera
 * thread, neural thread) publish new versions; each GL context uploads lazily when it sees a new one.
 */
class LookEngine(private val assets: AssetManager) : LookTextures.Source {

    class BankResult(val version: Int, val shade: FloatArray?)

    private val creative = ConcurrentHashMap<String, Lut3D>()
    private val banks = ConcurrentHashMap<String, LeicaBank>()
    private val bankLuts = ConcurrentHashMap<Int, Lut3D>()
    private val styleMaps = ConcurrentHashMap<Int, M9Style.Maps>()
    private val versions = AtomicInteger(0)
    /** Versions owned by queued stills; never pruned by the preview until [release]. */
    private val pinned = ConcurrentHashMap.newKeySet<Int>()

    @Volatile var bank: BankResult = BankResult(0, null); private set
    @Volatile var currentStyleVersion = 0; private set
    @Volatile var lm9Mu: FloatArray? = null

    private var lastKey: String? = null
    private var lastLux = -1
    private var lastCct = -1
    private var lastZoom = -1f

    fun bank(asset: String): LeicaBank = banks.getOrPut(asset) { LeicaBank.load(assets, asset) }

    override fun creative(asset: String): Lut3D = creative.getOrPut(asset) { Lut3D.fromClu1(assets, asset) }
    override fun bank(version: Int): Lut3D? = bankLuts[version]
    override fun style(version: Int): M9Style.Maps? = styleMaps[version]

    /** Re-blends the preview bank LUT when lux/CCT/zoom moved enough; returns the current result. */
    @Synchronized
    fun updateBank(asset: String?, scene: SceneEstimator.Scene?, zoom: Float): BankResult {
        if (asset == null) return bank
        val lux = (scene?.luxIndex ?: 200f).roundToInt()
        val cct = (scene?.cct ?: 5000f).roundToInt()
        if (asset == lastKey && abs(lux - lastLux) < 3 && abs(cct - lastCct) < 40 && abs(zoom - lastZoom) < 0.05f) return bank
        val r = blend(asset, lux, cct, zoom, still = false)
        bankLuts.keys.filter { it < r.version - 6 && it !in pinned }.forEach { bankLuts.remove(it) }
        lastKey = asset; lastLux = lux; lastCct = cct; lastZoom = zoom
        bank = r
        return r
    }

    /** One-off blend for a still (its own exposure/AWB); kept until replaced by later versions. */
    fun blend(asset: String, lux: Int, cct: Int, zoom: Float, still: Boolean = true): BankResult {
        val b = bank(asset)
        val data = FloatArray(b.grid * b.grid * b.grid * 3)
        b.lut("common", lux, cct, data)
        val v = versions.incrementAndGet()
        if (still) pinned += v
        bankLuts[v] = Lut3D.fromFloats(b.grid, data)
        return BankResult(v, if (b.hasShading()) b.shading(zoom, lux, cct) else null)
    }

    fun publishStyle(maps: M9Style.Maps): Int {
        val v = versions.incrementAndGet()
        styleMaps[v] = maps
        styleMaps.keys.filter { it < v - 6 && it != currentStyleVersion && it !in pinned }.forEach { styleMaps.remove(it) }
        currentStyleVersion = v
        return v
    }

    /** Registers maps for a single still without changing the preview's current version. */
    fun publishStillStyle(maps: M9Style.Maps): Int {
        val v = versions.incrementAndGet()
        pinned += v
        styleMaps[v] = maps
        return v
    }

    fun release(version: Int) {
        pinned -= version
        styleMaps.remove(version)
        bankLuts.remove(version)
    }

    fun toneCount(): Int = assets.list("looks")?.count { it.startsWith("tone-") && it.endsWith(".lut") } ?: 0

    companion object {
        /**
         * Lm9Stats probe: classifies pixels of an upright RGBA8 frame into sky / vegetation / other and
         * returns the per-class mean RGB in 0..1 (9 floats), exactly like the port's 320 px probe.
         */
        fun lm9Means(rgba: java.nio.ByteBuffer, w: Int, h: Int): FloatArray {
            val sum = DoubleArray(9); val n = IntArray(3)
            for (i in 0 until w * h) {
                val r = rgba.get(i * 4).toInt() and 0xff
                val g = rgba.get(i * 4 + 1).toInt() and 0xff
                val b = rgba.get(i * 4 + 2).toInt() and 0xff
                val y = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0
                val cls = if (b > g + 10 && b > r + 20 && y > 0.35) 0 else if (g > r + 8 && g > b + 8) 1 else 2
                sum[cls * 3] += r.toDouble(); sum[cls * 3 + 1] += g.toDouble(); sum[cls * 3 + 2] += b.toDouble(); n[cls]++
            }
            return FloatArray(9) { (sum[it] / maxOf(n[it / 3], 1) / 255.0).toFloat() }
        }
    }
}
