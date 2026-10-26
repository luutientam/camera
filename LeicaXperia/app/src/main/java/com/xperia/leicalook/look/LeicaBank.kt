package com.xperia.leicalook.look

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xiaomi/Leica "leica_filter_param_*.bin" bank: per AI-scene tables that pick a 17^3 RGB8 LUT
 * from (lux index, CCT).
 *
 * Layout (little endian u16 unless noted):
 *   [0]      scene count (6)
 *   [1..6]   byte size of each scene block
 *   [7]      offset of the scene blocks (0x400)
 *   [8]      offset of LUT data (0x1000)
 *   [9]      LUT grid size (17)
 *   0x20     ascii "aiscene-lux-cct", 0x120 "uint16_t", 0x220 "0-1023"
 *   scene block: 64 byte name, numLux, { luxLo, luxHi, numCct, { cctLo, cctHi, lutIndex } * numCct } * numLux
 *   LUT data: lutCount * grid^3 * 3 bytes, R fastest
 */
class LeicaBank(private val raw: ByteArray) {

    class CctBin(val lo: Int, val hi: Int, val lut: Int)
    class LuxBin(val lo: Int, val hi: Int, val cct: List<CctBin>)
    class Scene(val name: String, val lux: List<LuxBin>)

    val grid: Int
    val scenes: List<Scene>
    private val dataOffset: Int
    val lutCount: Int

    init {
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val sceneCount = bb.getShort(0).toInt()
        val metaOffset = bb.getShort(14).toInt() and 0xffff
        dataOffset = bb.getShort(16).toInt() and 0xffff
        grid = bb.getShort(18).toInt()
        val list = ArrayList<Scene>(sceneCount)
        var p = metaOffset
        for (i in 0 until sceneCount) {
            val blockSize = bb.getShort(2 + i * 2).toInt() and 0xffff
            val nameBytes = raw.copyOfRange(p, p + 64)
            val name = String(nameBytes, Charsets.US_ASCII).substringBefore('\u0000').trim()
            var q = p + 64
            fun u16(): Int = (bb.getShort(q).toInt() and 0xffff).also { q += 2 }
            val luxBins = ArrayList<LuxBin>()
            repeat(u16()) {
                val lo = u16(); val hi = u16()
                val cct = ArrayList<CctBin>()
                repeat(u16()) { cct += CctBin(u16(), u16(), u16()) }
                luxBins += LuxBin(lo, hi, cct)
            }
            list += Scene(name, luxBins)
            p += blockSize
        }
        scenes = list
        val lutBytes = grid * grid * grid * 3
        lutCount = (raw.size - dataOffset) / lutBytes
    }

    fun scene(name: String): Scene = scenes.firstOrNull { it.name == name } ?: scenes.first()

    /** Weighted LUT indices for a lux index / CCT, bridging gaps between bins linearly. */
    fun weights(scene: Scene, luxIndex: Float, cct: Float): List<Pair<Int, Float>> {
        val out = HashMap<Int, Float>()
        for ((luxBin, wl) in spans(scene.lux, luxIndex, { it.lo }, { it.hi })) {
            for ((cctBin, wc) in spans(luxBin.cct, cct, { it.lo }, { it.hi })) {
                out[cctBin.lut] = (out[cctBin.lut] ?: 0f) + wl * wc
            }
        }
        return out.map { it.key to it.value }
    }

    private inline fun <T> spans(bins: List<T>, x: Float, lo: (T) -> Int, hi: (T) -> Int): List<Pair<T, Float>> {
        if (bins.size == 1) return listOf(bins[0] to 1f)
        if (x <= lo(bins.first())) return listOf(bins.first() to 1f)
        for (i in bins.indices) {
            val b = bins[i]
            if (x >= lo(b) && x <= hi(b)) return listOf(b to 1f)
            if (i + 1 < bins.size) {
                val n = bins[i + 1]
                if (x > hi(b) && x < lo(n)) {
                    val t = (x - hi(b)) / (lo(n) - hi(b)).toFloat()
                    val s = t * t * (3 - 2 * t) // smoothstep keeps the transition visually soft
                    return listOf(b to 1 - s, n to s)
                }
            }
        }
        return listOf(bins.last() to 1f)
    }

    /** Blends the weighted LUTs into [dst] (RGB float, R fastest). */
    fun blend(weights: List<Pair<Int, Float>>, dst: FloatArray) {
        val count = grid * grid * grid
        java.util.Arrays.fill(dst, 0, count * 3, 0f)
        val lutBytes = count * 3
        for ((index, w) in weights) {
            if (w <= 0f) continue
            val base = dataOffset + index.coerceIn(0, lutCount - 1) * lutBytes
            val k = w / 255f
            for (i in 0 until lutBytes) dst[i] += (raw[base + i].toInt() and 0xff) * k
        }
    }

    /** True when every LUT in the bank is (near) identity, e.g. the M3 / M10R banks. */
    fun isIdentity(): Boolean {
        val n1 = grid - 1
        for (l in 0 until lutCount) {
            var p = dataOffset + l * grid * grid * grid * 3
            for (b in 0 until grid) for (g in 0 until grid) for (r in 0 until grid) {
                if (kotlin.math.abs((raw[p].toInt() and 0xff) - (r * 255f / n1)) > 1.5f) return false
                if (kotlin.math.abs((raw[p + 1].toInt() and 0xff) - (g * 255f / n1)) > 1.5f) return false
                if (kotlin.math.abs((raw[p + 2].toInt() and 0xff) - (b * 255f / n1)) > 1.5f) return false
                p += 3
            }
        }
        return true
    }

    companion object {
        fun load(assets: AssetManager, path: String) = LeicaBank(assets.open(path).use { it.readBytes() })
    }
}
