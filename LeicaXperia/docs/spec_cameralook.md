# cameralook — implementation spec (reverse-engineered from the modded APK)

Source: Dalvik bytecode of `com.codex.cameralook.*` (classes19/20/25/27.dex), plus the helper classes it calls
(`com.codex.quality80.QualityPass/SpatialPass` in classes32.dex, `com.codex.prostyles.ProfessionalSession` in classes25.dex,
`com.codex.leicaapp.PalettePostProcessor` in classes11.dex). Those extra dex files had no dexdump text, so I read them with a
small pure-Python disassembler (`scratchpad/work/minidex.py`). Its output matched dexdump byte-for-byte on the classes I compared.
I checked every formula below by re-implementing it in numpy (`scratchpad/work/cameralook_ref.py`) and running it on real images.
Items marked **UNCERTAIN** are guesses.

---------------------------------------------------------------------------------------------------------------------------

## 0. TL;DR / what the "look" actually is

* A **post-capture JPEG filter**. It is not a live preview shader. When one of these tones is selected, the preview is set to
  neutral (`PreviewTone.select(0)`). Input must be the camera's JPEG (`"New tones require camera JPEG input"`). It runs on the
  full-resolution decoded 8-bit sRGB bitmap.
* All the math is done on **gamma-encoded sRGB values in 0..1**. Nothing is linearized. No Display-P3 path: decoding forces
  `inPreferredColorSpace = SRGB`.
* **The APK contains no GLSL** for cameralook. Everything is CPU Java on `int[]` ARGB rows. Section 11 gives a GLSL ES 3.00
  port that I wrote (it is not from the APK).
* Order of operations (`ToneEngine.render` / `ProToneEngine.render`):

```
jpeg ──decode (ARGB_8888, sRGB, gain map stripped) ──rotate by EXIF orientation
     ──QualityPass.tonePrepare  (denoise: native NR, fallback SpatialPass.denoise + ChromaPass)   [§8, optional]
     ──ToneEngine.tone():
          pass 1  per pixel  ToneMath.toned()   = LUT(tetrahedral) → RGB curve → luma curve → "intensify"(strength)   [§3]
          pass 2  QualityPass.toneDetail()      = SpatialPass.detail  (adaptive luma sharpening)                   [§4]
          pass 3  per pixel  ToneMath.film()    = clarity(−) + vignette + film grain        (skipped if all 0)     [§5]
     ──(ProToneEngine only) ProfessionalSession.apply  (palette/ColorMatrix, only during a "Professional" capture)   [§9]
     ──ToneHdr.finish:  NightHighlights.prepare (night-scene highlight shoulder on SDR pixels)                       [§6]
                        API≥34: synthesize Ultra-HDR gain map + NightHighlights.limit                               [§7]
     ──watermark (LeicaMark / OriginalWatermark, reflection into host app)
     ──Bitmap.compress(JPEG, 100) + copy EXIF (Orientation→1, ColorSpace→1 sRGB)
```
Each stage writes 8-bit ARGB (`Math.round`, clamp 0..255). So the intermediate results are quantized to 8 bits between pass 1,
pass 2, pass 3 and the night shoulder.

---------------------------------------------------------------------------------------------------------------------------

## 1. Catalog (`ToneCatalog`)

* `FIRST = 16`, `COUNT = 23`. Tone **id** = 16..38 (`valid(id) = id in [16,39)`). **idx** = id − 16 = 0..22.
* `ToneCatalog.params(idx)` and the LUT asset `cameralook/tone-<idx>.lut` are both indexed by idx. The intro image is
  `cameralook/intro-<idx>.jpg`, decoded with `inSampleSize = 4`. The intro images are AI illustrations with an author banner,
  not before/after pairs.
* UI position in the host style picker = idx + 3 (`ToneUi.position(p) = p in [3,26)`, `ToneUi.model(p) = p − 3 + 16`).
  Positions 0..2 are the host app's original styles. The selection is stored in the SharedPreferences `lmoment_v101`, key
  `style` (int, default 8), and is only honoured when `fullm9_defaults_v1 == true`.
