# donor120 "Leica style" engine: reimplementation spec (Xperia 1 IV port, ORT Android + GLES)

Sources: dexdump of `classes24.dex` (packages `com.codex.donor120`, `com.codex.model120`, `com.codex.modelport`),
plus `classes32` (`com.codex.quality80.MonoRows`), `classes17` (`com.codex.fullm9.LeicaParams`, a Java parser of the
leica_filter_param bins), and `classes11` (`com.codex.leicaapp.LegendaryPostProcessor` constants). `ai.dnoxruntime.*` is the
ONNX Runtime Java API under a relocated package name, and `lmktln.*` is relocated Kotlin stdlib.
Float and double constants were decoded from the raw bit patterns (exact values are given where they matter).
Empirical numbers come from ORT 1.19.2 CPU EP with 8 intra-op threads on an Apple M1, using `cameralook/intro-0.jpg` (1536x1024).

Legend: **[EXACT]** = read directly from bytecode. **UNCERTAIN** = native or closed code, best guess given.

---------------------------------------------------------------------------------------------------------------

## 0. What is portable

| Component | Kind | Portable to Xperia? | Asset present in extract? |
|---|---|---|---|
| `M9Distill` (m9_distill.onnx) | ORT, pure Java pre/post | YES | yes, donor120/m9_distill.onnx |
| `M9OrtFallback` (m9fb_styletrans_high.onnx) | ORT + Java detail transfer | YES (slow) | yes |
| `Lm9Stats` | pure Java statistics + curves | YES | no asset needed |
| `Lm9Net` (lm9_net.onnx) | ORT | YES if model found | **missing** (`donor120/lm9_net.onnx`) |
| `PortraitFilm` (seplut_fivek.onnx) | ORT + Java | YES if model found | **missing** (`donor120/seplut_fivek.onnx`) |
| `M3Mono` -> `quality80.MonoRows` | pure Java | YES | none |
| `MadridShader` (vignette + grain) | pure Java | YES | none |
| `boostSaturationContrast` | Android ColorMatrix | YES | none |
| `AlexaLut` (M4, LP9) | pure Java 65^3 LUT | YES if LUT found | **missing** (`lut_alexa.bin`, `lut_p9vibrant.bin`) |
| `LegendaryPostProcessor.processM9/M9Plus/M10R/M3` | native `libd12camodelembed.so` + exec `libd12letrans_runner.so` (Xiaomi styletrans on QNN HTP) | NO | libs not in extract |
| `LegendaryPostProcessor.applyWarmOriginalFilter` (leica_filter_param LUT + "warm") | **native** | Reimplement (sec. 5), **UNCERTAIN** in part | bins yes |
| `MadridLegendary.process` (filter / grain) | native `libd12ridlegend.so`, exec `libd12opan_runner.so` | Reimplement as LUT (sec. 5) | bins yes |
| `M9CpuFallback` | exec `libd12letrans_cpu.so` + `m9stub/libcdsprpc.so` | NO | – |

Asset path rule (`ModelAssets.path`): a name is looked up as `assets/donor120/<name>` unless it is in the SHARED list
(`leica_filter_param_m3.bin`, `leica_filter_param_m9_snapshot.bin`, `legend18/*.bin`, `leica-v4/dsp/libQnnHtpV81.so`,
`leica-v4/human_segmentation_768/...pipeline`, `leica-v4/styletrans_params.json`). Shared names resolve to `assets/<name>`.
So `leica_filter_param_m9_snapshot.bin` is at the assets root (use the `legend18/` copy, which is the same format), and
`leica_filter_param_m10r_snapshot.bin` is at `donor120/`.

On the Xperia (`Build.SOC_MANUFACTURER` = "QTI"), `isNonQualcommSoC()` returns false, so the original app would try the
Xiaomi native chain first, have it fail, and fall through to the fallbacks. Your port should go straight to the fallbacks.

---------------------------------------------------------------------------------------------------------------

## 1. `LeicaEngine.Style` and the dispatcher  [EXACT]

`enum Style(id, label, desc)` in declaration (ordinal) order. `Companion.fromId(id)` matches on `id` and falls back to `M9`.

| ord | enum | id | label | desc |
|---|---|---|---|---|
| 0 | M3 | "M3" | "M3" | "黑白经典" (classic B/W) |
| 1 | M9 | "M9" | "M9" | "彩色经典" (classic colour) |
| 2 | M9P | "M9P" | "M9+" | "彩色经典·激进版" (classic colour, aggressive) |
| 3 | M10R | "M10R" | "M10-R" | "暗角颗粒" (vignette + grain) |
| 4 | M4 | "M4" | "ARRI 2049" | "浓青橙 · 电影暗调" (teal-orange, dark cinema) |
| 5 | M13 | "M13" | "CCD" | "冷白胶片" (cool-white film) |
| 6 | LP9 | "LP9" | "L-P9" | "P9 徕卡鲜艳" (P9 Leica vivid) |
| 7 | LM9 | "LM9" | "L-M9" | "一瞬 M9 · CCD" |
| 8 | M9D | "M9D" | "M9 蒸馏" (M9 distilled) | "一体化微网" (all-in-one micro-net) |

```kotlin
fun process(ctx, jpeg: ByteArray, style: Style, onFallback: ((Style)->Unit)? = null, allowCpuFallback: Boolean = true): ByteArray {
    val j = ImageUtils.normalizeExifOrientation(jpeg)   // bake EXIF rotation/flip into pixels, re-encode q100, copy EXIF, set Orientation=1
    return when (style) {
        M3 -> processM3(ctx, j, onFallback);       M9 -> processM9(ctx, j, onFallback, allowCpuFallback)
        M9P -> processM9Plus(ctx, j, onFallback, allowCpuFallback)
        M10R -> processM10R(ctx, j, onFallback, allowCpuFallback)
        M4 -> processM4(ctx, j, onFallback);       M13 -> processM13(ctx, j, onFallback)
        LP9 -> processLP9(ctx, j, onFallback);     LM9 -> processLM9(ctx, j, onFallback, allowCpuFallback)
        M9D -> processM9D(ctx, j, onFallback)
    }
}
```
`onFallback(style)` is called only when the chain degrades to the last-resort path.

### Per-style chains (first non-null result wins). `->` means "then post-process with".

* **M9**: `LPP.processM9` (native, unavailable) → [if allowCpu] `M9CpuFallback.run` (native exec, unavailable) →
  **`M9Distill.run(0.6, -0.95, 0.3, "leica_filter_param_m9_snapshot.bin")`** → `M9OrtFallback.run(same args)` →
  `MadridLegendary.process("M9")` (native LUT filter, see sec. 5).
* **M9P (M9+)**: `LPP.processM9Plus` (native) -> boost; [allowCpu] `M9CpuFallback` -> `LPP.applyWarmLut(0.6,-1.3,0.5)` -> boost;
  **`M9Distill.run(0.6, -1.3, 0.5, m9 bin)` -> boost**; `M9OrtFallback.run(0.6,-1.3,0.5)` -> boost; `MadridLegendary("M9")` -> boost.
  Here boost = `boostSaturationContrast(sat = 1.25, contrast = 1.1, warm = 0.04)` (sec. 10).
