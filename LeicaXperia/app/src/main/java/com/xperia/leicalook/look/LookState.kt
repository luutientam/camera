package com.xperia.leicalook.look

/**
 * Everything the look shader needs for one frame. Immutable; the UI swaps in a new instance.
 * Stage order in the shader follows the port's LeicaEngine chains:
 *   M9 distill (affine maps) -> Leica bank LUT -> warm(density EV, neutral warmth) -> M9+ boost matrix
 *   -> L-M9 stats curves -> cameralook tone -> M3 mono curve -> user intensity mix
 *   -> cameralook film | Madrid vignette/grain | M3 grain
 */
data class LookState(
    /** Overall user intensity: mix(original, graded). */
    val amount: Float = 0f,
    val styleVersion: Int = 0,
    val style: Boolean = false,
    val bankKey: String? = null,
    val bankVersion: Int = 0,
    val warmEv: Float = 0f,
    val warmNeutral: Float = 0f,
    /** M9+ boostSaturationContrast: saturation, contrast, warm (0 = off). */
    val boostSat: Float = 1f,
    val boostContrast: Float = 1f,
    val boostWarm: Float = 0f,
    /** L-M9: per-class (sky, vegetation, other) mean RGB of the frame, or null when off. */
    val lm9Mu: FloatArray? = null,
    val lutAsset: String? = null,
    val tone: ToneParams = ToneParams.NEUTRAL,
    val m3: Boolean = false,
    val madridGrain: Float = 0f,
    val madridVignette: Float = 0f,
    /** Crop inside the frame: (u0, v0, u1, v1) in output space. */
    val crop: FloatArray = floatArrayOf(0f, 0f, 1f, 1f),
) {
    companion object {
        val BYPASS = LookState()
    }
}

/** cameralook ToneMath.Params, values exactly as decoded from ToneCatalog. Curves are (x, y) pairs in 0..255. */
data class ToneParams(
    val strength: Float = 1f,
    val grain: Float = 0f, val shadows: Float = 0f, val midtones: Float = 0f, val highlights: Float = 0f,
    val grainSize: Float = 0f, val roughness: Float = 0f, val colorVariation: Float = 0f,
    val vignette: Float = 0f, val midpoint: Float = 50f, val clarity: Float = 0f,
    val luma: FloatArray = ID, val rgb: FloatArray = ID,
    val seed: Int = 0,
) {
    val hasFilm get() = grain != 0f || vignette != 0f || clarity != 0f

    companion object {
        val ID = floatArrayOf(0f, 0f, 255f, 255f)
        val NEUTRAL = ToneParams()
    }
}