* Every tone has the same intro tail: "本地影调适配 · 原尺寸成片 / 介绍图为AI风格示意，不是实拍对照。" ("local tone adaptation ·
  full-size output / the intro image is an AI style illustration, not a real comparison").
* There is **no category field and no user intensity slider** in the code. `strength` is fixed per tone. The "Kind" column
  is my own grouping.

### 1a. Names / descriptions

| idx | id | LUT (N) | Name (as in APK) | English | Description (CN → EN) | Kind (editorial) |
|---|---|---|---|---|---|---|
| 0 | 16 | tone-0.lut (33) | 富士 CC II | Fuji CC II | 青绿层次与温暖建筑，适合街景和阴天风景 → teal-green gradation & warm buildings; street, overcast landscape | film sim |
| 1 | 17 | tone-1.lut (33) | 珠光 | Pearl | 明亮柔和的浅色层次 → bright, soft, light tones; still life, soft light | digital |
| 2 | 18 | tone-2.lut (33) | Pocket | Pocket | 清透轻快的日常色彩 → clear, airy everyday color; street, travel | camera |
| 3 | 19 | tone-3.lut (32) | 富士 NN | Fuji NN (Nostalgic Neg.) | 柔和胶片层次与细腻颗粒 → soft film gradation, fine grain; everyday landscape | film sim |
| 4 | 20 | tone-4.lut (32) | 富士 NC | Fuji NC (Classic Neg.) | 更明显的胶片颗粒与色彩氛围 → stronger grain & color mood; street | film sim |
| 5 | 21 | tone-5.lut (32) | 理光正片 | Ricoh Positive Film | 鲜明色彩与清晰反差 → vivid color, crisp contrast; sunny architecture | camera |
| 6 | 22 | tone-6.lut (32) | 理光负片 | Ricoh Negative Film | 柔和的负片色彩 → soft negative color; overcast alleys | camera |
| 7 | 23 | tone-7.lut (32) | 全能 400 | All-round 400 | 均衡胶片层次与颗粒 → balanced film tones & grain; travel, documentary | film stock |
| 8 | 24 | tone-8.lut (33) | 哈苏蓝 | Hasselblad Blue | 蓝色层次与柔和质感 → blue gradation, soft texture; coast, distant mountains | camera |
| 9 | 25 | tone-9.lut (33) | 柯达 5207 | Kodak 5207 (Vision3 250D) | 温暖电影胶片色彩 → warm cine film; sunset, old streets | film stock |
| 10 | 26 | tone-10.lut (33) | 富士 CC | Fuji CC (Classic Chrome) | 含蓄的青绿与暖色 → subdued teal & warm; city, overcast | film sim |
| 11 | 27 | tone-11.lut (33) | 800T 冷调 | 800T Cool | 冷调电影夜景色彩 → cool cinematic night; neon, night street | film stock |
| 12 | 28 | tone-12.lut (33) | 富士 C200 | Fuji C200 | 明亮胶片色彩与颗粒 → bright film color & grain; greenery, daylight | film stock |
| 13 | 29 | tone-13.lut (33) | G7X II | Canon G7X II | 轻快清透的日常色彩 → light, clear everyday; travel, plazas | camera |
| 14 | 30 | tone-14.lut (33) | 柯达 5219 | Kodak 5219 (Vision3 500T) | 电影负片层次与柔和高光 → cine-negative tones, soft highlights; dusk | film stock |
| 15 | 31 | tone-15.lut (33) | Classic U | Classic U | 复古暖色与颗粒质感 → retro warm with grain; old streets | film stock |
| 16 | 32 | tone-16.lut (33) | G12 | Canon G12 | 轻盈的复古数码色彩 → light retro-digital; everyday still life | camera |
| 17 | 33 | tone-17.lut (33) | 柯达正片 64 | Kodak Positive 64 | 鲜明的暖色与蓝天 → vivid warm & blue skies; sunny landscape | film stock |
| 18 | 34 | tone-18.lut (33) | Portra 160 | Kodak Portra 160 | 柔和暖色与细腻层次 → soft warm, fine gradation; natural light | film stock |
| 19 | 35 | tone-19.lut (33) | 哈苏蓝 II | Hasselblad Blue II | 蓝色与暖色层次的第二种表达 → second blue/warm variant; harbors | camera |
| 20 | 36 | tone-20.lut (33) | 松下 S9 暖调 | Panasonic S9 Warm | 温暖的复古气氛 → warm retro mood; cafés, evening | camera |
| 21 | 37 | tone-21.lut (33) | 理光 GR III | Ricoh GR III | 克制的日常街拍色彩与轻颗粒 → restrained street color, light grain | camera |
| 22 | 38 | tone-22.lut (33) | 适马 BF | Sigma BF | 清爽现代的色彩 → fresh modern color; architecture, greenery | camera |

### 1b. Per-tone `ToneMath.Params` (exact values from `ToneCatalog.params`, decoded from the bytecode and its array payloads)

Defaults for a fresh `Params()`: `strength = 1.0`, every other field 0, arrays null. Every catalog entry sets all fields.
`luma` / `rgb` are flat arrays `[x0,y0, x1,y1, …]` in 0..255 units. Identity is `[0,0,255,255]`.

| idx | strength | grain | shadows | midtones | highlights | grainSize | roughness | colorVar | vignette | midpoint | clarity | luma curve | rgb curve |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 0 | 1.50 | 20 | 30 | 30 | 15 | 10 | 15 | 0 | −20 | 25 | 0 | id | id |
| 1 | 1.55 | 0 | 0 | 0 | 0 | 30 | 30 | 0 | 0 | 50 | 0 | id | id |
| 2 | 1.50 | 0 | 0 | 0 | 0 | 30 | 30 | 0 | 0 | 50 | 0 | id | id |
| 3 | 1.45 | 30 | 30 | 50 | 15 | 10 | 15 | 10 | −45 | 30 | 0 | [0,0, 78,85, 135,137, 255,255] | id |
| 4 | 1.35 | 60 | 30 | 50 | 15 | 10 | 15 | 10 | −45 | 30 | 0 | id | id |
| 5 | 1.40 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 50 | 0 | id | id |
| 6 | 1.35 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 50 | 0 | id | id |
| 7 | 1.40 | 30 | 30 | 50 | 15 | 10 | 15 | 10 | −10 | 50 | 0 | id | id |
| 8 | 1.75 | 10 | 50 | 30 | 15 | 30 | 30 | 0 | −63 | 10 | −10 | [5,0, 89,88, 255,241] | id |
| 9 | 1.50 | 50 | 45 | 30 | 15 | 10 | 15 | 10 | −10 | 50 | 0 | id | id |
| 10 | 1.70 | 20 | 50 | 30 | 15 | 30 | 30 | 0 | −35 | 20 | 0 | id | id |
| 11 | 1.50 | 25 | 50 | 30 | 15 | 30 | 30 | 0 | −50 | 30 | 0 | id | id |
| 12 | 1.40 | 50 | 40 | 30 | 15 | 35 | 30 | 0 | 0 | 50 | 0 | id | [0,0, 105,109, 197,206, 255,255] |
| 13 | 1.50 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 50 | 0 | id | id |
| 14 | 1.40 | 60 | 55 | 20 | 15 | 10 | 30 | 0 | −45 | 0 | 0 | [0,0, 149,148, 193,189, 255,255] | [0,0, 41,36, 118,115, 255,255] |
| 15 | 1.45 | 40 | 40 | 30 | 15 | 20 | 20 | 20 | −30 | 0 | 0 | id | id |
| 16 | 1.45 | 0 | 0 | 0 | 0 | 30 | 30 | 0 | 0 | 50 | 0 | id | id |
| 17 | 1.50 | 50 | 50 | 30 | 15 | 30 | 30 | 0 | −40 | 30 | 0 | id | id |
| 18 | 1.50 | 25 | 50 | 35 | 15 | 30 | 30 | 0 | −35 | 35 | 0 | id | id |
| 19 | 1.40 | 15 | 30 | 35 | 15 | 10 | 15 | 10 | −11 | 28 | −6 | id | id |
| 20 | 1.45 | 25 | 50 | 30 | 15 | 30 | 30 | 0 | −50 | 30 | 0 | id | id |
| 21 | 1.40 | 10 | 50 | 30 | 15 | 30 | 30 | 0 | 0 | 50 | −5 | id | id |
| 22 | 1.45 | 6 | 6 | 6 | 6 | 6 | 6 | 0 | −30 | 15 | −10 | id | id |

(Float sources: 1.45 = 0x3fb9999a, 1.4 = 0x3fb33333, 1.35/1.55/1.7/1.75 likewise. Integers come from `const/high16`.)

What the fields mean (§5): `grain` is the overall grain amount (0..100). `shadows`, `midtones` and `highlights` are grain
amounts per luma band (0..100). `grainSize` sets the grain cell size, `roughness` the share of per-pixel white noise, and
`colorVariation` the chroma grain. `vignette` is in units of 1/100 EV (−45 → −0.45 EV at the corners). `midpoint` is where
the vignette starts (0..85). `clarity` is local-contrast reduction, and only negative values do anything.

Kotlin:
```kotlin
data class ToneParams(
  val strength: Float, val grain: Float, val shadows: Float, val midtones: Float, val highlights: Float,
  val grainSize: Float, val roughness: Float, val colorVariation: Float, val vignette: Float,
  val midpoint: Float, val clarity: Float, val luma: FloatArray, val rgb: FloatArray)
```

---------------------------------------------------------------------------------------------------------------------------

## 2. LUT file format & loading (`ToneTable.<init>(InputStream)`)

Big-endian `DataInputStream`:
```
int32  magic  = 0x434C5531 ("CLU1")            else IOException("Invalid LUT magic")
int32  N      (2 ≤ N ≤ 65)                       else "Invalid LUT size"     (assets: 33, or 32 for idx 3..7)
float32 min[3]  (R,G,B)                          all assets: 0,0,0
float32 max[3]                                    all assets: 1,1,1      (each finite and min<max, else "Invalid LUT domain")
float32 data[N*N*N*3]                             each finite, else "Nonfinite LUT"; must be followed by EOF ("Unexpected LUT data")
```
**Layout: R varies fastest, then G, then B.** `data[((b*N + g)*N + r)*3 + c]`, with strides sR = 3, sG = 3N, sB = 3N².
I confirmed this against the assets: output R correlates 0.95–1.0 with the fastest axis, and the neutral diagonal is close to
identity. The values are gamma-encoded sRGB in and out. This is the same memory order as a GL `TEXTURE_3D` with x=R, y=G, z=B,
so the data can be uploaded as-is (`glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB32F, N, N, N, 0, GL_RGB, GL_FLOAT, buf)`).

`ToneEngine` caches exactly one LUT (`cachedId`/`cached`).

### 2a. `ToneTable.map(r, g, b, out)` — tetrahedral interpolation (exact)
```kotlin
fun map(r: Float, g: Float, b: Float, out: FloatArray) {
    var fr = clamp01((r - min[0]) / (max[0] - min[0])) * (n - 1)
    var fg = clamp01((g - min[1]) / (max[1] - min[1])) * (n - 1)
    var fb = clamp01((b - min[2]) / (max[2] - min[2])) * (n - 1)
    val ir = min(n - 2, fr.toInt()); val ig = min(n - 2, fg.toInt()); val ib = min(n - 2, fb.toInt())
    fr -= ir; fg -= ig; fb -= ib
    val sR = 3; val sG = 3 * n; val sB = 3 * n * n
    val first: Int; val second: Int; val w1: Float; val w2: Float; val w3: Float   // w1 ≥ w2 ≥ w3
    if (fr >= fg) {
        if (fg >= fb)      { first = sR; second = sR + sG; w1 = fr; w2 = fg; w3 = fb }
        else if (fr >= fb) { first = sR; second = sR + sB; w1 = fr; w2 = fb; w3 = fg }
        else               { first = sB; second = sB + sR; w1 = fb; w2 = fr; w3 = fg }
    } else {
        if (fr >= fb)      { first = sG; second = sG + sR; w1 = fg; w2 = fr; w3 = fb }
        else if (fg >= fb) { first = sG; second = sG + sB; w1 = fg; w2 = fb; w3 = fr }
        else               { first = sB; second = sB + sG; w1 = fb; w2 = fg; w3 = fr }
    }
    val base = ir * sR + ig * sG + ib * sB; val all = sR + sG + sB
    for (c in 0 until 3) {
        val c0 = data[base + c]; val c1 = data[base + first + c]
        val c2 = data[base + second + c]; val c3 = data[base + all + c]
        out[c] = clamp01(c0 + (c1 - c0) * w1 + (c2 - c1) * w2 + (c3 - c2) * w3)
    }
}
```
GPU trilinear filtering is close but does **not** match exactly. Use the tetrahedral `texelFetch` version in §11 for parity.

---------------------------------------------------------------------------------------------------------------------------

## 3. Pass 1 — `ToneMath.toned(argb, table, params, tmp[3])` (per pixel, exact)

```kotlin
fun clamp01(x: Float) = max(0f, min(1f, x))              // ToneMath.clamp
fun channel(x: Float) = max(0, min(255, Math.round(x * 255f)))   // Java Math.round(float) = floor(x+0.5)

// piecewise-linear curve, points in 0..255 (ToneMath.curve)
fun curve(x0: Float, p: FloatArray): Float {
    val x = clamp01(x0) * 255f
    if (x <= p[0]) return clamp01(p[1] / 255f)
    var i = 2
    while (i < p.size) {
        if (x <= p[i]) return clamp01((p[i-1] + (p[i+1] - p[i-1]) * (x - p[i-2]) / (p[i] - p[i-2])) / 255f)
        i += 2
    }
    return clamp01(p[p.size - 1] / 255f)
}

// "strength": push the LUT result further away from the original, with soft saturation (ToneMath.intensify)
fun intensify(orig: Float, mapped: Float, strength: Float): Float {
    val k = (max(1f, min(1.8f, strength)) - 1f) * (mapped - orig)
    val room = if (k >= 0f) 1f - mapped else mapped          // headroom in the push direction
    if (room <= 0f) return clamp01(mapped)
    return clamp01(mapped + k * room / (room + abs(k)))
}

fun toned(argb: Int, lut: ToneTable, p: ToneParams, t: FloatArray): Int {
    val r = ((argb ushr 16) and 255) / 255f; val g = ((argb ushr 8) and 255) / 255f; val b = (argb and 255) / 255f
    lut.map(r, g, b, t)                                         // 1. 3D LUT (tetrahedral)
    for (i in 0..2) t[i] = curve(t[i], p.rgb)                   // 2. master RGB curve, per channel
    val Y = 0.2126f * t[0] + 0.7152f * t[1] + 0.0722f * t[2]    // 3. Rec.709 weights on gamma-encoded values
    val Yc = curve(Y, p.luma)                                   //    luma curve applied by scaling RGB
    val orig = floatArrayOf(r, g, b)
    for (i in 0..2) {
        val v = clamp01(if (Y > 1e-6f) t[i] * Yc / Y else Yc)
        t[i] = intensify(orig[i], v, p.strength)                // 4. strength vs. the ORIGINAL pixel
    }
    return (0xFF shl 24) or (channel(t[0]) shl 16) or (channel(t[1]) shl 8) or channel(t[2])   // alpha forced 255
}
```
`strength` behaviour: k = (s−1)·Δ, where Δ is the LUT's change to the channel. The result is moved further in the same
direction by k·room/(room+|k|). That never leaves [0,1] and saturates softly near 0 or 1. Strengths in the catalog run
1.35–1.75, so the LUT effect is amplified by roughly 35–75 % in mid-tones. To add a user intensity slider of your own, a
natural option is `out = mix(orig, toned, u)`, or scaling `strength − 1` (UNCERTAIN: the original has no slider).

---------------------------------------------------------------------------------------------------------------------------

## 4. Pass 2 — `QualityPass.toneDetail` = `SpatialPass.detail(bitmap)` (adaptive luma sharpening, exact)

This always runs after pass 1 (bitmap mutable, ARGB_8888). It works on 8-bit pixels; Y is in 0..255 units.

```kotlin
fun yOf(p: Int) = (((p ushr 16) and 255) * 77 + ((p ushr 8) and 255) * 150 + (p and 255) * 29) / 256f

// global noise estimate (SpatialPass.noise)
fun noiseEstimate(img): Float {
    if (w < 2 || h < 2) return 1.25f
    val hist = IntArray(256); var count = 0
    for (y in 0 until h - 1 step max(1, h / 32)) for (x in 0 until w - 1 step max(1, w / 64)) {
        // (only if all 4 pixels opaque)
        val v = abs(Y(x,y) - Y(x+1,y) - Y(x,y+1) + Y(x+1,y+1))
        hist[min(255, Math.round(v))]++; count++
    }
    var cum = 0
    for (i in 0..255) { cum += hist[i]; if (cum > count / 2) return max(1.25f, min(18f, i * 0.7413f)) }
    return 1.25f
}

N = noiseEstimate(img); thr = max(0.8f, 0.7f * N)
// Hb = horizontal [1 4 6 4 1]/16 of Y (edge-clamped); blur = vertical [1 4 6 4 1]/16 of Hb  → 5x5 Gaussian
for each pixel (x,y) (rows/cols edge-clamped; skipped if any pixel in the 5x5 window has alpha≠255):
    a0,a1,a2 = Y(x-1,y-1), Y(x,y-1), Y(x+1,y-1)
    b0,b1,b2 = Y(x-1,y  ), Y(x,y  ), Y(x+1,y  )
    c0,c1,c2 = Y(x-1,y+1), Y(x,y+1), Y(x+1,y+1)
    d    = b1 - blur(x,y)                                      // high-pass
    gx   = (a2 + 2*b2 + c2 - a0 - 2*b0 - c0) / 8               // Sobel
    gy   = (c0 + 2*c1 + c2 - a0 - 2*a1 - a2) / 8
    g    = sqrt(gx*gx + gy*gy)
    edge = g / (g + 2.5*N + 2)
    amt  = copySign(min(4, max(0, |d| - thr) * 0.55 * edge), d) // coring + edge-gated gain, max ±4 levels
    lo = min(min(a1,c1), min(b0,b2)); hi = max(max(a1,c1), max(b0,b2))   // 4-neighbour range
    amt = clamp(amt, min(b1,lo) - b1, max(b1,hi) - b1)                    // no overshoot beyond neighbours
    amt = clamp(amt, -min(R,G,B), 255 - max(R,G,B))                        // keep hue (same offset to all channels)
    R' = round(R+amt), G' = round(G+amt), B' = round(B+amt)   (clamped 0..255), alpha 255
```
The original uses a rolling 5-row buffer. Row r is read into the buffer while row r−3 is processed, before row r is
overwritten, so every neighbourhood comes from the unsharpened pass-1 image. You can implement this as a pure function of the
pass-1 image.

---------------------------------------------------------------------------------------------------------------------------

## 5. Pass 3 — `ToneMath.film(argb, x, y, w, h, seed, params, localLuma)` (exact)

Skipped entirely when `grain == 0 && vignette == 0 && clarity == 0`. Its input is the pass-2 image. `seed = idx` (0..22).
`(x, y)` are pixel coordinates in the full-resolution, upright image (origin at the top-left).

`localLuma` is a 3×3 [1 2 1; 2 4 2; 1 2 1]/16 blur of `L(p) = (0.2126R + 0.7152G + 0.0722B)/255`, taken over the pass-2 image
(edge-clamped). As in pass 2, it is computed from the unmodified input rows.

```kotlin
fun film(argb: Int, x: Int, y: Int, w: Int, h: Int, seed: Int, p: ToneParams, localLuma: Float): Int {
    var r = ((argb ushr 16) and 255) / 255f; var g = ((argb ushr 8) and 255) / 255f; var b = (argb and 255) / 255f
    val L = 0.2126f * r + 0.7152f * g + 0.0722f * b
    // clarity: only NEGATIVE values act (softens local contrast), range clamped to [-20, 0]
    val detail = (L - localLuma) * (max(-20f, min(0f, p.clarity)) / 100f)
    // vignette (elliptical, normalized per axis)
    val nx = (2f * x + 1f - w) / max(1, w); val ny = (2f * y + 1f - h) / max(1, h)
    val d = (nx * nx + ny * ny) * 0.5f                          // 0 at center, 1 at corners
    val mp = max(0f, min(0.85f, p.midpoint / 100f))
    var t = clamp01((d - mp) / (1f - mp)); t = t * t * (3f - 2f * t)   // smoothstep
    val gain = 2.0.pow((max(-1.25f, min(1.25f, p.vignette / 100f)) * t).toDouble()).toFloat()   // EV
    // grain amplitude per luma band
    val band = if (L < 0.5f) p.shadows + (p.midtones - p.shadows) * (L * 2f)
               else          p.midtones + (p.highlights - p.midtones) * (L * 2f - 1f)
    val amp = max(0f, min(100f, p.grain)) / 100f * max(0f, band) / 100f * 0.06f
    val mono = noise(x, y, seed, p.grainSize, p.roughness) * amp
    val cv = min(1f, max(0f, p.colorVariation) / 100f)
    r = (r + detail) * gain + mono + noise(x, y, seed + 1, p.grainSize, p.roughness) * amp * cv
    g = (g + detail) * gain + mono + noise(x, y, seed + 2, p.grainSize, p.roughness) * amp * cv
    b = (b + detail) * gain + mono + noise(x, y, seed + 3, p.grainSize, p.roughness) * amp * cv
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

// value noise on a lattice of `s` px, bilinear, mixed with per-pixel white noise (ToneMath.noise)
fun noise(x: Int, y: Int, seed: Int, size: Float, rough: Float): Float {
    val s = max(0f, size) / 25f + 1f
    var fx = x / s; var fy = y / s
    val ix = fx.toInt(); val iy = fy.toInt(); fx -= ix; fy -= iy
    val v = (hash(ix, iy, seed) * (1 - fx) + hash(ix + 1, iy, seed) * fx) * (1 - fy) +
            (hash(ix, iy + 1, seed) * (1 - fx) + hash(ix + 1, iy + 1, seed) * fx) * fy
    val rr = min(0.7f, max(0f, rough) / 100f)
    return v * (1f - rr) + hash(x, y, seed + 83) * rr
}

// integer hash (32-bit wrapping int math, >>> = unsigned shift) → [-1, 1) (ToneMath.hash)
fun hash(a: Int, b: Int, c: Int): Float {
    var h = a * 0x1f123bb5 + b * 0x5f356495 + c * 0x6c8e9cf5.toInt()
    h = h xor (h ushr 16); h *= 0x7feb352d
    h = h xor (h ushr 15); h *= 0x846ca68b.toInt()
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 8388607.5f - 1f                 // 8388607.5 = 0x4AFFFFFF
}
```
Notes: grain is in **output pixels** at full resolution. Grain cell size is grainSize/25 + 1 px (10 → 1.4 px, 30 → 2.2 px,
35 → 2.4 px). For a preview at scale k, either evaluate the noise at full-resolution coordinates (`x/k`) or accept a different
grain size. A maximum amplitude of ≈0.06 × (grain/100) × (band/100) means e.g. idx 14 (grain 60, shadows 55) gives about
±0.02 = ±5 levels in the shadows.

---------------------------------------------------------------------------------------------------------------------------

## 6. NightHighlights (SDR highlight shoulder for night scenes) — `ToneHdr.finish → NightHighlights.prepare`

This runs on every render after `tone()`, and it changes the **SDR pixels**. It is skipped if `FullM9Hdr.RAW_BOOST` (a
ThreadLocal in the host) is set, in which case `night = false`.

Detection (sparse grid sample):
```
sx = max(1, (w+255)/256), sy = max(1, (h+255)/256)            // ~256x256 samples
for y in 0 until h step sy, x in 0 until w step sx:
    lum = round(0.2126R + 0.7152G + 0.0722B)   (0..255 ints)
    sum += lum; count++
    if lum < 77           dark++
    if max(R,G,B) >= 204  bright++
night = count>0 && dark >= 0.65*count && sum < 76.5*count && bright > 0 && bright <= 0.15*count
```
If `night` is true and the bitmap is mutable ARGB_8888, then for every pixel:
```
m = max(R,G,B); if (m > 183) { s = m/255f; k = shoulder(s)/s; R=round(R*k); G=round(G*k); B=round(B*k) }  // alpha kept
shoulder(x) = if (x <= 0.72) x else { t=(x-0.72)/0.28; 0.72 + 0.28*t/(1+0.32*t) }   // 1.0 → 0.9321
```
This is hue-preserving because all channels are scaled by the same factor. Highlights are compressed to at most ≈238/255.
`NightHighlights.limit` (API 34+, only when night && !raw): if the gain map metadata is valid, it caps `ratioMax[c]` at 1.2 and
sets `displayRatioForFullHdr = max(minDisplayRatioForHdrTransition, min(displayRatioForFullHdr, 1.2))`. "Valid" means all
values finite, 0 < ratioMin ≤ 1.2 and ratioMax ≥ ratioMin.

---------------------------------------------------------------------------------------------------------------------------

## 7. HDR gain map (ToneHdr / GainPlane / HdrMath / HdrRepair / WatermarkGainMath)

* `ToneInput.decode` **removes** any incoming gain map (`setGainmap(null)`), so the tone works on SDR only.
* `ToneHdr$Api34.generate` (API ≥ 34) builds a **synthetic** gain map:
  * Size: scale = min(1, 384/max(w,h)); gw = max(1, round(w·scale)), gh likewise (≤ 384 px on the long side).
  * `pix = NativeM9.hdrSample(bitmap, gw, gh)`. This is native code. **UNCERTAIN**: assumed to be a box/area downsample of
    the toned bitmap to gw×gh ARGB.
  * `GainPlane.fromPixels`: g = smoothstep(0.36, 0.94, luma709(pix)/255), stored as the gray byte round(255·g). Then
    `GainPlane.smooth`: a separable integer [1 2 1]/4 blur with rounding, `(a + 2b + c + 2)/4` horizontally, then
    vertically, edge-clamped.
  * Gain map metadata: ratioMin = 1, **ratioMax = 2** (+1 stop), gamma = 1, epsilonSdr = epsilonHdr = 0,
    minDisplayRatioForHdrTransition = 1, displayRatioForFullHdr = 2.
  * Effect: on an HDR display, pixels with luma above ~0.36 are boosted by up to 2× (fully at luma ≥ 0.94). On SDR displays
    nothing changes. `Bitmap.compress(JPEG)` then writes an Ultra HDR JPEG. Failures fall back to SDR (logged as
    `"Keep SDR …"`).
* `HdrMath.source`, `HdrMath.limit` (1.8 or 2.0), `HdrRepair` and `WatermarkGainMath`/`HdrWatermark`/`WatermarkLast` are
  geometry plumbing. They re-align or re-crop an existing gain map when the watermark frame changes the canvas: they locate
  the photo inside the watermarked canvas, bilinearly resample at most 384 px, and set gain coverage under the watermark
  border. `HdrMath.source(px,…)` converts a 3-channel gain map to one channel:
  log-gain = Σ_c w_c·[ln rmin_c + (ln rmax_c − ln rmin_c)·v_c^γ_c], with w = (0.2126, 0.7152, 0.0722). This is clamped to
  [0, ln(limit)], multiplied by smoothstep(0.1, 0.55, luma) and stored as a byte 255·g/ln(limit).
  **None of this matters for a non-Ultra-HDR pipeline.** If you skip HDR, keep §6 (`prepare`, the SDR shoulder) and drop
  `limit`, `generate`, `HdrRepair` and the watermark gain code.

---------------------------------------------------------------------------------------------------------------------------

## 8. Pre-denoise (`ToneEngine.denoise` = `QualityPass.tonePrepare`) — optional for the port

`QualityPass.noise(bitmap, false, null)` tries `com.codex.lmport.DenoiseFlow.runNative(copy, false)`, which is native and
cannot be reproduced. If that fails it falls back to `SpatialPass.denoise` (a Java row-based spatial NR), followed by
`ChromaPass.apply` (chroma cleanup). I did not decode these in detail. They are image-quality steps, not part of the color
look; the Sony ISP output can be used directly. **UNCERTAIN / not specified.**

---------------------------------------------------------------------------------------------------------------------------

## 9. ProToneEngine vs ToneEngine

`ProToneEngine` (classes25) is byte-identical to `ToneEngine` in `tone()`, `table()`, `denoise()` and `metadata()`. The
`tone()` method matches exactly in a diff. It differs in two ways:
* The lock comes from `ProfessionalSession.sharedLock("com.codex.cameralook.ToneEngine","LOCK")`.
* It calls `ProfessionalSession.apply(bitmap)` after `tone()` and before `ToneHdr.finish`.

`ProfessionalSession.apply` is a no-op unless a "Professional capture" shot is active (ThreadLocal `CURRENT`). When one is:
* If the shot's native palette string contains `@StyleAdjustEffect`, it reads the floats SaturationIntensity (s),
  ContrastIntensity (c), Temperature (T), Tint (n), Grain and VignetteIntensity, then calls
  `matrix(bmp, clamp(s+1, 0, 2), clamp(c·0.5+1, 0.5, 1.5), 18T+6n, −12n, −18T+6n)`. If Grain or Vignette ≠ 0, it also calls
  `PalettePostProcessor.texture(bmp, grain, vignette)`, which I did not decode (UNCERTAIN).
* Built-in styles: style −100 → `matrix(1.2, 1.06, 1, 0, 0)`; −101 → `matrix(0.78, 1.12, 4, 0, −4)`; −102 →
  `matrix(0.9, 0.96, 0, 0, 0)`.
* It then applies `ProfessionalPalette.apply(bmp, shot.palette)`, which I did not decode.

`PalettePostProcessor.matrix(sat S, contrast C, oR, oG, oB)` → `android.graphics.ColorMatrix` (0..255 domain):
```
k = 1−S;  lr = 0.213k, lg = 0.715k, lb = 0.072k;  off = 128(1−C)
R' = C·((lr+S)R + lg·G + lb·B) + oR + off
G' = C·(lr·R + (lg+S)G + lb·B) + oG + off
B' = C·(lr·R + lg·G + (lb+S)B) + oB + off
```
This is only relevant if you also port the host's "Professional" mode. For the cameralook tones it does nothing.

---------------------------------------------------------------------------------------------------------------------------

## 10. Other classes

* **ProZoom**: a UI hook only. In the host's professional/manual modes (mode 167 or 180, or 256 with the rear lens), it
  remembers an "anchor" zoom ratio per `ZoomRatioToggleView` child. On click it calls the host's `T(index, 18, true, true,
  false, animate)` to jump back to that ratio. Valid ratios are in (0, 100]. It has nothing to do with color.
* **ToneUi / ToneGallery**: the picker UI. It appends the 23 names after the host's 3 styles, shows a badge ("<name> 影调，点击切换"),
  and shows the intro image and description. The label bitmap is 640×72 with a bold 42 px font centered at (320, 53). Import
  is disabled ("新增影调用于相机拍摄成片，暂不支持此导入入口").
* **ToneState**: saves and restores capture-geometry fields of the host's capture object. `commitJpeg` stores the output JPEG
  and sets field j = 256.
* **ToneExif**: post-processes the output JPEG's APP1 EXIF. It sets Orientation (0x0112) to 1 because the pixels are already
  rotated, and sets ColorSpace (0xA001, in IFD0 and the Exif IFD 0x8769) to 1 (sRGB).
* **ToneOrientation.matrix(o)**: the standard EXIF orientation matrix (2..8). It is applied with
  `Bitmap.createBitmap(…, matrix, filter = false)`.
* **u (classes20)**: decrypts `assets/cameralook/author-notice.bin` (AES/GCM, hard-coded key, tag "author-r17-v1") into an
  author notice dialog. Irrelevant.
* `render()` logs `"<name> photo=WxH output=WxH seconds=…"` under the tag `CameraLook`.

---------------------------------------------------------------------------------------------------------------------------

## 11. GLSL

**There are no GLSL shader strings in the cameralook classes** (classes19/20/25/27 contain no `#version`, `uniform` or
`texture(` strings). The only shaders in the modded dex files are trivial pass-through blits in unrelated modules (classes22:
`uniform samplerExternalOES t;void main(){gl_FragColor=texture2D(t,uv);}`, plus the same with `sampler2D`), and the stock host
app's shaders.