* **M10R**: first `j = MadridLegendary.process(j, "M10R")` (native filter with the m10r bin; **its LUTs are identity**, so this is
  ~no-op apart from the unknown shading step). Then `LPP.processM10R` (native) → [allowCpu] `M9CpuFallback` →
  **`M9Distill.run(0.6, -0.95, 0.3, "leica_filter_param_m10r_snapshot.bin")`** → `M9OrtFallback.run(same)` → fall back to `j`.
  **Whichever result is used goes through `MadridShader.applyToJpeg(x, grain = 0.3)`** (vignette strength 1.0).
* **M9D**: **`M9Distill.run(defaults = 0.6, -0.95, 0.3, m9 bin)`** → `M9OrtFallback.run(defaults)` → `MadridLegendary("M9")`. No CPU path.
* **LM9 (L-M9)**: **`Lm9Stats.run`** (pure Java; returns null only on exception) → `Lm9Net.run` (lm9_net.onnx) → `processM9(...)`.
* **M3**: `LPP.processM3` (native: m3 bin filter + native "monopan" grain) → `M3Mono.ensure`; fallback: `MadridLegendary("M3")` → `M3Mono.ensure`.
  `M3Mono.ensure` = `quality80.MonoRows.ensure` (pure Java mono conversion, sec. 8). Because the m3 bin LUTs are identity,
  **port M3 = MonoRows.convert only**.
* **M13 (CCD)**: **`PortraitFilm.process`** (seplut_fivek.onnx) → `MadridShader.applyToJpeg(x, grain = 0.18)`; fallback `MadridLegendary("M9")`.
* **M4 (ARRI 2049)**: `AlexaLut.process("lut_alexa.bin")` → `MadridShader.applyToJpeg(x, grain = 0.22)`; on LUT failure the original image is returned.
* **LP9**: `AlexaLut.process("lut_p9vibrant.bin", hooks = P9Post)` → `MadridShader.applyToJpeg(x, grain = 0.0, vignette = 0.3)`.

### Recommended port chains

| Style | Port pipeline |
|---|---|
| M9 / M9D | decode → M9Distill → LeicaLut(m9 bin, "common", lux 200, CCT 5500) + warm(0.6, -0.95, 0.3) **UNCERTAIN** → JPEG q95 |
| M9+ | the same with warm(0.6, -1.3, 0.5), then boost(1.25, 1.1, 0.04) |
| M10R | M9Distill → (m10r LUT = identity, so skip it; warm(0.6, -0.95, 0.3) **UNCERTAIN**) → MadridShader(grain 0.3, vig 1.0) |
| L-M9 | Lm9Stats (exact) |
| M3 | MonoRows.convert (exact) |
| M13 | PortraitFilm (needs seplut_fivek.onnx) → MadridShader(0.18) |
| M4 / LP9 | AlexaLut (needs LUT files) → MadridShader |

Optional quality path for M9/M9D: use M9OrtFallback (styletrans) instead of M9Distill. It takes about 5 s per photo on M1 (sec. 4).

---------------------------------------------------------------------------------------------------------------

## 2. Common I/O conventions  [EXACT]

* Decode: `BitmapFactory` ARGB_8888 (sRGB colour space for the model120 paths), then `DenoiseFlow.prepare(bmp)` = app-wide hook
  (`com.codex.fullm9.OutputQuality.prepare`, a denoise/detail pre-pass from another package). For the port, treat it as identity or use your own NR.
* All model math runs on **gamma-encoded sRGB 8-bit values** (no linearisation) in **RGB order**, NCHW planar.
* JPEG qualities: M9Distill / M9OrtFallback / Lm9Net / M9CpuFallback / AlexaLut / MadridLegendary = 95; Lm9Stats = 98; PortraitFilm = 96;
  boost, MadridShader, LPP.encode, normalizeExif and MonoRows = 100. EXIF is copied from the source JPEG (APP1 "Exif\0\0" segment).
* `Kotlin roundToInt` / `Math.round(float)` = `floor(x + 0.5)`. "float-to-int" = truncation toward 0.
* Android `createScaledBitmap(..., filter=true)` = bilinear (no mip-mapping, so it aliases on big downscales).

---------------------------------------------------------------------------------------------------------------

## 3. M9Distill (m9_distill.onnx)  [EXACT] + empirical

Constants: `TILE KEEP = 1024`, `HALO = 96`, `MIN_CANVAS = 256`, `W4K = 4096`, `H4K = 3072`, `INV255 = 0.00392157f` (bits 0x3b808081).
ORT: `OrtEnvironment.getEnvironment()`, `SessionOptions().setIntraOpNumThreads(8)`, created from bytes. **No EP is registered (CPU EP)**,
and the graph optimisation level is left at the default (ORT_ENABLE_ALL). The session is a lazily created singleton.
The result is cached by CRC32 of the input JPEG bytes (1 entry).

### 3.1 Model graph (opset 17, 163,671 params, PyTorch export)
Inputs `lowres [b,3,lh,lw]` and `full [b,3,h,w]`, both float in [0,1]. Output `out [b,3,h,w]` in [0,1].
It is an HDRNet-style **locally adaptive affine colour transform**:
```
feat  = Enc(lowres)            // 4x Conv3x3 s2 (48ch)+ReLU, 2x(Conv3x3+ReLU)x2  → lowres/16
grid  = Conv1x1(feat) : 12ch   // 9 = 3x3 matrix (index out*3+in), 3 = bias
grid  = Resize_bilinear_halfpixel(grid, (h/16, w/16)); grid = Resize_bilinear_halfpixel(grid, (h, w))
A[o][i](x,y) = grid[o*3+i];  b[o] = grid[9+o]
y1[o] = Σ_i A[o][i]*full[i] + b[o]
res = Dec(feat): Up2x→Conv3x3(24)+ReLU→Up2x→Conv3x3(24)+ReLU→Conv3x3(3)  (at lowres/4) → Resize bilinear to (h,w) → clip[-16,16]
out = clip(y1 + res, 0, 1)
```
For each pixel the output is **affine in that pixel's RGB**. The coefficients depend only on low-res content: the map is spatially varying
but smooth and content-driven, not a global LUT.

