package com.xperia.leicalook.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/**
 * Rebuilds the two scene signals the Xiaomi pipeline reads from vendor tags (Qualcomm "lux index"
 * and AWB CCT) from standard Camera2 metadata, so the Leica LUT banks can be indexed on any device.
 */
class SceneEstimator(chars: CameraCharacteristics) {

    data class Scene(val luxIndex: Float, val cct: Float, val ev100: Float, val iso: Int, val exposureNs: Long)

    private val cm1 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1)?.toMatrix()
    private val cm2 = chars.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2)?.toMatrix()
    private val cc1 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1)?.toMatrix()
    private val cc2 = chars.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2)?.toMatrix()
    private val ill1 = illuminantCct(chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1)?.toInt())
    private val ill2 = illuminantCct(chars.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2)?.toInt())
    private val defaultAperture = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull() ?: 1.8f

    // Exponential smoothing so LUT choice does not flicker frame to frame.
    private var smLux = Float.NaN
    private var smCct = Float.NaN

    fun update(r: CaptureResult): Scene? {
        val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val boost = (r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100) / 100f
        val n = r.get(CaptureResult.LENS_APERTURE) ?: defaultAperture
        val t = exp / 1e9
        val ev100 = (log2(n * n / t) - log2(iso * boost / 100.0)).toFloat()
        val lux = luxIndexFromEv(ev100)
        val cct = cctFrom(r) ?: smCct.takeIf { !it.isNaN() } ?: 5000f
        smLux = if (smLux.isNaN()) lux else smLux + (lux - smLux) * 0.15f
        smCct = if (smCct.isNaN()) cct else smCct + (cct - smCct) * 0.15f
        return Scene(smLux, smCct, ev100, iso, exp)
    }

    /** Un-smoothed estimate for a single still capture. */
    fun estimate(r: CaptureResult): Scene? {
        val exp = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return null
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return null
        val boost = (r.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100) / 100f
        val n = r.get(CaptureResult.LENS_APERTURE) ?: defaultAperture
        val ev100 = (log2(n * n / (exp / 1e9)) - log2(iso * boost / 100.0)).toFloat()
        val cct = cctFrom(r) ?: smCct.takeIf { !it.isNaN() } ?: 5000f
        return Scene(luxIndexFromEv(ev100), cct, ev100, iso, exp)
    }

    private fun cctFrom(r: CaptureResult): Float? {
        val neutral = r.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT)?.map { it.toDouble() }?.toDoubleArray()
            ?: r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let {
                doubleArrayOf(1.0 / it.red, 2.0 / (it.greenEven + it.greenOdd), 1.0 / it.blue)
            } ?: return null
        if (cm1 == null) return null
        // DNG spec: iterate CCT -> interpolated XYZ->camera matrix -> xy -> CCT.
        var cct = 5000.0
        repeat(6) {
            val m = interpolate(cct)
            val xyz = solve(m, neutral) ?: return null
            val sum = xyz[0] + xyz[1] + xyz[2]
            if (sum <= 1e-9) return null
            val next = mccamy(xyz[0] / sum, xyz[1] / sum).coerceIn(1800.0, 12000.0)
            if (abs(next - cct) < 5) return next.toFloat()
            cct = next
        }
        return cct.toFloat().takeIf { it.isFinite() }
    }

    private fun interpolate(cct: Double): DoubleArray {
        val a = mul(cc1, cm1!!)
        val b = cm2?.let { mul(cc2, it) } ?: return a
        val t1 = ill1; val t2 = ill2
        if (t1 == null || t2 == null || t1 == t2) return a
        val lo = minOf(t1, t2); val hi = maxOf(t1, t2)
        val w = when {
            cct <= lo -> 1.0
            cct >= hi -> 0.0
            else -> (1.0 / cct - 1.0 / hi) / (1.0 / lo - 1.0 / hi)
        }
        val (mLo, mHi) = if (t1 < t2) a to b else b to a
        return DoubleArray(9) { mLo[it] * w + mHi[it] * (1 - w) }
    }

    companion object {
        /**
         * Qualcomm lux index steps by ~1.03x of exposure per unit (one EV ~= 23.45 units).
         * Anchored so that bright daylight (EV 15) lands ~120 and EV 9.4 lands on the
         * 260 day/indoor threshold used by the Leica style models.
         */
        fun luxIndexFromEv(ev100: Float): Float =
            (120f + (15f - ev100) * (1f / log2(1.03f))).coerceIn(0f, 999f)

        private fun ColorSpaceTransform.toMatrix(): DoubleArray {
            val out = DoubleArray(9)
            for (i in 0 until 9) out[i] = getElement(i % 3, i / 3).toDouble()
            return out
        }

        private fun mul(a: DoubleArray?, b: DoubleArray): DoubleArray {
            if (a == null) return b
            return DoubleArray(9) { i ->
                val r = i / 3; val c = i % 3
                a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
            }
        }

        private fun solve(m: DoubleArray, v: DoubleArray): DoubleArray? {
            val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
            if (abs(det) < 1e-12) return null
            val inv = doubleArrayOf(
                (m[4] * m[8] - m[5] * m[7]) / det, (m[2] * m[7] - m[1] * m[8]) / det, (m[1] * m[5] - m[2] * m[4]) / det,
                (m[5] * m[6] - m[3] * m[8]) / det, (m[0] * m[8] - m[2] * m[6]) / det, (m[2] * m[3] - m[0] * m[5]) / det,
                (m[3] * m[7] - m[4] * m[6]) / det, (m[1] * m[6] - m[0] * m[7]) / det, (m[0] * m[4] - m[1] * m[3]) / det,
            )
            return DoubleArray(3) { r -> inv[r * 3] * v[0] + inv[r * 3 + 1] * v[1] + inv[r * 3 + 2] * v[2] }
        }

        private fun mccamy(x: Double, y: Double): Double {
            val n = (x - 0.3320) / (0.1858 - y)
            return 449 * n.pow(3) + 3525 * n * n + 6823.3 * n + 5520.33
        }

        private fun illuminantCct(code: Int?): Double? = when (code) {
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_A,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_TUNGSTEN -> 2856.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_ISO_STUDIO_TUNGSTEN -> 3200.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_COOL_WHITE_FLUORESCENT -> 4150.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FLUORESCENT,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_WHITE_FLUORESCENT -> 3450.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT_FLUORESCENT -> 6430.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAY_WHITE_FLUORESCENT -> 5050.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_B -> 4874.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_STANDARD_C -> 6774.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D50 -> 5003.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D55,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_DAYLIGHT,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FINE_WEATHER,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_FLASH -> 5503.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D65,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_CLOUDY_WEATHER -> 6504.0
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_D75,
            CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1_SHADE -> 7504.0
            else -> null
        }
    }
}