Below is my own **GLSL ES 3.00 port** for Kotlin + GLES 3.x. It is not from the APK.

Ground rules:
* Upload the sRGB JPEG as **GL_RGBA8**, not `GL_SRGB8_ALPHA8`. The math wants gamma-encoded values.
* Run each pass offscreen into an RGBA8 FBO for bit-parity with the Java 8-bit quantization (RGBA16F is fine if you prefer).
* If you upload a Bitmap with `GLUtils.texImage2D` and never flip, then `ivec2(gl_FragCoord.xy)` equals the Java bitmap (x, y)
  with a top-left origin.

### Pass 1 (`toned`)
```glsl
#version 300 es
precision highp float; precision highp int; precision highp sampler3D;
uniform sampler2D uSrc;            // RGBA8 sRGB-encoded
uniform sampler3D uLut;            // N^3 RGB32F (or RGBA16F), data uploaded as-is from the .lut (R fastest)
uniform int  uN;
uniform vec3 uDomMin, uDomMax;     // (0,0,0),(1,1,1) for all assets
uniform float uStrength;
uniform vec2 uRgb[4];  uniform int uRgbCount;    // (in,out) points, 0..255
uniform vec2 uLuma[4]; uniform int uLumaCount;
out vec4 oColor;

vec3 L(ivec3 p){ return texelFetch(uLut, p, 0).rgb; }
vec3 lutTetra(vec3 c){
  vec3 f = clamp((c - uDomMin) / (uDomMax - uDomMin), 0.0, 1.0) * float(uN - 1);
  ivec3 i0 = min(ivec3(f), ivec3(uN - 2));
  vec3 d = f - vec3(i0);
  ivec3 e1, e2; vec3 w;
  if (d.r >= d.g) {
    if (d.g >= d.b)      { e1 = ivec3(1,0,0); e2 = ivec3(1,1,0); w = d.rgb; }
    else if (d.r >= d.b) { e1 = ivec3(1,0,0); e2 = ivec3(1,0,1); w = d.rbg; }
    else                 { e1 = ivec3(0,0,1); e2 = ivec3(1,0,1); w = d.brg; }
  } else {
    if (d.r >= d.b)      { e1 = ivec3(0,1,0); e2 = ivec3(1,1,0); w = d.grb; }
    else if (d.g >= d.b) { e1 = ivec3(0,1,0); e2 = ivec3(0,1,1); w = d.gbr; }
    else                 { e1 = ivec3(0,0,1); e2 = ivec3(0,1,1); w = d.bgr; }
  }
  vec3 c0 = L(i0), c1 = L(i0 + e1), c2 = L(i0 + e2), c3 = L(i0 + ivec3(1));
  return clamp(c0 + (c1 - c0) * w.x + (c2 - c1) * w.y + (c3 - c2) * w.z, 0.0, 1.0);
}
float curve(float x, vec2 p[4], int n){
  x = clamp(x, 0.0, 1.0) * 255.0;
  if (x <= p[0].x) return clamp(p[0].y / 255.0, 0.0, 1.0);
  for (int i = 1; i < 4; ++i) {
    if (i >= n) break;
    if (x <= p[i].x) return clamp((p[i-1].y + (p[i].y - p[i-1].y) * (x - p[i-1].x) / (p[i].x - p[i-1].x)) / 255.0, 0.0, 1.0);
  }
  return clamp(p[n-1].y / 255.0, 0.0, 1.0);
}
float intensify(float a, float b, float s){
  float k = (clamp(s, 1.0, 1.8) - 1.0) * (b - a);
  float room = (k >= 0.0) ? 1.0 - b : b;
  if (room <= 0.0) return clamp(b, 0.0, 1.0);
  return clamp(b + k * room / (room + abs(k)), 0.0, 1.0);
}
void main(){
  vec3 src = texelFetch(uSrc, ivec2(gl_FragCoord.xy), 0).rgb;
  vec3 m = lutTetra(src);
  m = vec3(curve(m.r, uRgb, uRgbCount), curve(m.g, uRgb, uRgbCount), curve(m.b, uRgb, uRgbCount));
  float Y = dot(m, vec3(0.2126, 0.7152, 0.0722));
  float Yc = curve(Y, uLuma, uLumaCount);
  vec3 v = clamp((Y > 1e-6) ? m * Yc / Y : vec3(Yc), 0.0, 1.0);
  oColor = vec4(intensify(src.r, v.r, uStrength), intensify(src.g, v.g, uStrength), intensify(src.b, v.b, uStrength), 1.0);
}
```
(Curves with at most 4 points cover every catalog entry: 2, 3 or 4 points.)

