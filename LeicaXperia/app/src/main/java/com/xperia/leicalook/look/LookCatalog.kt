package com.xperia.leicalook.look

enum class Pipeline { NONE, M9, M9_PLUS, M10R, LM9, M3, TONE }

/** One selectable look in the strip. */
data class Look(
    val id: String,
    val name: String,
    val subtitle: String,
    val thumb: String?,
    val pipeline: Pipeline,
    val bank: String? = null,
    val lut: String? = null,
    val tone: ToneParams = ToneParams.NEUTRAL,
    val defaultIntensity: Float = 1f,
) {
    val neural get() = pipeline == Pipeline.M9 || pipeline == Pipeline.M9_PLUS || pipeline == Pipeline.M10R
    val needsStats get() = pipeline == Pipeline.LM9
}

object LookCatalog {

    private fun c(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }

    /** cameralook ToneCatalog (decoded): name, strength, grain, shadows, midtones, highlights, size, roughness, colorVar, vignette, midpoint, clarity. */
    private val tones: List<Triple<String, String, ToneParams>> = listOf(
        Triple("FUJI CC II", "Teal-green, warm buildings", ToneParams(1.50f, 20f, 30f, 30f, 15f, 10f, 15f, 0f, -20f, 25f, 0f)),
        Triple("PEARL", "Bright, soft light tones", ToneParams(1.55f, 0f, 0f, 0f, 0f, 30f, 30f, 0f, 0f, 50f, 0f)),
        Triple("POCKET", "Clear, airy everyday colour", ToneParams(1.50f, 0f, 0f, 0f, 0f, 30f, 30f, 0f, 0f, 50f, 0f)),
        Triple("FUJI NN", "Nostalgic negative", ToneParams(1.45f, 30f, 30f, 50f, 15f, 10f, 15f, 10f, -45f, 30f, 0f, luma = c(0, 0, 78, 85, 135, 137, 255, 255))),
        Triple("FUJI NC", "Classic negative, stronger grain", ToneParams(1.35f, 60f, 30f, 50f, 15f, 10f, 15f, 10f, -45f, 30f, 0f)),
        Triple("RICOH POSITIVE", "Vivid colour, crisp contrast", ToneParams(1.40f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 50f, 0f)),
        Triple("RICOH NEGATIVE", "Soft negative colour", ToneParams(1.35f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 50f, 0f)),
        Triple("ALL-ROUND 400", "Balanced film tones & grain", ToneParams(1.40f, 30f, 30f, 50f, 15f, 10f, 15f, 10f, -10f, 50f, 0f)),
        Triple("HASSELBLAD BLUE", "Blue gradation, soft texture", ToneParams(1.75f, 10f, 50f, 30f, 15f, 30f, 30f, 0f, -63f, 10f, -10f, luma = c(5, 0, 89, 88, 255, 241))),
        Triple("KODAK 5207", "Warm cine film (250D)", ToneParams(1.50f, 50f, 45f, 30f, 15f, 10f, 15f, 10f, -10f, 50f, 0f)),
        Triple("FUJI CLASSIC CHROME", "Subdued teal & warm", ToneParams(1.70f, 20f, 50f, 30f, 15f, 30f, 30f, 0f, -35f, 20f, 0f)),
        Triple("800T COOL", "Cool cinematic night", ToneParams(1.50f, 25f, 50f, 30f, 15f, 30f, 30f, 0f, -50f, 30f, 0f)),
        Triple("FUJI C200", "Bright film colour & grain", ToneParams(1.40f, 50f, 40f, 30f, 15f, 35f, 30f, 0f, 0f, 50f, 0f, rgb = c(0, 0, 105, 109, 197, 206, 255, 255))),
        Triple("CANON G7X II", "Light, clear everyday", ToneParams(1.50f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 50f, 0f)),
        Triple("KODAK 5219", "Cine negative (500T)", ToneParams(1.40f, 60f, 55f, 20f, 15f, 10f, 30f, 0f, -45f, 0f, 0f,
            luma = c(0, 0, 149, 148, 193, 189, 255, 255), rgb = c(0, 0, 41, 36, 118, 115, 255, 255))),
        Triple("CLASSIC U", "Retro warm with grain", ToneParams(1.45f, 40f, 40f, 30f, 15f, 20f, 20f, 20f, -30f, 0f, 0f)),
        Triple("CANON G12", "Light retro digital", ToneParams(1.45f, 0f, 0f, 0f, 0f, 30f, 30f, 0f, 0f, 50f, 0f)),
        Triple("KODAK E64", "Vivid warm, blue skies", ToneParams(1.50f, 50f, 50f, 30f, 15f, 30f, 30f, 0f, -40f, 30f, 0f)),
        Triple("PORTRA 160", "Soft warm, fine gradation", ToneParams(1.50f, 25f, 50f, 35f, 15f, 30f, 30f, 0f, -35f, 35f, 0f)),
        Triple("HASSELBLAD BLUE II", "Blue/warm variant", ToneParams(1.40f, 15f, 30f, 35f, 15f, 10f, 15f, 10f, -11f, 28f, -6f)),
        Triple("LUMIX S9 WARM", "Warm retro mood", ToneParams(1.45f, 25f, 50f, 30f, 15f, 30f, 30f, 0f, -50f, 30f, 0f)),
        Triple("RICOH GR III", "Restrained street colour", ToneParams(1.40f, 10f, 50f, 30f, 15f, 30f, 30f, 0f, 0f, 50f, -5f)),
        Triple("SIGMA BF", "Fresh modern colour", ToneParams(1.45f, 6f, 6f, 6f, 6f, 6f, 6f, 0f, -30f, 15f, -10f)),
    )

