package com.xperia.leicalook.capture

import android.graphics.Bitmap

/**
 * cameralook NightHighlights.prepare: on night scenes, compress highlights with a hue-preserving
 * shoulder (1.0 -> 0.932) so lamps and neon keep colour instead of clipping.
 */
object NightHighlights {

    fun isNight(bmp: Bitmap): Boolean {
        val w = bmp.width; val h = bmp.height
        val sx = maxOf(1, (w + 255) / 256); val sy = maxOf(1, (h + 255) / 256)
        var sum = 0L; var count = 0; var dark = 0; var bright = 0
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p = row[x]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                val lum = Math.round(0.2126f * r + 0.7152f * g + 0.0722f * b)
                sum += lum; count++
                if (lum < 77) dark++
                if (maxOf(r, maxOf(g, b)) >= 204) bright++
                x += sx
            }
            y += sy
        }
        return count > 0 && dark >= 0.65f * count && sum < 76.5f * count && bright > 0 && bright <= 0.15f * count
    }

    private fun shoulder(x: Float): Float =
        if (x <= 0.72f) x else { val t = (x - 0.72f) / 0.28f; 0.72f + 0.28f * t / (1f + 0.32f * t) }

    fun apply(bmp: Bitmap) {
        if (!bmp.isMutable || !isNight(bmp)) return
        val k = FloatArray(256) { m -> if (m > 183) { val s = m / 255f; shoulder(s) / s } else 1f }
        val w = bmp.width
        val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = row[x]
                val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
                val m = maxOf(r, maxOf(g, b))
                if (m > 183) {
                    val f = k[m]
                    row[x] = (p and -0x1000000) or (Math.round(r * f) shl 16) or (Math.round(g * f) shl 8) or Math.round(b * f)
                }
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }
}