### 3.2 Pipeline
```kotlin
fun run(ctx, jpeg, a: Float = 0.6f, b: Float = -0.95f, c: Float = 0.3f, warmBin: String = "leica_filter_param_m9_snapshot.bin"): ByteArray? {
    val styled = styledCached(crc32(jpeg)) ?: return null           // ARGB bitmap, same size/orientation as input
    val m = styled.copy(ARGB_8888, mutable = true)
    val warmed = LegendaryPostProcessor.applyWarmLutBitmapBin(ctx, m, warmBin, a, b, c) ?: m   // sec. 5 (native in original)
    return copyExif(jpeg, NativeQuality.encodeModel(warmed, JPEG, 95))
}

fun styledInternal(jpeg): Bitmap? {
    var bmp = prepare(decode(jpeg))
    val portrait = bmp.height > bmp.width
    val src = if (portrait) rotate(bmp, -90f) else bmp               // Matrix.setRotate(-90), filter=true
    // fit(): if NativeQuality.nativeTiles(bmp) keep size; else if w>4096||h>3072: s=min(4096f/w, 3072f/h); scale to (int)(w*s),(int)(h*s) bilinear
    var work = fit(src); val fitW = work.width; val fitH = work.height
    if (min(fitW, fitH) < 256) { val s = 256.0 / min(fitW, fitH); work = scale(work, ceil(fitW*s).toInt(), ceil(fitH*s).toInt()) }
    val px = tiledInfer(work) ?: return null
    var out = Bitmap(work.width, work.height, px)
    if (out.size != (fitW, fitH)) out = scale(out, fitW, fitH)
    if ((fitW, fitH) != src.size) out = scale(out, src.width, src.height)
    return if (portrait) rotate(out, +90f) else out
}

fun tileOffsets(dim: Int, T: Int): IntArray =
    if (dim <= T) intArrayOf(0) else IntArray((dim + T - 1) / T) { i -> min(i * T, dim - T) }

fun tiledInfer(bmp): IntArray? {
    val W = bmp.width; val H = bmp.height
    var T = min(1024, min(W, H)); T -= T and 1                  // even
    if (T < 16) return null
    val C = T + 192                                             // canvas with 96-px halo each side
    val L = C / 2
    val out = IntArray(W * H)
    for (y0 in tileOffsets(H, T)) for (x0 in tileOffsets(W, T)) {      // y outer, x inner. Later tiles overwrite overlaps (no feathering)
        val block = readBlock(bmp, x0, y0, C)                    // C*C ARGB, edge-clamped (replicate) outside the image
        val low = boxDown2(block, C)                             // L*L
        val full = planesCHW(block) ; val lowres = planesCHW(low)    // value = channel8 * 0.00392157f; planes R,G,B
        val o = session.run(mapOf("lowres" to tensor(lowres, [1,3,L,L]), "full" to tensor(full, [1,3,C,C])))[0] // [1,3,C,C]
        for (r in 0 until T) { val y = y0 + r; if (y >= H) break
            for (cIdx in 0 until T) { val x = x0 + cIdx; if (x >= W) break
                val p = (r + 96) * C + 96 + cIdx
                val R = clamp(round(o[p] * 255f), 0, 255); val G = clamp(round(o[C*C + p] * 255f), 0, 255); val B = clamp(round(o[2*C*C + p] * 255f), 0, 255)
                out[y * W + x] = 0xFF000000 or (R shl 16) or (G shl 8) or B } }
    }
    return out
}
// readBlock: x in [x0-96, x0-96+C), y likewise; sample (clamp(x,0,W-1), clamp(y,0,H-1))
// boxDown2: for each 2x2 cell and each channel: (a+b+c+d+2) >> 2      (integer, rounded)
```
Note: for any photo whose shorter side is at least 1024 px, every tile has **fixed shape C=1216 / L=608**. That lets you make the model static-shape
(`python -m onnxruntime.tools.make_dynamic_shape_fixed`) for NNAPI or XNNPACK.

### 3.3 Empirical results (intro-0.jpg 1536x1024 → T=1024, C=1216, 2 tiles)
* Timing (M1, 8 thr): first run 0.10 s per tile, warm **55–75 ms per 1216² tile**. A single pass at 1536x1024 (no tiling) takes 82 ms.
  A 4096x3072 photo needs 4x3 = 12 tiles, about 0.8 s on M1 (rough estimate: 2–4x slower on the SD 8 Gen 1 CPU).
* Effect size: mean |out − in| = 4.66 /255. Per-channel means change from (96.5, 90.8, 79.2) to (95.7, 89.6, 75.1): a slightly darker, cooler blue channel and more saturation.
  The best global affine fit has diagonal about (1.22, 1.10, 1.16) with off-diagonals ≈ −0.05…−0.10, i.e. a saturation boost.
* **Spatial test** (pixels of `full` shuffled, `lowres` fixed, then unshuffled): mean diff **4.18**, p99 13.4, max 29 (0–255 units), so the transform is **NOT a global per-pixel map**.
  A constant grey-128 `full` maps to values from 117 to 138 depending on position (std ≈ 4). Swapping `lowres` for another image changes the output by 4.4 on average.
  The tile-overlap disagreement between adjacent tiles is only 0.017 mean (p99 0.27), so the field is content-driven, not tile-position-driven, and there are no seams.