### Pass 2 (`detail`)
Compute `uNoise` = `noiseEstimate()` on the CPU from the pass-1 output (glReadPixels, or estimate on a downscaled copy:
UNCERTAIN parity). Then:
```glsl
#version 300 es
precision highp float; precision highp int;
uniform sampler2D uImg; uniform ivec2 uSize; uniform float uNoise;
out vec4 oColor;
float Yat(ivec2 p){ p = clamp(p, ivec2(0), uSize - 1); vec3 c = floor(texelFetch(uImg, p, 0).rgb * 255.0 + 0.5);
                    return (c.r * 77.0 + c.g * 150.0 + c.b * 29.0) / 256.0; }
void main(){
  ivec2 p = ivec2(gl_FragCoord.xy);
  const float k[5] = float[5](1.0, 4.0, 6.0, 4.0, 1.0);
  float blur = 0.0;
  for (int j = -2; j <= 2; ++j) { float row = 0.0;
    for (int i = -2; i <= 2; ++i) row += k[i+2] * Yat(p + ivec2(i, j));
    blur += k[j+2] * (row / 16.0); }
  blur /= 16.0;
  float a0=Yat(p+ivec2(-1,-1)), a1=Yat(p+ivec2(0,-1)), a2=Yat(p+ivec2(1,-1));
  float b0=Yat(p+ivec2(-1, 0)), b1=Yat(p),             b2=Yat(p+ivec2(1, 0));
  float c0=Yat(p+ivec2(-1, 1)), c1=Yat(p+ivec2(0, 1)), c2=Yat(p+ivec2(1, 1));
  float d = b1 - blur;
  float gx = (a2 + 2.0*b2 + c2 - a0 - 2.0*b0 - c0) / 8.0;
  float gy = (c0 + 2.0*c1 + c2 - a0 - 2.0*a1 - a2) / 8.0;
  float g = sqrt(gx*gx + gy*gy);
  float edge = g / (g + 2.5 * uNoise + 2.0);
  float thr = max(0.8, 0.7 * uNoise);
  float amt = sign(d) * min(4.0, max(0.0, abs(d) - thr) * 0.55 * edge);
  float lo = min(min(a1, c1), min(b0, b2)), hi = max(max(a1, c1), max(b0, b2));
  amt = clamp(amt, min(b1, lo) - b1, max(b1, hi) - b1);
  vec3 c = floor(texelFetch(uImg, p, 0).rgb * 255.0 + 0.5);
  amt = clamp(amt, -min(c.r, min(c.g, c.b)), 255.0 - max(c.r, max(c.g, c.b)));
  oColor = vec4(clamp(floor(c + amt + 0.5), 0.0, 255.0) / 255.0, 1.0);
}
```
(Java's `copySign(…, d)` keeps the sign for d = ±0. With `sign(0) = 0` the amount is 0 anyway.)

### Pass 3 (`film`) — skip when grain = vignette = clarity = 0
```glsl
#version 300 es
precision highp float; precision highp int;
uniform sampler2D uImg; uniform ivec2 uSize; uniform int uSeed;  // seed = tone idx
uniform float uGrain, uShadows, uMidtones, uHighlights, uGrainSize, uRoughness, uColorVar, uVignette, uMidpoint, uClarity;
out vec4 oColor;
const vec3 W = vec3(0.2126, 0.7152, 0.0722);
float Lat(ivec2 p){ return dot(texelFetch(uImg, clamp(p, ivec2(0), uSize - 1), 0).rgb, W); }
float jhash(int a, int b, int c){
  uint h = uint(a) * 0x1f123bb5u + uint(b) * 0x5f356495u + uint(c) * 0x6c8e9cf5u;   // wraps mod 2^32 (GLSL ES 3.00)
  h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u;
  return float(h & 0xFFFFFFu) / 8388607.5 - 1.0;
}
float vnoise(ivec2 p, int seed){
  float s = max(0.0, uGrainSize) / 25.0 + 1.0;
  vec2 f = vec2(p) / s; ivec2 i = ivec2(f); f -= vec2(i);
  float v = (jhash(i.x, i.y, seed) * (1.0 - f.x) + jhash(i.x + 1, i.y, seed) * f.x) * (1.0 - f.y)
          + (jhash(i.x, i.y + 1, seed) * (1.0 - f.x) + jhash(i.x + 1, i.y + 1, seed) * f.x) * f.y;
  float r = min(0.7, max(0.0, uRoughness) / 100.0);
  return v * (1.0 - r) + jhash(p.x, p.y, seed + 83) * r;
}
void main(){
  ivec2 p = ivec2(gl_FragCoord.xy);
  vec3 c = texelFetch(uImg, p, 0).rgb;
  float L = dot(c, W);
  float lb = 0.0;                                   // 3x3 [1 2 1]^T[1 2 1]/16
  for (int j = -1; j <= 1; ++j) for (int i = -1; i <= 1; ++i)
    lb += Lat(p + ivec2(i, j)) * float((2 - abs(i)) * (2 - abs(j)));
  lb /= 16.0;
  float detail = (L - lb) * (clamp(uClarity, -20.0, 0.0) / 100.0);
  vec2 n = (2.0 * vec2(p) + 1.0 - vec2(uSize)) / vec2(max(uSize, ivec2(1)));
  float d = dot(n, n) * 0.5;
  float mp = clamp(uMidpoint / 100.0, 0.0, 0.85);
  float t = clamp((d - mp) / (1.0 - mp), 0.0, 1.0); t = t * t * (3.0 - 2.0 * t);
  float gain = exp2(clamp(uVignette / 100.0, -1.25, 1.25) * t);
  float band = (L < 0.5) ? uShadows + (uMidtones - uShadows) * (L * 2.0)
                         : uMidtones + (uHighlights - uMidtones) * (L * 2.0 - 1.0);
  float amp = clamp(uGrain, 0.0, 100.0) / 100.0 * max(0.0, band) / 100.0 * 0.06;
  float mono = vnoise(p, uSeed) * amp;
  float cv = min(1.0, max(0.0, uColorVar) / 100.0);
  vec3 col = vec3(vnoise(p, uSeed + 1), vnoise(p, uSeed + 2), vnoise(p, uSeed + 3)) * amp * cv;
  vec3 o = (c + detail) * gain + mono + col;
  oColor = vec4(clamp(floor(o * 255.0 + 0.5), 0.0, 255.0) / 255.0, 1.0);
}
```
### Pass 4 (night shoulder, optional)
Compute `night` on the CPU (§6) from a 256×256 sample of the pass-3 output. If it is true:
`m = max(c.r, c.g, c.b) * 255; if (m > 183) c *= shoulder(m/255)/(m/255)`, then round to 8-bit.

Precision: the Java code is float32 throughout except `pow` (double). Expect ±1 LSB differences from GPU rounding, and the
hash must use `highp uint`. Mali/Adreno support for highp in fragment shaders is required (it is on any ES 3.0 device).

---------------------------------------------------------------------------------------------------------------------------

## 12. All numeric constants

| Where | Constant | Bits | Meaning |
|---|---|---|---|
| ToneTable | 0x434C5531 | int | "CLU1" magic |
| ToneTable | 2 … 65 | int | allowed N |
| ToneMath.* | 255.0 | 0x437f0000 | 8-bit scale |
| ToneMath.toned/film/luma | 0.2126, 0.7152, 0.0722 | 0x3e59b3d0, 0x3f371759, 0x3d93dd98 | Rec.709 luma weights (on gamma values) |
| ToneMath.toned | 1e-6 | 0x358637bd | luma divide guard |
| ToneMath.intensify | 1.0 … 1.8 | 0x3f800000, 0x3fe66666 | strength clamp |
| ToneMath.film | −20 … 0, /100 | 0xc1a00000, 0x42c80000 | clarity clamp |
| ToneMath.film | 2, 1, 0.5 | | vignette coordinates: (2x+1−w)/w, d = 0.5·r² |
| ToneMath.film | 0 … 0.85 | 0x3f59999a | midpoint clamp |
| ToneMath.film | 3, 2 | 0x40400000 | smoothstep |
| ToneMath.film | ±1.25 | 0x3fa00000 / 0xbfa00000 | vignette clamp (EV) |
| ToneMath.film | 2.0 (double) | 0x4000000000000000 | pow base (EV → gain) |
| ToneMath.film | 0.06 | 0x3d75c28f | grain amplitude |
| ToneMath.film | +1, +2, +3 | int | chroma-grain seed offsets |
| ToneMath.noise | 25 | 0x41c80000 | grainSize → cell size |
| ToneMath.noise | 0.7, 100 | 0x3f333333 | roughness clamp |
| ToneMath.noise | +83 | int | white-noise seed offset |
| ToneMath.hash | 0x1f123bb5, 0x5f356495, 0x6c8e9cf5 | int | coordinate multipliers |
| ToneMath.hash | 0x7feb352d, 0x846ca68b; shifts 16, 15, 16 | int | "lowbias32" finalizer |
| ToneMath.hash | 0xFFFFFF, 8388607.5 | 0x4affffff | → [−1, 1) |
| ToneEngine.tone | 3×3 [1 2 1]⊗[1 2 1], /16 | 0x41800000 | local luma for clarity |
| SpatialPass.y | 77, 150, 29, /256 | | integer luma for detail |
| SpatialPass.noise | step h/32, w/64; 0.7413; 1.25 … 18 | 0x3f3dc5d6, 0x3fa00000, 0x41900000 | robust (MAD-like) noise sigma |
| SpatialPass.detail | 0.7·N, ≥ 0.8 | 0x3f333333, 0x3f4ccccd | coring threshold |
| SpatialPass.detail | [1 4 6 4 1]/16 | 0x40800000, 0x40c00000 | 5×5 Gaussian |
| SpatialPass.detail | /8, 2.5, 2 | 0x41000000, 0x40200000 | Sobel, edge gate |
| SpatialPass.detail | 0.55, max 4 | 0x3f0ccccd, 0x40800000 | sharpening gain / clamp |
| NightHighlights | sample step ceil(w/256) | | grid |
| NightHighlights | 77, 204, 0.65, 76.5, 0.15 | 0x3f266666, 0x42990000, 0x3e19999a | night detection |
| NightHighlights | 183 | | shoulder applies above max channel 183 |
| NightHighlights.shoulder | 0.72, 0.28, 0.32 | 0x3f3851ec, 0x3e8f5c29, 0x3ea3d70a | knee, range, softness |
| NightHighlights.limit | 1.2 | 0x3f99999a | gain-ratio cap at night |
| ToneHdr | 384 | 0x43c00000 | max gain-map side |
| ToneHdr | ratioMin 1, ratioMax 2, gamma 1, eps 0, minDisp 1, fullDisp 2 | | gain-map metadata |
| HdrMath.generated | smoothstep(0.36, 0.94) | 0x3eb851ec, 0x3f70a3d7 | gain from luma |
| HdrMath.source | smoothstep(0.1, 0.55); limit 1.8 or 2.0 | 0x3dcccccd, 0x3f0ccccd | gain-map conversion (watermark path) |
| GainPlane.smooth | (a + 2b + c + 2)/4 | | integer blur |
| ToneUi | 0.75 load factor; inSampleSize 4; label 640×72, text 42 px at (320, 53) | | UI |
| ProfessionalSession | see §9 | | palette ColorMatrix |
| Output | JPEG quality 100 (metadata sidecar quality 97) | | encode |

---------------------------------------------------------------------------------------------------------------------------

## 13. Test vectors (from the numpy reference; pass 1 `toned()` only)

Format: input RGB → raw LUT output (0..1) → final pass-1 RGB8.
```
tone 0 : (0,0,0)->(0.0243,0.0419,0.0436)->(9,16,17)     (128,128,128)->(0.5516,0.5497,0.5569)->(147,146,149)
         (255,255,255)->(0.9780,0.9652,0.9782)->(247,242,247)   (200,150,100)->(0.8259,0.6332,0.4435)->(215,167,119)
         (50,100,200)->(0.2497,0.4398,0.8030)->(70,118,207)     (255,0,0)->(0.9411,0.1005,0.0841)->(233,38,32)
         (30,60,40)->(0.1042,0.2434,0.1944)->(25,63,54)
tone 3 : (128,128,128)->(157,154,145)  (200,150,100)->(217,171,110)  (50,100,200)->(44,134,213)  (255,0,0)->(225,84,32)
tone 8 : (0,0,0)->(3,3,4)  (128,128,128)->(120,121,127)  (255,255,255)->(225,225,226)  (200,150,100)->(187,146,101)
         (50,100,200)->(49,93,191)  (255,0,0)->(234,31,23)  (30,60,40)->(27,51,39)
tone 12: (128,128,128)->(129,135,139)  (200,150,100)->(210,171,102)  (255,0,0)->(252,59,0)  (30,60,40)->(13,49,53)
tone 14: (0,0,0)->(15,16,16)  (128,128,128)->(144,142,142)  (255,255,255)->(249,249,248)  (200,150,100)->(199,163,116)
         (50,100,200)->(85,103,167)  (255,0,0)->(217,64,32)  (30,60,40)->(24,37,43)
tone 22: (128,128,128)->(148,143,164)  (200,150,100)->(228,177,139)  (50,100,200)->(98,74,225)  (255,0,0)->(255,123,98)
hash(0,0,0) = -1.0000000   hash(1,2,3) = -0.7323514   hash(-1,5,16) = 0.8485143
noise(x,y,seed=14,size=10,rough=30) at (0,0),(7,3),(100,50),(1234,987) = 0.418463, -0.081338, 0.369266, -0.000567
intensify(0.4, 0.5, 1.5) = 0.545455     intensify(0.6, 0.5, 1.75) = 0.434783
```
`scratchpad/work/cameralook_ref.py <lutdir> <idx> in.jpg out.jpg [maxdim]` runs the whole Java pipeline (passes 1–3).
Use it to generate more vectors or golden images for the Kotlin/GL port.

---------------------------------------------------------------------------------------------------------------------------

## 14. Uncertainties / caveats

* **UNCERTAIN**: `NativeM9.hdrSample` (native downsample used for the gain map); assumed to be an area-average resize.
* **UNCERTAIN / not decoded**: native `DenoiseFlow.runNative`, `SpatialPass.denoise`, `ChromaPass.apply` (pre-tone NR),
  `PalettePostProcessor.texture` and `ProfessionalPalette.apply` (Professional mode only).
* Pass 2 and pass 3 neighbourhoods read the pre-pass image. The Java rolling buffers read rows from the bitmap before those
  rows are overwritten. In pass 3 the next row is read via `getPixels` after the current row's `setPixels`, but the row read is
  y+2, which is untouched, so every pass is a pure function of the previous pass's output.
* The English names and the "Kind" column are my interpretation. The APK only has the Chinese/English strings shown.
* The LUTs are 8-bit sRGB in → sRGB out and assume the Android/Xiaomi-style camera JPEG rendering. On a Sony phone, feed the
  final sRGB JPEG/YUV→RGB output, not linear or RAW data.