    fun all(toneCount: Int): List<Look> {
        val list = ArrayList<Look>()
        list += Look("original", "STANDARD", "Sony ISP, no look", null, Pipeline.NONE)
        list += Look("m9", "LEICA M9", "Classic colour · AI", "looks/thumb-m9.webp", Pipeline.M9, bank = "leica/m9_snapshot.bin")
        list += Look("m9p", "LEICA M9+", "Classic colour, aggressive", "looks/thumb-m9.webp", Pipeline.M9_PLUS, bank = "leica/m9_snapshot.bin")
        list += Look("m10r", "LEICA M10-R", "Vignette & grain · AI", "looks/thumb-m9.webp", Pipeline.M10R)
        list += Look("lm9", "L-M9", "Moment M9 · CCD", "looks/thumb-m9.webp", Pipeline.LM9)
        list += Look("m3", "LEICA M3", "Classic black & white", "looks/thumb-m3.webp", Pipeline.M3)
        for (i in 0 until minOf(toneCount, tones.size)) {
            val (name, sub, p) = tones[i]
            list += Look("tone$i", name, sub, "looks/thumb-$i.webp", Pipeline.TONE, lut = "looks/tone-$i.lut", tone = p.copy(seed = i))
        }
        list += Look(
            "huaijiu", "NOSTALGIA", "Vintage film", "looks/thumb-huaijiu.webp", Pipeline.TONE,
            lut = "looks/huaijiu.lut",
            tone = ToneParams(grain = 35f, shadows = 45f, midtones = 30f, highlights = 10f, grainSize = 20f,
                roughness = 25f, colorVariation = 10f, vignette = -35f, midpoint = 20f, seed = 40),
        )
        return list
    }

    /** Shader state for [look] at user intensity [k] (styleVersion/bank/stats filled by the caller). */
    fun state(look: Look, k: Float): LookState = when (look.pipeline) {
        Pipeline.NONE -> LookState()
        Pipeline.M9 -> LookState(amount = k, style = true, bankKey = look.bank, warmEv = -0.95f, warmNeutral = 0.3f)
        Pipeline.M9_PLUS -> LookState(amount = k, style = true, bankKey = look.bank, warmEv = -1.3f, warmNeutral = 0.5f,
            boostSat = 1.25f, boostContrast = 1.1f, boostWarm = 0.04f)
        Pipeline.M10R -> LookState(amount = k, style = true, warmEv = -0.95f, warmNeutral = 0.3f,
            madridGrain = 0.3f * k, madridVignette = 1.0f * k)
        Pipeline.LM9 -> LookState(amount = k)
        Pipeline.M3 -> LookState(amount = k, m3 = true)
        Pipeline.TONE -> LookState(amount = k, lutAsset = look.lut, tone = look.tone)
    }
}