* **3D-LUT baking**: a global affine fit gives error mean 2.50 / p99 8.6 / max 26.5. A 33³ LUT fitted on the image itself gives mean **2.15** / p99 7.8 / max 18.3.
  A 33³ LUT obtained by evaluating the model on a colour grid (with this image's lowres) gives mean 3.54 / p99 11.1. A static LUT captures only about half of the effect.
* **Better realtime route (validated): coefficient maps.** Add graph outputs `/head_grid/Conv_output_0` (12 × lh/16 × lw/16) and
  `/dec/dec.6/Conv_output_0` (3 × lh/4 × lw/4) (file produced: `work/d120/m9_distill_coef.onnx`). Run on a small analysis frame, upload both as
  float textures (GL_LINEAR), and in the fragment shader compute `out = clamp(A(uv)·rgb + b(uv) + clamp(res(uv), -16, 16), 0, 1)`.
  Error against the full tiled output on the full-res image:

  | analysis frame (full / lowres) | infer (M1) | grid | err mean / p99 / max |
  |---|---|---|---|
  | 1536x1024 / 768x512 | 82 ms | 12x32x48 | **0.36 / 2.2 / 8.7** |
  | 768x512 / 384x256 | 54 ms | 12x16x24 | 1.31 / 4.9 / 13 |
  | 384x256 / 192x128 | 10 ms | 12x8x12 | 2.04 / 7.3 / 17 |
  | 192x128 / 96x64 | 1.3 ms | 12x4x6 | 3.48 / 11 / 20 |

  For preview, use a ~768x576 analysis frame refreshed every N frames. The model is scale-sensitive: the receptive field is fixed in pixels.
* Distill vs the styletrans chain (M9OrtFallback) on the same image: mean diff 8.5. Distill is much milder (4.7 vs 10.2 change from input).
  The two are **not** interchangeable looks.

---------------------------------------------------------------------------------------------------------------

## 4. M9OrtFallback (m9fb_styletrans_high.onnx = ONNX port of Xiaomi `styletrans_high.minn`)  [EXACT] + empirical

Constants: `NW = 1024`, `NH = 768`, `PAD = 48` (→ 1120x864), `DETAIL_SIGMA = 2f` (declared, **unused**; code passes sigma 1.0f), `W4K = 4096`, `H4K = 3072`.
ORT: intra-op threads 8, CPU EP, from bytes. Cache by CRC32.

### 4.1 Model
Opset 18, 3.84 M params, about 487 Conv and 487 Clip (ReLU-like clamps with odd bounds inherited from the QNN encodings), 182 Concat, 2 Resize.
* `rgb [N,3,864,1120]` = (c8 − 128) / 127.5 (from styletrans_params.json: offset −128, delta 0.007843).
* `mask [N,1,864,1120]`: inside the graph it is AveragePool 4x4 and concatenated after a 4x pixel-unshuffle of rgb.
  This is the 4th input channel of the original Xiaomi model (input shape [1,864,1120,4]). The likely source was the
  `human_segmentation_768` person mask (1 = person). **UNCERTAIN.** **donor120 always passes zeros** (a fresh `allocateDirect` buffer).
  Measured effect: all-ones vs zeros gives mean |Δ| 7.0 (p99 16), 0.5 gives 3.4. **Pass zeros** to match the donor. A person mask is optional/experimental.
* `out [N,3,864,1120]` in about [−1, 1]: c8 = (int)(v*127.5 + 128), clamped to 0..255.

### 4.2 Pipeline
```kotlin
fun styledInternal(jpeg): Bitmap? {
    var bmp = prepare(decode(jpeg)); val portrait = bmp.height > bmp.width
    val src = if (portrait) rotate(bmp, -90f) else bmp
    val img = fit(src)                                   // <= 4096x3072, as in M9Distill but without the nativeTiles shortcut
    val (W, H) = img.size
    val (cw, ch) = canvasOf(W, H)
    val canvas = padMirror(img, cw, ch)                  // centred: offset ((cw-W)/2, (ch-H)/2), Shader.TileMode.MIRROR (edge pixel repeated)
    val small = scale(canvas, 1024, 768)                 // bilinear
    val tile = padMirror(small, 1120, 864)               // 48 px mirror border
    val rgb = FloatArray(3*864*1120) { planar: (c8 - 128) / 127.5f }
    val mask = FloatArray(864*1120)                      // zeros
    val o = session.run(mapOf("rgb" to [1,3,864,1120], "mask" to [1,1,864,1120]))[0]
    val sty = 1024x768 ARGB: for y<768,x<1024: p=(y+48)*1120+48+x; c = clamp((o[c*967680+p]*127.5f + 128f).toInt(), 0, 255)
    val styUp = scale(sty, cw, ch)                                   // bilinear
    val half = scale(canvas, max(1, cw/2), max(1, ch/2))
    val blurUp = scale(gaussianBlurARGB(half, sigma = 1.0f), cw, ch)
    // crop W x H at ((cw-W)/2, (ch-H)/2) from canvas (orig), blurUp, styUp
    out[i] = clamp(sty[i] + orig[i] - blur[i], 0, 255) per channel       // detail transfer (high-pass of original added back)
    var res = Bitmap(W, H, out); if (res.size != src.size) res = scale(res, src.width, src.height)
    return if (portrait) rotate(res, +90f) else res
}
fun canvasOf(w: Int, h: Int): Pair<Int,Int> =
    if (3L*w >= 4L*h) { val cw = min(w, 4096); cw to min(max(h, Math.round(cw*3f/4f)), 3072) }
    else              { val ch = min(h, 3072); min(max(w, Math.round(ch*4f/3f)), 4096) to ch }
// gaussianBlurARGB(px,w,h,sigma): boxes = boxesForGauss(sigma, 3); for b in boxes if b>=1 { boxBlurH(src->tmp, r=b); boxBlurV(tmp->src, r=b) }
// boxesForGauss(s,n): wIdeal=floor(sqrt(12s²/n+1)); wl = wIdeal (if even: wl-1); wu=wl+2;
//    m = round((12s² - n*wl² - 4n*wl - 3n) / (-4wl - 4)); sizes[i] = if (i<m) wl else wu      → sigma 1: [1,1,3]
//    NOTE: sizes are then used directly as RADII (window 2r+1 = 3,3,7), so the effective sigma is about 2.3 px at half-res (~4.6 px full-res).
// boxBlurH/V: integer running sum over window [x-r, x+r] with clamped (edge-replicated) indices, out = sum / (2r+1) (int division)
```
Then `run()` = copy → `applyWarmLutBitmapBin(warmBin, a, b, c)` → JPEG q95 + EXIF (the same as M9Distill).

### 4.3 Empirical
* One inference (1120x864) on M1, 8 threads: **4.5–5.0 s** (session create 0.08 s). Expect roughly 8–20 s on SD 8 Gen 1 CPU.
  NNAPI/GPU may fail on the Clip/Concat-heavy graph. Try XNNPACK EP with 4 threads (estimate only).
* The output is spatially varying (shuffle test mean |Δ| 22.8). A global affine fit has error mean 3.8 / p99 14.7. On intro-0 the change is mean |out−in| 6.6 at 1024x768;
  after detail transfer the full-res change is 10.2.

---------------------------------------------------------------------------------------------------------------

## 5. leica_filter_param bins: LUT selection and the "warm original filter"

### 5.1 File format (confirmed by Java parser `com.codex.fullm9.LeicaParams`)  [EXACT]
All fields are little-endian u16 unless noted.
```
u16 nScenes(=6); u16 sceneBlockSize[nScenes] (=216 each)
u16 sceneTableOff (=0x400); u16 lutOff (=0x1000); u16 lutDim (must be 17)
u16 nZooms (=5); u16 zoomBlockSize[nZooms] (=102 each)
@sceneTableOff, for each scene (stride sceneBlockSize): char name[64] (NUL-terminated) + TREE
   names: "common","protrait"(sic),"night","plants","food","sunrise_sunset"
@after scenes, for each zoom (stride zoomBlockSize): f32 zoom + TREE  → indexes "shading records"
TREE := u16 nLux; repeat nLux { u16 luxLo; u16 luxHi; u16 nCct; repeat nCct { u16 cctLo; u16 cctHi; u16 index } }
LUTs @lutOff: lutCount * 17*17*17*3 bytes (14739 each), uint8 RGB, **R fastest**: off = ((b*17+g)*17+r)*3
   lutCount = max(index)+1 over all scenes (m9: 114, m10r: 108)
Shading records @shadeOff = lutOff + lutCount*14739: 8 x f32 each (32 B). m9 and m10r both have 55 records.
```
m9 `common` tree: lux [0..220]: CCT [1..5500]→0, [6000..10000]→1; lux [240..300], [320..380], [400..470], [490..530]: CCT
[1..2450],[2650..3350],[3600..4400],[4700..10000] → 2–5, 6–9, 10–13, 14–17; lux [550..999] → 18. The other scenes follow the same layout
(19 LUTs each: protrait 19–37, night 38–56, plants 57–75, food 76–94, sunrise_sunset 95–113).
Zoom trees (0.6, 1.0, 3.0, 4.3, 10.0): 5 lux bins x {1 or 3 CCT bins}, 11 records per zoom.
Content: m9 LUT 0 brightens mid-greys (64→66, 128→132, 191→200) and saturates/warms (skin 223,159,128 → 238,164,124). Some LUTs (e.g. #2, #18) are near
identity. **All m10r and m3 LUTs are identity.** Example record (zoom 1.0, lux 200–260, CCT ≥ 4800 → #14): `[1.8, 0.1, 1.0, 1.02, 4.3, 0.6, 1.15, 0.1]`.
The meaning of the 8 shading floats is **UNCERTAIN** (provenance.json calls them "shading_records" with "neutral_gain_removed").

### 5.2 Selection with interpolation (`LeicaParams.lut / corners / find / fraction`)  [EXACT, from the fullm9 Java twin]
```kotlin
fun find(bins: List<Bin>, v: Int): Pair<Bin,Bin> {
    for ((i, b) in bins.withIndex()) if (v <= b.hi) return if (v >= b.lo || i == 0) b to b else bins[i-1] to b   // inside, or in the gap
    return bins.last() to bins.last()
}
fun fraction(v: Int, a: Bin, b: Bin): Float { val d = b.lo - a.hi; return if (d == 0) 0f else (v - a.hi).toFloat() / d }
fun corners(tree, lux, cct): Map<Int, Float> {            // bilinear over (lux gap, cct gap)
    val (l0, l1) = find(tree, lux); val (a0, a1) = find(l0.sub, cct); val (b0, b1) = find(l1.sub, cct)
    val fl = fraction(lux, l0, l1); val fa = fraction(cct, a0, a1); val fb = fraction(cct, b0, b1)
    return accumulate(a0.index to (1-fl)*(1-fa), a1.index to (1-fl)*fa, b0.index to fl*(1-fb), b1.index to fl*fb)
}
fun lut(scene: String, lux: Int, cct: Int): ByteArray {   // scene missing -> "common"
    val w = corners(scenes[scene] ?: scenes["common"], lux, cct)
    if (w.size == 1) return raw.copyOfRange(lutOff + idx*14739, ... +14739)
    return ByteArray(14739) { k -> (Σ w_i * (raw[lutOff+idx_i*14739+k] & 0xFF)).toInt().toByte() }   // truncation
}
fun shading(zoom: Float, lux: Int, cct: Int): FloatArray   // zoom tree = last i with zoom >= zooms[i] (default 0); weighted sum of 8-float records
```
**donor120 always calls the native filter with lux = 200 and CCT = 5500**, zoom = 1.0, seed = `SystemClock.uptimeMillis() % 60000`.
So the m9 bin resolves to scene "common", **LUT #0 only** (5500 is inside [1..5500]). The m10r and m3 bins resolve to identity LUTs.
The shading record is #14 (zoom 1.0; lux bin 200..260; CCT bin 4800..10000).
For the port you can feed real values: Sony `CaptureResult` does not give Xiaomi lux_index, so derive a proxy (≈ 0–220 = bright daylight
… 550+ = very dark) and CCT from AWB gains. Daylight returns LUT 0 or 1.

### 5.3 Applying the 17³ LUT (port)
In the original this is native (`applyWarmOriginalFilter(bmp, binPath, lux=200f, cct=5500f, zoom=1f, seed, a, b, c)` in `libd12camodelembed.so`;
`MadridLegendary.filter(bmp, path, 200f, 5500f, 1f, 0f)` in `libd12ridlegend.so`). Port as a standard trilinear 17³ lookup on 8-bit sRGB:
`p = c/255*16; i0 = floor(p) clamped to 0..15; f = p - i0`, index `((b*17+g)*17+r)*3`. GLES: upload as a GL_TEXTURE_3D (RGB8, 17³, LINEAR) and sample at
`(c*16+0.5)/17`. **UNCERTAIN**: tetrahedral vs trilinear interpolation, and whether shading is applied.

### 5.4 The three "warm" floats (a, b, c)  **UNCERTAIN (native)**
Names from `model120.LegendaryPostProcessor` / `leicaapp.LegendaryPostProcessor`: `M9P_DENOISE = 0.6`, `M9P_EV = -1.3`, `M9P_WARMTH = 0.5`;
`M9_DENSITY_EV = -0.95`, `M9_NEUTRAL_WARMTH = 0.3`, `WARM_GRAY_M9 = true`. So **(a, b, c) = (denoise strength, "density" EV, neutral-grey warmth)**.
M9/M9D/M10R use (0.6, −0.95, 0.3) and M9+ uses (0.6, −1.3, 0.5). The algorithm is not in Java. Best guess, to be tuned by eye:
* density EV: film "density" = darken saturated colours, not a global exposure change, e.g. `rgb *= mix(1, 2^(ev*0.25), sat)` (**guess**).
* neutral warmth: push low-chroma pixels warm, e.g. `w = c*0.03*(1-sat)*4Y(1-Y); R += w; B -= w` (**guess**).
* denoise 0.6: chroma NR strength (skip, or use your own).
Treat these as tunables, not reproductions.

---------------------------------------------------------------------------------------------------------------

## 6. M9CpuFallback (not portable, documented for completeness)  [EXACT]
Spawns `nativeLibraryDir/libd12letrans_cpu.so` (an executable) with argv
`[exe, nativeLibDir, filesDir/donor120/leica-v4, cache/donor120-m9cpu/in.raw, out.raw, W, H, "0", "200", "5500"]`
(orientation, lux index, CCT). Env: `LD_LIBRARY_PATH = nativeLibDir:filesDir/donor120/m9stub:/vendor/lib64` and `LD_PRELOAD` = the existing libc++ copies.
Input = EXIF-upright image cropped to even W,H, **RGB565 big-endian** (`(r&0xF8)<<8 | (g&0xFC)<<3 | b>>3`, high byte first).
Output = W*H*3 RGB888. Exit 0 = OK. JPEG q95. It runs the Xiaomi styletrans .minn on a CPU/QNN stub.
**Replace with M9OrtFallback**, which is the same network as ONNX.

---------------------------------------------------------------------------------------------------------------

## 7. L-M9: Lm9Stats (pure Java) and Lm9Net

### 7.1 Lm9Stats.run  [EXACT]
```kotlin
val L_SKY = floatArrayOf(...)  // see constants below
fun run(jpeg): ByteArray? {           // null only on exception
    val bmp = prepare(decode(jpeg)); val W = bmp.width; val H = bmp.height
    // --- probe ---
    val ph = max(1, Math.round(H * 320f / W)); val probe = scale(bmp, 320, ph).pixels      // bilinear
    val bucket = bucketOf(probe)     // used ONLY for logging (KEYS); MU/SD/CORPUS tables are unused
    val sum = Array(3){DoubleArray(3)}; val n = IntArray(3)
    for (p in probe) { val r,g,b = p ints; val Y = (0.2126*r + 0.7152*g + 0.0722*b)/255.0
        val cls = if (b > g+10 && b > r+20 && Y > 0.35) 0 /*sky*/ else if (g > r+8 && g > b+8) 1 /*veg*/ else 2 /*other*/
        sum[cls][0]+=r; sum[cls][1]+=g; sum[cls][2]+=b; n[cls]++ }
    val mu = Array(3){ k -> FloatArray(3){ c -> (sum[k][c] / max(n[k],1) / 255.0).toFloat() } }
    // --- per-class, per-channel curves ---
    val M = arrayOf(f(0.919f,0.953f,0.959f), f(0.70f,0.74f,0.70f), f(0.72f,0.74f,0.76f))     // mean multiplier  (sky, veg, other)
    val K = arrayOf(f(1.062f,1.028f,1.0f),   f(0.87f,0.953f,0.858f), f(0.892f,0.859f,0.864f)) // slope about mean
    val curve = Array(3){k -> Array(3){c -> FloatArray(256){ i -> val x=i/255f
        clamp((x - mu[k][c]) * K[k][c] + mu[k][c] * M[k][c], 0f, 1f) * 255f }}}
    // --- full-res soft segmentation + 1-D error diffusion per row ---
    for (y in 0 until H) { var eR=0f; var eG=0f; var eB=0f
      for (x in 0 until W) { val r=R/255f; val g=G/255f; val b=B/255f      // floats
        val Y = 0.2126f*r + 0.7152f*g + 0.0722f*b
        val blue = clamp((b - g - 0.024f)/0.078f, 0f, 1f)
        val veg0 = clamp((g - max(r,b) - 0.016f)/0.063f, 0f, 1f)
        val warm = clamp((r - b - 0.02f)/0.06f, 0f, 1f) * clamp((Y - 0.45f)/0.15f, 0f, 1f)
        var sky = max(blue * clamp((Y - 0.30f)/0.15f, 0f, 1f), warm)
        val hl = clamp((Y - 0.80f)/0.15f, 0f, 1f); sky = sky*(1-hl) + hl          // highlights -> "sky" curve
        val veg = veg0 * (1 - sky); val oth = max(0f, (1 - sky) - veg); val s = sky + veg + oth
        val fr = (curve[0][0][R]*sky + curve[1][0][R]*veg + curve[2][0][R]*oth)/s + eR   // same for G (ch1), B (ch2)
        val ir = Math.round(fr); eR = clamp(fr - ir, -1f, 1f)  // carry quantisation error to next pixel (reset each row)
        out = rgb(clamp(ir,0,255), clamp(ig,0,255), clamp(ib,0,255)) } }
    return copyExif(jpeg, encodeJpeg(out, 98))
}
// bucketOf (logging only): meanY over probe; skyFrac = #(b>g+10 && b>r+20 && Y>0.35)/N; vegFrac = #(g>r+8 && g>b+8)/N
// tag = (skyFrac>0.12 ? "sky":"") + (vegFrac>0.12 ? "veg":"") or "plain"; prefix A if meanY>=0.5, B if >=0.3, else C → index in KEYS (default 3)
```
Unused static tables (kept for reference): KEYS = A-plain, A-sky, A-skyveg, B-plain, B-sky, B-skyveg, B-veg, C-plain, C-veg;
CORPUS_MU = (0.458, 0.443, 0.431), CORPUS_SD = (0.283, 0.282, 0.300);
MU = [(.4703,.4359,.3982),(.3957,.4694,.5461),(.3556,.405,.391),(.3476,.3171,.2954),(.2839,.3552,.4373),(.269,.3321,.3284),(.272,.3054,.2361),(.2154,.1824,.1786),(.1679,.2356,.1661)];
SD = [(.26,.2525,.2576),(.2408,.2368,.2598),(.2473,.2506,.3217),(.2707,.2454,.2452),(.2319,.2327,.2759),(.2316,.2369,.2934),(.2236,.2226,.2118),(.2428,.1947,.1931),(.1882,.206,.18)].
GLES: the curves are 3x3 1-D tables (a 256x9 texture). The segmentation weights are per-pixel analytic, so this runs **realtime-ready in a shader**
(drop the error diffusion or replace it with blue-noise dither).

### 7.2 Lm9Net (lm9_net.onnx, **missing from the extract**)  [EXACT]
Same I/O names as m9_distill (`lowres`, `full`; it is probably a sibling distilled net). ORT 8 threads, CPU.
Rotate if portrait → fit ≤4096x3072 → if min side < 512: upscale with s = 512/min (ceil) → overlapping tiles: `TILE=512, STRIDE=448, OV=64`,
`offsets(dim) = if dim<=512 [0] else [0,448,896,…≤dim-512] + [dim-512 if not already last]`. Each tile: full = 512² /255, lowres = boxDown2 → 256².
Blend weights: `w1d[i]=1`; if dim>512: for i<64 { if off>0: w[i]=i/64f; if off+512<dim: w[448+i]=(63-i)/64f }; weight = wx*wy.
Accumulate out*w and w, then pixel = clamp(round(acc/wsum*255)). Scale back, rotate back, JPEG q95.

---------------------------------------------------------------------------------------------------------------

## 8. M3: MonoRows.convert (called via M3Mono.ensure)  [EXACT]
`M3Mono.Params`: W_R=0.54, W_G=0.40, W_B=0.06, CONTRAST_S=0.42, FADE_BLACK=10, FADE_WHITE=246, GRAIN_MAX=13, GRAIN_SIZE_DIV=1400, GRAY_MEAN_CHROMA=2.5.
`ensure(jpeg)`: decode (inSampleSize 1, retry 2/4 on OOM, sRGB) → `QualityPass.prepare` → `convert` → JPEG q100 (EXIF is **not** copied here).
```kotlin
fun convert(bmp): Boolean {
    // 1) skip if already mono: sample every step = max(1, W*H/8192) pixels; chroma = max(|r-g|,|g-b|,|r-b|); if mean <= 2.5 return false
    // 2) tone curve
    val curve = FloatArray(256) { i -> var t = clamp((i - 10f)/236f, 0f, 1f); t += 0.42f*(t*t*(3 - 2*t) - t); t*236f + 10f }
    // 3) pass 1: gray
    px: v = curve[clamp((0.54f*R + 0.40f*G + 0.06f*B).toInt(), 0, 255)].toInt();  R=G=B=v
    QualityPass.detailBeforeGrain(bmp)        // app-specific detail pass, UNCERTAIN, skip
    // 4) pass 2: grain
    val g = Grain(W, H)
    for (y) { g.row(y); for (x) { val v = px & 0xFF; val l = v/255f
        val amp = (clamp(4f*l*(1-l), 0f, 1f)*0.7f + 0.3f) * 13f
        val o = clamp((v + g.at(x)*amp).toInt(), 0, 255); px = gray(o) } }
    return true
}
class Grain(W: Int, H: Int) {           // smooth value-noise, cell = scale px
    var seed = 0x9E3779B9.toInt(); val scale = max(1f, W/1400f); val cols = (W/scale).toInt() + 2; val maxRow = (H/scale).toInt()
    var a = FloatArray(cols).also(::fill); var b = FloatArray(cols).also(::fill); var index = 0; var fraction = 0f
    fun fill(arr) { for (i in arr.indices) { seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                    arr[i] = (seed and 0xFFFF)/32768f - 1f } }
    fun row(y: Int) { val fy = y/scale; val t = min(maxRow, fy.toInt()); while (index < t) { val tmp=a; a=b; b=tmp; fill(b); index++ }; fraction = fy - t }
    fun at(x: Int): Float { val fx = x/scale; val i = min(cols-2, fx.toInt()); val f = fx - i
        val na = a[i] + (a[i+1]-a[i])*f; val nb = b[i] + (b[i+1]-b[i])*f; return na + (nb-na)*fraction }
}
```
`M3Mono.convert([I,w,h)` is an unused duplicate. It uses the same curve and grain amplitude but precomputes a (w/s+2)x(h/s+2) noise grid with the same xorshift,
then does bilinear lookup, with no chroma check and no detail pass.
GLES: the curve is a 1-D LUT, and the grain is a value-noise texture (cell = max(1, W/1400) px, amplitude 13·(0.3+0.7·4l(1−l)) in 8-bit units).

---------------------------------------------------------------------------------------------------------------

## 9. M13 "CCD": PortraitFilm (seplut_fivek.onnx, **missing from the extract**)  [EXACT]
Constants: D=17, INFER_EDGE=256, PORTRA_GAIN=(0.98,1.0,1.026) (**no effect**, cancelled by row normalisation), PORTRA_SAT=0.88,
PORTRA_WARM_MID=−0.016, PORTRA_GAMMA=0.05, PORTRA_COOL_SHADOW=0.015, COOL_SHADOW_G=0.35, COOL_SHADOW_B=1.1, SKIN_HUE_C=32°, SKIN_HUE_HALF=18°,
SKIN_SAT=0.62, SKIN_LUM_LIFT=0.05, SKIN_B_LIFT=0.012. ORT: 4 intra-op threads, CPU.
```kotlin
fun process(jpeg): ByteArray? {
    val bmp = prepare(decode(jpeg, ARGB_8888, mutable))
    val thumb = scale(bmp, 256, 256)                         // aspect NOT preserved, bilinear
    val img = planar [1,3,256,256] = c8/255f                 // input name "img"
    val res = session.run("img")
    val curves17: Array<FloatArray> = res[0] as [3][17]      // per-channel 1-D curve control points
    val lut3d: FloatArray = res[1] as [3*17*17*17]           // lut[c][b][g][r] = lut3d[c*4913 + b*289 + g*17 + r]
    val tab = Array(3){ c -> FloatArray(256){ i -> val x=i/255f*16; val k=clamp(x.toInt(),0,15); val f=clamp(x-k,0f,1f); curves17[c][k] + (curves17[c][k+1]-curves17[c][k])*f } }
    val M = 3x3: M[i][j] = 0.88*δij + 0.12*w[j], w=(0.299,0.587,0.114)   // = saturation 0.88
    val curveR = pwl(x=[0,.25,.5,.75,1], y=[0,.249,.5,.756,1]); curveG = pwl(.., y=[0,.25,.5,.752,1]); curveB = pwl(.., y=[0,.251,.501,.753,1])
        // pwl evaluated at i/255 into 256-entry tables (buildCurve: linear between control points)
    val gammaLut = FloatArray(513){ i -> Math.pow(i/256.0, 0.05000000074505806).toFloat() }
    for each pixel:
      // 1) per-channel 1-D curve then 3-D LUT (trilinear, 17³)
      val r1 = tab[0][R]; val g1 = tab[1][G]; val b1 = tab[2][B]
      val (rL,gL,bL) = trilinear(lut3d, r1*16, g1*16, b1*16)  // idx = clamp(int(x),0,15); frac = x-idx; upper = idx+1 only if frac>0
      // 2) saturation matrix
      var r = M0·(rL,gL,bL); var g = M1·(...); var b = M2·(...)
      val Y = 0.299f*r + 0.587f*g + 0.114f*b
      // 3) mid-tone tint (value -0.016: R down, B up)
      val t = 4f*Y*(1-Y)*(-0.016f); r = clamp01(r + t); b = clamp01(b - t)        // g NOT clamped here
      // 4) skin mask (HSV hue around 32°)
      val mx = max(r,g,b); val mn = min(r,g,b); val d = mx - mn; var skin = 0f
      if (d > 1e-5f) { var h = when { mx == r -> 60f*(g-b)/d + (if <0) 360 ; mx == g -> 60f*(b-r)/d + 120f; else -> 60f*(r-g)/d + 240f }
          var dh = abs(h - 32f); if (dh > 180f) dh = 360f - dh; val tt = 1f - dh/18f
          if (tt > 0f) skin = tt*tt*(3-2*tt) * clamp((d/max(mx,1e-6f) - 0.05f)/0.1f, 0f, 1f) * clamp((mx - 0.1f)/0.15f, 0f, 1f) }
      if (skin > 0f) { val k = 1f - 0.38f*skin; val Y2 = 0.299f*r + 0.587f*g + 0.114f*b
          r = clamp01(Y2+(r-Y2)*k); g = clamp01(Y2+(g-Y2)*k); b = clamp01(Y2+(b-Y2)*k)
          val lift = 1f + 0.05f*skin; r = clamp01(r*lift - 0.004f*skin); g = clamp01(g*lift); b = clamp01(b*lift + 0.012f*skin) }
      // 5) luminance-dependent gain (uses Y from step 2): g' = (2Y)^0.05
      val gi = clamp((Y*512f).toInt(), 0, 511); val gf = clamp(Y*512f - gi, 0f, 1f); val gm = lerp(gammaLut[gi], gammaLut[gi+1], gf)
      r = clamp01(r*gm); g = clamp01(g*gm); b = clamp01(b*gm)
      // 6) cool shadows (reduced on skin)
      val Y3 = 0.299f*r + 0.587f*g + 0.114f*b; val sh = (1-Y3)*(1-Y3)*(1f - 0.8f*skin)
      r = clamp01(r - 0.015f*sh); g = clamp01(g + 0.00525f*sh); b = clamp01(b + 0.0165f*sh)
      // 7) final per-channel curves
      R' = clamp((curveR[clamp((r*255f+0.5f).toInt(),0,255)]*255f + 0.5f).toInt(), 0, 255)   // same for G, B
    return copyExif(jpeg, encodeJpeg(bmp, 96))       // then LeicaEngine applies MadridShader(grain 0.18, vignette 1.0)
}
```
GLES: everything after inference is per-pixel. Run the SepLUT net once per scene (a 256x256 thumbnail), then render a 33³ LUT that bakes
steps 1–7 into one 3D texture. Exact apart from LUT resolution, since all steps are pure functions of the pixel colour.

---------------------------------------------------------------------------------------------------------------

## 10. M9+ boostSaturationContrast(jpeg, sat=1.25, contrast=1.1, warm=0.04)  [EXACT]
Android `ColorMatrix` on 0–255 values, applied via Paint colour filter, then JPEG q100 + EXIF:
```
cm = setSaturation(1.25)                    // lum weights 0.213, 0.715, 0.072:  row_i = [0.213*(1-s), 0.715*(1-s), 0.072*(1-s)] + s*e_i
if (warm != 0) cm = Scale(1+warm, 1, 1-warm, 1) ∘ cm        // R×1.04, B×0.96
cm = Contrast ∘ cm,  Contrast: c' = 1.1*c + (1-1.1)*128     // = 1.1c − 12.8
out = clamp(cm·[R,G,B,A,1])                                  // order: saturation → warm scale → contrast
```
This is a fixed 3x4 matrix, so it is realtime-trivial in a shader.

---------------------------------------------------------------------------------------------------------------

## 11. MadridShader.apply(bmp, grain g, vignette v)  [EXACT]
`applyToJpeg(jpeg, g)` = `apply(bmp, g, 1.0)` → JPEG q100 + EXIF. Callers: M10R g=0.3, M13 g=0.18, M4 g=0.22, LP9 (g=0, v=0.3).
```kotlin
init once: P_LUT[i] = (1.0 / Math.pow(100.0, 1.0 - i/255.0)).toFloat()            // = 100^(i/255 - 1), 0.01..1
           NOISE[(j shl 8) or i] = (Σ_{o in [0.07,0.11,0.13,0.17,0.19,0.23]} nrand(i/255f + o, j/255f + o)) / 6f     // i,j in 0..255
           nrand(x,y) = fract(sin(x*12.9898 + y*78.233) * 43758.5453)   (double math)
per pixel (x,y), r,g,b in 0..1:
    val dx = (x/W.toFloat() - 0.5f)*0.75f; val dy = y/H.toFloat() - 0.5f; val d = sqrt(dx*dx + dy*dy)
    val P = P_LUT[clamp255(0.2125f*r + 0.7154f*g + 0.0721f*b)]               // clamp255(v) = clamp((v*255+0.5).toInt(), 0, 255)
    val vig = 1f - (1f - smoothstep(1.4f, 0.2f, d)) * v                       // smoothstep(e0,e1,x): t=clamp((x-e0)/(e1-e0)); t²(3-2t)
    val m = P + (1 - P)*vig                                                    // vignette darkens only darker pixels (bright P≈1 unaffected)
    r*=m; g*=m; b*=m
    val amt = clamp((1f - (0.299f*r + 0.114f*g + 0.587f*b)) * g, 0f, 1f)      // NOTE swapped G/B weights (bug, reproduce)
    if (amt > 0) { val n = NOISE[((y and 255) shl 8) or (x and 255)] * 2f; r += (r*n - r)*amt; g += (g*n - g)*amt; b += (b*n - b)*amt }
    out = clamp255 each
```
(In the grain line, `g` on the right-hand side is the grain parameter, not the green channel.) GLES-ready: NOISE is a 256x256 R16F texture with GL_REPEAT.

---------------------------------------------------------------------------------------------------------------

## 12. M4 / LP9: AlexaLut (LUT files missing)  [EXACT]
LUT file = 65³ x 3 float32 LE, **planar** (R plane, then G at +274625, then B at +549250); index `b*4225 + g*65 + r`; file size must be 3,295,500 bytes.
`BINSIZE = 0.015626563f`; i = clamp(floor(c/BINSIZE), ≤63); f = clamp((c mod BINSIZE)/BINSIZE, 0, 1); trilinear; out = clamp((v*255+0.5).toInt()).
Hooks for LP9 (`P9Post`): `pre = Lm9Post.chromaDenoise + Lm9Post.skyChromaMidbandClean` (not decoded); `post`: if max(r,g,b) > 250, scale RGB by 250/max.
Output JPEG q95 + EXIF, then MadridShader.

---------------------------------------------------------------------------------------------------------------

## 13. ORT on Android: what the donor does, and what to do on the Xperia
* Donor: `OrtEnvironment.getEnvironment()`; `SessionOptions(); setIntraOpNumThreads(8)` (PortraitFilm: 4); `env.createSession(bytes, opts)`;
  input tensors from direct native-order `FloatBuffer`s; output read with `getFloatBuffer()`. **No NNAPI, XNNPACK or QNN EPs.**
* Xperia 1 IV (SD 8 Gen 1, 1x X2 + 3x A710 + 4x A510): use 4 intra-op threads pinned to big cores (8 threads usually hurts on big.LITTLE).
  Optional `addXnnpack(mapOf("intra_op_num_threads" to "4"))`. NNAPI needs static shapes, so fix m9_distill to 1216/608 (sec. 3.2).
  Check NNAPI output against CPU (fp16 relaxation may shift colours).
* Realtime preview: M9Distill via coefficient maps (sec. 3.3) + LUT0 3D texture + MadridShader/boost in one fragment shader.
  Lm9Stats, MonoRows, PortraitFilm-baked LUT and boost are all per-pixel shaders. Styletrans is offline only.

---------------------------------------------------------------------------------------------------------------

## 14. Numeric constants (decoded)
| Where | Constant | Value |
|---|---|---|
| LeicaEngine | M9P_BOOST_SATURATION / CONTRAST / WARM | 1.25 / 1.1 / 0.04 |
| LeicaEngine | MadridShader grain: M10R / M13 / M4 / LP9 | 0.3 (0x3e99999a) / 0.18 (0x3e3851ec) / 0.22 (0x3e6147ae) / 0.0 (+vignette 0.3) |
| M9Distill / M9OrtFallback defaults | a, b, c, bin | 0.6 (0x3f19999a), −0.95 (0xbf733333), 0.3, "leica_filter_param_m9_snapshot.bin" |
| M9+ | a, b, c | 0.6, −1.3 (0xbfa66666), 0.5 |
| M9Distill | KEEP, HALO, MIN_CANVAS, W4K, H4K, INV255 | 1024, 96, 256, 4096, 3072, 0.003921569 |
| Lm9Net | TILE, STRIDE, OV, LOW, INV255 | 512, 448, 64, 256, 1/255 |
| M9OrtFallback | NW, NH, PAD, DETAIL_SIGMA(unused), blur sigma used, scale | 1024, 768, 48, 2.0, 1.0, 127.5 (0x42ff0000), offset 128 |
| Lm9Stats | luma | 0.2126, 0.7152, 0.0722 (double and float) |
| Lm9Stats | probe width; sky/veg/plain thresholds | 320; b>g+10 && b>r+20 && Y>0.35; g>r+8 && g>b+8; frac 0.12; meanY 0.5 / 0.3 |
| Lm9Stats | soft masks | 0.024/0.078, 0.016/0.063, 0.02/0.06, (Y−0.45)/0.15, (Y−0.30)/0.15, (Y−0.80)/0.15 |
| Lm9Stats | per-class M, K | see 7.1 |
| MonoRows/M3Mono | weights, S, fade, grain | 0.54/0.40/0.06, 0.42, 10..246 (236), 13·(0.3+0.7·4l(1−l)), cell W/1400, seed 0x9E3779B9, chroma gate 2.5 |
| PortraitFilm | see sec. 9 | 0.88, −0.016, 0.05, 0.015, 0.35→0.00525, 1.1→0.0165, 32°, 18°, 0.62→0.38, 0.05, 0.012, 0.004, 0.8, 1e-5, 1e-6, 0.05/0.1, 0.1/0.15 |
| MadridShader | vignette | aspect 0.75 on x; smoothstep(1.4, 0.2, d); P_LUT 100^(i/255−1); lum 0.2125/0.7154/0.0721 |
| MadridShader | grain | luma(bug) 0.299/0.114/0.587; noise ×2; offsets 0.07,0.11,0.13,0.17,0.19,0.23; 12.9898, 78.233, 43758.5453 |
| AlexaLut | DIM, BINSIZE, SHIFT | 65, 0.015626563, 274625 |
| Native filter call | lux, cct, zoom, seed | 200, 5500, 1.0, uptimeMillis % 60000 (0xea60) |
| styletrans_params.json | thresholds | lux_threshold_daytime 260, cct_threshold 4692 |
| boost | ColorMatrix sat lum | 0.213, 0.715, 0.072 (Android), contrast pivot 128 |

## 15. UNCERTAIN items (summary)
1. The native `applyWarmOriginalFilter` / `MadridLegendary.filter`: LUT interpolation (trilinear assumed), how the 8-float shading record is used,
   and the exact maths of (denoise 0.6, density EV −0.95/−1.3, neutral warmth 0.3/0.5). Tune by eye. A tetrahedral LUT is an option.
2. The meaning and polarity of the styletrans `mask` (probably the human-segmentation probability). The donor passes zeros.
3. `DenoiseFlow.prepare` / `QualityPass.detailBeforeGrain` (app-wide NR/detail hooks) are not reproduced.
4. Missing assets: lm9_net.onnx, seplut_fivek.onnx, lut_alexa.bin, lut_p9vibrant.bin (not in the extracted APK; look in other split APKs or the full asset pack).
5. Android timings are estimates scaled from M1.

## 16. Artefacts produced
* `scratchpad/work/d120/m9distill_intro0.png`: M9Distill exact-tiling output for intro-0.jpg (before the warm LUT)
* `scratchpad/work/d120/m9ort_intro0.png`: M9OrtFallback reproduction (before the warm LUT)
* `scratchpad/work/d120/styletrans_zero_mask_1024x768.png`: raw styletrans tile output
* `scratchpad/work/d120/m9_distill_coef.onnx`: m9_distill with extra outputs for shader-side coefficient maps
* `scratchpad/work/d120/lut33_fit.npy`: image-fitted 33³ LUT (for comparison only)
* Scripts: `scratchpad/scripts/{m9distill_test,m9coef_test,m9ort_test,seam_test,compare_chains,parse_bin,arrdata,inspect}.py`
