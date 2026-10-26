package com.xperia.leicalook.look

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Xiaomi/Leica "leica_filter_param_*.bin" (docs/spec_fullm9.md §5, docs/spec_donor120.md §5).
 *
 *   u16 nScenes; u16 sceneBlockSize[nScenes]; u16 sceneOff(0x400); u16 lutOff(0x1000); u16 lutDim(17);
 *   u16 nZooms; u16 zoomBlockSize[nZooms]
 *   @sceneOff: per scene  char name[64] + TREE
 *   after the scenes: per zoom  f32 zoom + TREE  (indexes shading records)
 *   TREE := u16 nLux { u16 lo, hi, nCct { u16 lo, hi, index } }
 *   LUTs @lutOff: 17^3 entries, index ((r*17+g)*17+b)*3 (B fastest), bytes (B, G, R)
 *   shading records after the LUTs: 8 x f32 each
 */
class LeicaBank(private val raw: ByteArray) {

    class Bin(val lo: Int, val hi: Int, val index: Int, val sub: List<Bin>)

    val grid: Int
    private val scenes = LinkedHashMap<String, List<Bin>>()
    private val zooms = ArrayList<Float>()
    private val zoomTrees = ArrayList<List<Bin>>()
    private val lutOff: Int
    val lutCount: Int
    private val shadeOff: Int
    private val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    init {
        fun u16(o: Int) = bb.getShort(o).toInt() and 0xffff
        val nScenes = u16(0)
        var h = 2
        val sceneSizes = IntArray(nScenes) { u16(h).also { h += 2 } }
        val sceneOff = u16(h); h += 2
        lutOff = u16(h); h += 2
        grid = u16(h); h += 2
        val nZooms = u16(h); h += 2
        val zoomSizes = IntArray(nZooms) { u16(h).also { h += 2 } }

        fun tree(start: Int): List<Bin> {
            var q = start
            fun r() = u16(q).also { q += 2 }
            return List(r()) {
                val lo = r(); val hi = r()
                val sub = List(r()) { Bin(r(), r(), r(), emptyList()) }
                Bin(lo, hi, -1, sub)
            }
        }
        var p = sceneOff
        var maxIndex = 0
        for (i in 0 until nScenes) {
            val name = String(raw, p, 64, Charsets.US_ASCII).substringBefore('\u0000').trim()
            val t = tree(p + 64)
            t.forEach { l -> l.sub.forEach { maxIndex = maxOf(maxIndex, it.index) } }
            scenes[name] = t
            p += sceneSizes[i]
        }
        for (i in 0 until nZooms) {
            zooms += bb.getFloat(p)
            zoomTrees += tree(p + 4)
            p += zoomSizes[i]
        }
        lutCount = maxIndex + 1
        shadeOff = lutOff + lutCount * grid * grid * grid * 3
    }

    private fun find(list: List<Bin>, v: Int): Pair<Bin, Bin> {
        for ((i, b) in list.withIndex()) if (v <= b.hi) return if (v >= b.lo || i == 0) b to b else list[i - 1] to b
        return list.last() to list.last()
    }

    private fun frac(v: Int, a: Bin, b: Bin): Float = (b.lo - a.hi).let { if (it == 0) 0f else (v - a.hi).toFloat() / it }

    /** Exact LeicaParams.corners: bilinear weights over the lux gap and the CCT gaps. */
    fun corners(tree: List<Bin>, lux: Int, cct: Int): LinkedHashMap<Int, Float> {
        val (l0, l1) = find(tree, lux)
        val (c00, c01) = find(l0.sub, cct)
        val (c10, c11) = find(l1.sub, cct)
        val fl = frac(lux, l0, l1); val f0 = frac(cct, c00, c01); val f1 = frac(cct, c10, c11)
        val m = LinkedHashMap<Int, Float>()
        fun add(i: Int, w: Float) { m[i] = (m[i] ?: 0f) + w }
        add(c00.index, (1 - fl) * (1 - f0)); add(c01.index, (1 - fl) * f0)
        add(c10.index, fl * (1 - f1)); add(c11.index, fl * f1)
        return m
    }

    /**
     * Blended LUT for a scene as standard R-fastest RGB floats in [0,1] (ready for a GL 3D texture),
     * reproducing the native byte truncation of the blend.
     */
    fun lut(scene: String, lux: Int, cct: Int, dst: FloatArray) {
        val w = corners(scenes[scene] ?: scenes["common"]!!, lux, cct)
        val n = grid; val bytes = n * n * n * 3
        for (r in 0 until n) for (g in 0 until n) for (b in 0 until n) {
            val src = ((r * n + g) * n + b) * 3          // native layout: B fastest, bytes B,G,R
            val dstI = ((b * n + g) * n + r) * 3        // GL layout: R fastest, RGB
            for (c in 0 until 3) {
                var acc = 0f
                for ((i, wt) in w) acc += wt * (raw[lutOff + i * bytes + src + (2 - c)].toInt() and 0xff)
                dst[dstI + c] = (if (w.size == 1) acc else acc.toInt().toFloat()) / 255f
            }
        }
    }

    /** 8 shading floats for the zoom/lux/CCT (radial vignette + luma sigmoid parameters). */
    fun shading(zoom: Float, lux: Int, cct: Int): FloatArray {
        val zi = zooms.indexOfLast { zoom >= it }.coerceAtLeast(0)
        val w = corners(zoomTrees[zi], lux, cct)
        return FloatArray(8) { k -> w.entries.sumOf { (i, wt) -> (wt * bb.getFloat(shadeOff + i * 32 + 4 * k)).toDouble() }.toFloat() }
    }

    fun hasShading() = shadeOff + 32 <= raw.size

    companion object {
        fun load(assets: AssetManager, path: String) = LeicaBank(assets.open(path).use { it.readBytes() })
    }
}
