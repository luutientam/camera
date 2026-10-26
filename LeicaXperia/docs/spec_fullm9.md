# Full M9 pipeline — implementation spec (port target: Xperia 1 IV, Camera2 + ORT Android + GLES)

Source: `com.codex.fullm9.*` in "Leica Port 8.9.apk" (classes17.dex; `PackedWeights` in classes21.dex), native
`lib/arm64-v8a/libfullm9.so` (not stripped; JNI `Java_com_codex_fullm9_NativeM9_*`), assets `assets/fullm9/*`.
Every claim below comes from the dexdump or the aarch64 disassembly unless it is marked **UNCERTAIN**.
Numbers are decimal.

Working files (all under the scratchpad
`/private/tmp/claude-501/-Users-lmao1-Desktop-camera/68d51ba1-2b98-422d-af9f-21e15ef9b900/scratchpad/`):

| What | Path |
|---|---|
| Reconstructed models (SHA-256 verified) | `models/low.onnx`, `models/high.onnx`, `models/colorfix.onnx` |
| `.m9w` decoder (Python) | `scripts2/m9w_decode.py` |
| Model check (onnx.checker + ORT) | `scripts2/verify_models.py` |
| Numpy reference of the native Frame pipeline | `scripts2/m9_frame_proto.py`; sample output `scripts2/proto_cmp.jpg` |
| LUT-bin parser / LUT diff / AE / ipe helpers | `scripts2/leica_bin.py`, `lutdiff.py`, `ae_used.py`, `ipe_sum.py`, `jsonsum.py`, `lutcheck.py` |
| Native disassembly listings | `native/*.s` (`restoreTexture.s`, `restoreDetail.s`, …) |

---

## 0. TL;DR data flow (JPEG/bitmap capture path, which is the only path that matters on the Xperia)

```
camera JPEG (sRGB 8-bit)                                   Settings{lux index, cct, zoom}
   │ FullM9Input.decode (ARGB_8888, sRGB, EXIF-rotated, gain map dropped)
   ▼
DenoiseFlow.prepare  (input NR: libleicadenoise.so leica_filter + chroma NR; optional for port)
   │ if h>w rotate +90°;  scale s=min(4096/w, 3072/h, 1)  (filtered, never upscale)
   ▼
M9Graph.process  (= native m9::Frame + 2 ONNX models, ORT CPU FP32)
   │  canvas 4096x3072 (mirror-padded) → 4x4 box → 1024x768 → reflect-pad 48 → [1,4,864,1120]
   │  style model: "low" if lux>260 && cct<4692 else "high"  → [1,3,864,1120] → crop → 1024x768 u8
   │  per visible 512x512 tile (≤48): [1,6,544,544] = [nearest-4x style RGB, original RGB] → colorfix
   │  → [1,3,544,544] → crop 16 → stitch → crop to w×h  (the colorfix output IS the final pixel)
   ▼ scale back to pre-scale size; rotate −90° if rotated
LeicaParams("common", lux, cct) 17³ LUT (bilinear-blended between bins) + shading(zoom,lux,cct) float[8]
   │ NativeM9.applyFilter: trilinear 3D LUT + radial/luma-sigmoid "shading" (vignette)
   ▼
DenoiseFlow.finish (no-op for style 8) → NativeM9.restoreTexture(orig, out) (edge-aware detail re-injection)
   ▼ (QualityPass.fullDetail only if restoreTexture failed)
watermark (host; skip) → FullM9Hdr.finish: generate an Ultra HDR gain map from the final SDR (API 34+)
   ▼
draw into a DISPLAY_P3 ARGB_8888 bitmap (Skia sRGB→P3) → copy gain map → JPEG q=100 → copy EXIF, Orientation=1
```

---

## 1. `.m9w` packed-model format and decoder (`com.codex.fullm9.PackedWeights.model`)

`Assets.model(ctx, name)` delegates to `PackedWeights.model(ctx, name)`. The name is one of `low`, `high` or
`colorfix`; anything else throws "Unknown M9 model". The decoder writes `noBackupFilesDir/fullm9-p1/<name>.onnx`.
If that file already exists with the expected length and SHA-256, it is reused.

### 1.1 Layout (all integers big-endian, `DataInputStream`)

```
off  size  field
0    8     MAGIC = "M9W1801\0"  (4D 39 57 31 38 30 31 00)       else IOException("Packed model header")
8    4     u32 outSize — must equal the hard-coded size          else "Packed model header"
12   4     i32 nSections, 1..2048                               else "Packed model sections"
16   32    SHA-256 of the decoded ONNX; hex must equal ManifestHashes.hash(name)   else "Packed model identity"
48   …     nSections × section
section:
  u8 type
  type 0 (literal):   i32 n (0 ≤ n ≤ outSize − written)  then n raw bytes copied     else "Packed literal bounds"
  type 1 (int8 rows): i32 rows (1..4096), i32 cols (1..1048576), rows*cols*4 ≤ outSize − written  else "Packed weight bounds"
                      rows × f64 BE scale[r] (must be finite)   else "Packed scale"
                      rows*cols × int8 q   (row-major)
                      output: for r, c: float32 LE  =  (float)( (double)q[r][c] * scale[r] )
  other type → "Packed section type"
After all sections: written == outSize AND the stream is at EOF   else "Packed model length"
SHA-256(output) == expected                                      else "Model digest mismatch: <name>"
```

The quantized sections are the raw bytes of the ONNX initializer `raw_data`, and the literal sections are the
protobuf around them. The original models used int8-representable weights (QNN/SNPE export), so decoding is
**lossless**: the SHA-256 matches.

| model | outSize (code constant) | sections | quant sections / values | SHA-256 |
|---|---|---|---|---|
| low | 15,709,687 | 975 | 487 / 3,818,496 | 89f7e4e515467a6f47c9d74538a08d25ed7e148474fca2e3713db5e9259cf406 |
| high | 15,636,032 | 969 | 484 / 3,800,064 | 61eedf9d72141c0f5262a2b2d7791ea27d36b77cfb63a8312b007e447b14da73 |
| colorfix | **31,071,922** (0x01DA1EB2) | 51 | 25 / 7,759,384 | 381e0c48c241b3a1eaf09843ab9cb43610a96eae7708c0316b17a8661620b74f |

`conversion-validation.json` lists colorfix as 31,091,662 bytes. That is stale: the code constant and the
decoded file are 31,071,922 bytes.

### 1.2 Kotlin decoder (drop-in; you can also simply ship the decoded .onnx files)

```kotlin
fun decodeM9w(input: InputStream, expectedSize: Int, expectedSha: String): ByteArray {
    val d = DataInputStream(BufferedInputStream(input, 65536))
    val magic = ByteArray(8).also(d::readFully)
    require(magic.contentEquals("M9W1801\u0000".toByteArray(Charsets.ISO_8859_1))) { "Packed model header" }
    require(d.readInt() == expectedSize) { "Packed model header" }
    val n = d.readInt(); require(n in 1..2048) { "Packed model sections" }
    val id = ByteArray(32).also(d::readFully); require(id.toHex() == expectedSha) { "Packed model identity" }
    val out = ByteArrayOutputStream(expectedSize); var written = 0L
    repeat(n) {
        when (d.readUnsignedByte()) {
            0 -> { val len = d.readInt(); require(len >= 0 && len <= expectedSize - written)
                   val b = ByteArray(len); d.readFully(b); out.write(b); written += len }
            1 -> { val rows = d.readInt(); val cols = d.readInt()
                   val bytes = rows.toLong() * cols * 4
                   require(rows in 1..4096 && cols in 1..1_048_576 && bytes <= expectedSize - written)
                   val scale = DoubleArray(rows) { d.readDouble().also { require(it.isFinite()) } }
                   val q = ByteArray(cols); val bb = ByteBuffer.allocate(cols * 4).order(ByteOrder.LITTLE_ENDIAN)
                   for (r in 0 until rows) { d.readFully(q); bb.clear()
                       for (c in 0 until cols) bb.putFloat((q[c].toDouble() * scale[r]).toFloat())
                       out.write(bb.array(), 0, cols * 4) }
                   written += bytes }
            else -> error("Packed section type")
        }
    }
    require(written == expectedSize.toLong() && d.read() == -1) { "Packed model length" }
    return out.toByteArray().also { require(sha256Hex(it) == expectedSha) }
}
```

The Python equivalent is `scripts2/m9w_decode.py`; it ran and its SHA-256 check passed for all three models.

### 1.3 Reconstructed models (checked with `onnx.checker.check_model(full_check=True)` and run in ORT 1.x CPU)

All three: ir_version 8, opset 17, producer "pytorch 2.8.0". Each has one input named `input` and one output
named `output`, both float32 NCHW. The ORT symbolic dims are named `height` and `width`.

| model | input | output | params | ops | ORT CPU time (Mac, 1 run) |
|---|---|---|---|---|---|
| low.onnx | [1,4,864,1120] | [1,3,864,1120] | 3,838,016 | 487 Conv, 487 Clip, 185 Concat, 124 Slice, 2 Resize(nearest ×2), AveragePool, Pad, … (2,806 nodes) | ≈2.6 s |
| high.onnx | [1,4,864,1120] | [1,3,864,1120] | 3,819,584 | same topology (484 Conv) | ≈2.3 s |
| colorfix.onnx | [1,6,544,544] | [1,3,544,544] | 7,764,275 | U-Net: Conv/Clip, 4× MaxPool 2×2, 4× DepthToSpace(2, DCR), Concat skips | ≈0.55 s/tile |

- **Style net internals.** Input channels 0–2 are space-to-depth'd by 4, giving 48 channels. Channel 3 goes
  through AveragePool 4×4, adding 1 channel. The 49 channels are zero-padded to 64 before a dense
  RDB-style trunk. The net then upsamples with 2× nearest-neighbour twice (each followed by ×0.99814 Mul). The
  final Clip is to [−1.003921628, 1.003890991], and the first 3 channels are sliced out.
- **Clip ranges.** Every Conv is followed by a Clip with per-layer ranges. These are the baked activation ranges
  of the original SNPE/QNN 8-bit model (`styletrans_params.json`: offset −128, delta 0.007843 = 2/255).
- **Precision.** FP32 is reference-exact. FP16 or XNNPACK is acceptable, because the app itself accepts a
  backend only if its output matches to `|Δ| ≤ 1e-4` (§9).

**Value domain** (both models): `x = (u8 − 128) / 127.5`, i.e. sRGB-encoded 8-bit values mapped to
[−1.00392, 1.0]. Outputs are mapped back with `u8 = clamp(rint(y·127.5 + 128), 0, 255)`, using round half to
even.

---

## 2. Capture pipeline — `FullM9Engine.render(ctx, bytes, hostCaptureObj, int[]) : ByteArray` (exact order)

```kotlin
fun render(ctx: Context, input: ByteArray, obj: Any?): ByteArray = synchronized(LOCK) {
    FullM9Hdr.clear(); OutputQuality.begin(intArrayOf(8, 0)); DenoiseFlow.begin(intArrayOf(8, 0))   // style 8 = "M9"
    val isTiff = input.size > 4 && ((input[0]=='I' && input[1]=='I') || (input[0]=='M' && input[1]=='M'))
    lateinit var src: Bitmap; lateinit var exifSrc: ByteArray; lateinit var s: Settings; var rawGain: Bitmap? = null
    if (isTiff) {                                     // ---- DNG path (see §6) ----
        val m = DngMeta(input)
        s = Settings(lux = round(m.lux), cct = round(m.cct), zoom = m.zoom.toFloat(), provenance = "linear DNG ${m.camera}")
        val h = NativeM9.rawOpen(input, m.wb); val wh = NativeM9.rawSize(h); val stats = NativeM9.rawStats(h)
        val ae = AE.meter(ctx, stats, s.lux.toDouble(), m.camera)
        val t = Tuning(ctx)
        val cfgA = t.config(m.camera, m.cameraToSrgb, s.lux.toDouble(), s.cct.toDouble(), ae, false)
        val cfgB = t.config(m.camera, m.cameraToSrgb, s.lux.toDouble(), s.cct.toDouble(), ae, true)
        src = Bitmap.createBitmap(wh[0], wh[1], ARGB_8888)
        rawGain = Bitmap.createBitmap((((wh[0]+1)/2)+1) and -2, (((wh[1]+1)/2)+1) and -2, ARGB_8888)
        NativeM9.rawRender(h, cfgA, cfgB, src, rawGain); NativeM9.rawClose(h)
        exifSrc = rawExif(ctx, input, wh[0], wh[1])
    } else {                                          // ---- JPEG / HEIC / PNG path ----
        val o = FullM9Input.orientation(input); src = FullM9Input.decode(input, o)
        val isJpeg = input.size > 1 && input[0]==0xFF.toByte() && input[1]==0xD8.toByte()
        exifSrc = if (isJpeg && (o == 0 || o == 1)) input else rawExif(ctx, input, src.width, src.height)
        s = settings(exifSrc, obj)                                                  // §3
    }
    val dn = DenoiseFlow.prepare(src)                                               // stage "输入降噪" (input NR)
    val rotated = dn.height > dn.width
    var work = if (rotated) rotate(dn, 90f) else dn                                 // Matrix.setRotate(90): clockwise
    val W = work.width; val H = work.height
    val sc = min(1f, min(4096f / W, 3072f / H))
    if (sc < 1f) work = Bitmap.createScaledBitmap(work, max(1, (W*sc).toInt()), max(1, (H*sc).toInt()), true)
    // stage "M9影调与色彩恢复" (M9 tone & colour recovery)
    var out = M9Graph.process(ctx, work, s.lux, s.cct, s.zoom)                      // §4 (CloudGraph.hook → null locally)
    if (sc < 1f) out = Bitmap.createScaledBitmap(out, W, H, true)                   // bilinear back to the pre-scale size
    if (rotated) out = rotate(out, -90f)
    // stage "影调与输出降噪" (tone & output NR)
    val lp = LeicaParams(ctx)                                                       // snapshot bin (§5)
    NativeM9.applyFilter(out, lp.lut("common", s.lux, s.cct), lp.shading(s.zoom, s.lux, s.cct))   // §5.4
    out = DenoiseFlow.finish(out)                                                   // QualityPass.finish: no-op for style 8
    // stage "精细纹理恢复" (fine texture recovery)
    try { NativeM9.restoreTexture(dn, out); QualityPass.nativeDetailDone(dn, out) }  // §4.6; errors only logged
    catch (e: Throwable) { log("Texture recovery …") }
    out = QualityPass.fullDetail(out)                       // fallback sharpen ONLY if restoreTexture did not complete
    CloudGraph.palette(out)                                 // no-op locally
    if (rawGain != null && SDK >= 34) FullM9Hdr.attachRaw(out, rawGain, boost(rawGain)) else FullM9Hdr.transfer(null, out, input, false)
    // stage "水印" (watermark): host LeicaMark/OriginalWatermark (skip in port; must return a Bitmap)
    val wm = watermark(out)
    FullM9Hdr.finish(out, wm, 8)                            // §7: build the gain map on the final canvas
    // stage "最终HDR与JPEG编码" (final HDR & JPEG encode)
    val p3 = p3(wm)                                         // createBitmap(w,h,ARGB_8888,false,DISPLAY_P3); Canvas.drawBitmap(Paint(3))
    if (SDK >= 34 && wm.hasGainmap()) p3.gainmap = wm.gainmap
    val jpg = ByteArrayOutputStream().also { require(p3.compress(JPEG, 100, it)) { "Final JPEG encoding failed" } }.toByteArray()
    val result = metadata(exifSrc, jpg, p3.width, p3.height)   // JpegMetadata.copyExif(src,out,true,w,h,true) then ExifOrientation.upright
    FullM9Hdr.verify(ctx, result, 8)                        // diagnostics only
    // log: "完美移植m9 size=WxH model=<low|high> <timing> lux= cct= triggers=<provenance> seconds=…"
    return result
}
```

Notes for the port:

- **Colour space.** Everything is processed as **8-bit sRGB-encoded RGBA**. `FullM9Input.decode` forces
  `inPreferredColorSpace = SRGB`. There is no linearisation anywhere on the JPEG path. Display P3 appears only
  at the end, as a container: Skia converts sRGB to P3 so the appearance is unchanged, and `Bitmap.compress`
  embeds the P3 ICC. The asset `display_p3.icc` (600 bytes) is **not referenced by code**. If you encode with
  libjpeg-turbo, either embed it as APP2 after converting the pixels sRGB→P3, or just emit sRGB.
- **Orientation.** The image is decoded upright (EXIF matrix applied) and processed landscape (portrait is
  rotated +90° then −90°). The output EXIF Orientation is forced to 1.
- **Source gain map.** Any source Ultra HDR gain map is discarded and a new one is generated (§7).
- **Rotation sign.** In `rotate()`, `Matrix.setRotate(+90)` is clockwise on screen (y-down). The direction
  does not affect the result because the image is rotated back.
- **Output JPEG.** Quality 100. The framework default chroma subsampling is 4:2:0 (**UNCERTAIN**; not set
  explicitly).

---

## 3. Scene metadata: lux index, CCT, zoom (`FullM9Engine.settings` + `$Settings`)

`Settings` defaults: `lux = 300`, `cct = 5000`, `zoom = 1.0f`, `provenance = "reference"`. The "lux" here is
Qualcomm's **lux index**: higher means darker, ~0 is bright sun, ~500+ is dark.

```kotlin
fun settings(jpegExif: ByteArray, obj: Any?): Settings {
    if (obj is CaptureSnapshot) return obj.settings()             // frozen earlier with this same function
    val s = Settings()
    try {
        val ex = ExifInterface(jpegExif.inputStream())
        val t = ex.getAttributeDouble("ExposureTime", 0.0); val N = ex.getAttributeDouble("FNumber", 0.0)
        val iso = ex.getAttributeDouble("PhotographicSensitivity", ex.getAttributeDouble("ISOSpeedRatings", 0.0))
        if (t > 0 && N > 0 && iso > 0) {
            val ev100 = ln(N*N / t)/ln(2.0) - ln(iso/100.0)/ln(2.0)
            s.lux = max(0.0, min(999.0, 474.9 - 23.45*ev100)).toInt()      // truncation
            s.provenance = "EXIF EV estimate"
        }
        val f35 = ex.getAttributeDouble("FocalLengthIn35mmFilm", 0.0)
        if (f35 >= 50.0) s.zoom = max(3.2, min(4.3, f35/23.0)).toFloat()
    } catch (_: Exception) {}
    val cr = captureResult(obj) as? CaptureResult ?: return s     // host's CaptureResult (reflection)
    for (key in cr.keys) {
        val name = key.name.lowercase(Locale.US)
        val v: Float = when (val o = cr.get(key)) { is Number -> o.toFloat(); is FloatArray -> o.firstOrNull() ?: Float.NaN; else -> Float.NaN }
        if (!v.isFinite()) continue
        if ((name.contains("luxindex") || name.contains("lux_index")) && v >= 0f && v <= 1000f) { s.lux = v.roundToInt(); s.provenance = "capture lux" }
        if ((name.endsWith("cct") || name.contains("colortemperature") || name.contains("color_temperature")) && v >= 1500f && v <= 20000f) {
            s.cct = v.roundToInt(); s.provenance += " capture CCT" }
        if (name == "android.control.zoomratio" && v > 0f) s.zoom = v
    }
    return s
}
```

- **Lux index check.** The constants fit Qualcomm AEC: EV100 ≈ 10 (bright overcast) gives lux ≈ 240, and
  EV100 ≈ 5 (indoor) gives lux ≈ 357.
- **Xiaomi vendor tags.** On Xiaomi these are, for example, `xiaomi.ai.asd.luxIndex` /
  `com.xiaomi.camera.awb.cct` style keys. They match the substring rules above.
- **Sony.** No matching keys exist, so the EXIF estimate is used for lux and CCT stays at **5000** unless you
  compute it.

### 3.1 Recommended Camera2 fallbacks for the port

These are the formulas the app uses, mapped onto standard keys:

```kotlin
// lux index (identical formula; use the CaptureResult instead of EXIF)
val t = result[SENSOR_EXPOSURE_TIME]!! / 1e9; val N = result[LENS_APERTURE] ?: chars[LENS_INFO_AVAILABLE_APERTURES]!![0]
val iso = result[SENSOR_SENSITIVITY]!! * ((result[CONTROL_POST_RAW_SENSITIVITY_BOOST] ?: 100) / 100.0)   // boost: UNCERTAIN
val luxIndex = (474.9 - 23.45*(log2(N*N/t) - log2(iso/100.0))).coerceIn(0.0, 999.0).toInt()

// CCT (same method as DngMeta, §6.4): McCamy on XYZ of the as-shot neutral
//   cm1/cm2 = SENSOR_COLOR_TRANSFORM1/2 (XYZ→camera), illuminants = SENSOR_REFERENCE_ILLUMINANT1/2 (EXIF codes)
//   neutral = SENSOR_NEUTRAL_COLOR_POINT (RAW-capable devices; the Xperia 1 IV exposes it)
//          ?: from COLOR_CORRECTION_GAINS g: [ (gGe+gGo)/2/gR, 1, (gGe+gGo)/2/gB ]        (UNCERTAIN equivalence)
//   iterate: CM = blend(cm1,cm2,cct) ; XYZ = CM⁻¹·neutral ; x,y ; n=(x−0.332)/(0.1858−y);
//            cct = clamp(449n³+3525n²+6823.3n+5520.33, 1500, 20000)  until |Δ|<1 (≤30 iters, start 5000)
// zoom: result[CONTROL_ZOOM_RATIO] (API 30+), else 1.0; tele (85 mm-eq lens) → clamp(f35/23, 3.2, 4.3)
```

Model choice: `model = if (lux > 260 && cct < 4692) "low" else "high"`. The thresholds come from
`styletrans_params.json` "Threshold": `lux_threshold_daytime` 260 and `cct_threshold` 4692. They are
hard-coded; the JSON is never read. "low" therefore means a dim **and** warm scene.

---

## 4. Native pipeline (libfullm9.so) — style/colorfix tiling (`m9::Frame`), filter, texture

### 4.0 Conventions

- **Bitmaps** must be `RGBA_8888`, otherwise IOException. Memory order is R,G,B,A. Native code reads R,G,B and
  always writes A = 255.
- **`n8(u) = (u − 128) / 127.5`.**
- **`q8(v) = clamp(rint(v·127.5 + 128), 0, 255)`**, using `frinti` (round half to even). NaN or Inf throws
  "Non-finite image sample".
- **`PAD = n8(0) = −1.003921627998352`.**

### 4.1 Frame object

| Field | Value |
|---|---|
| `w`, `h` | Bitmap size; requires 1 ≤ w ≤ 4096 and 1 ≤ h ≤ 3072, else "M9 frame dimensions" |
| `offX` | `(4096 − w) / 2` (int division) |
| `offY` | `(3072 − h) / 2` (int division) |
| `canvas` | u8 RGB 4096×3072×3 |
| `small` | u8 RGB 1024×768×3 |
| `style` | u8 RGB 1024×768×3 |
| `outCanvas` | u8 RGB 4096×3072×3 |

### 4.2 frameCreate + prepare

```kotlin
fun refl101(i0: Int, n: Int): Int { if (n < 2) return 0; var i = i0
    while (i !in 0 until n) i = (if (i < 0) 0 else 2*n - 2) - i; return i }      // mirror, edge NOT repeated
for (Y in 0 until 3072) for (X in 0 until 4096) for (c in 0..2)
    canvas[Y][X][c] = bmp[refl101(Y - offY, h)][refl101(X - offX, w)].rgb[c]
for (y in 0 until 768) for (x in 0 until 1024) for (c in 0..2)                    // exact 4x4 box average
    small[y][x][c] = clamp(rint(Σ_{dy,dx∈0..3} canvas[4y+dy][4x+dx][c] / 16f), 0, 255)
```

### 4.3 Style model I/O

```kotlin
fun reflHalf(i0: Int, n: Int): Int { var i = i0
    while (i !in 0 until n) i = (if (i < 0) -1 else 2*n - 1) - i; return i }      // mirror, edge repeated
// input tensor [1,4,864,1120] (H=864, W=1120), plane = 967_680 floats
for (y in 0 until 864) for (x in 0 until 1120) {
    val sy = reflHalf(y - 48, 768); val sx = reflHalf(x - 48, 1024)
    for (c in 0..2) inp[c*967680 + y*1120 + x] = n8(small[sy][sx][c])               // R,G,B
    inp[3*967680 + y*1120 + x] = PAD                                                  // channel 3: constant −1.0039216
}
run(model, inp)                                                                       // "low" | "high"
// styleOutput: crop the 48-px border, quantize to 8 bits
for (y in 0 until 768) for (x in 0 until 1024) for (c in 0..2)
    style[y][x][c] = q8(out[c*967680 + (y + 48)*1120 + (x + 48)])
```

Channel 3 is an all-"zero" (u8 = 0) mask plane in the original SNPE model. It must be −1.0039216, not 0.0.

### 4.4 Colorfix tiles

```kotlin
// visible tiles (Java M9Graph.visibleTiles): row-major over the 8x6 grid of 512 px intersecting the image
val rows = offY/512 .. (offY + h - 1)/512 ; val cols = offX/512 .. (offX + w - 1)/512 ; id = row*8 + col   // ≤ 48
// tileInput(id): [1,6,544,544], plane = 295_936
val y0 = row*512 - 16; val x0 = col*512 - 16
for (ty in 0 until 544) for (tx in 0 until 544) {
    val Y = y0 + ty; val X = x0 + tx; val p = ty*544 + tx
    if (Y !in 0 until 3072 || X !in 0 until 4096) { for (c in 0..5) t[c*295936 + p] = PAD; continue }
    for (c in 0..2) {
        t[c*295936 + p]       = n8(style[Y/4][X/4][c])      // ch 0..2 = style result, NEAREST 4x upsample
        t[(c+3)*295936 + p]   = n8(canvas[Y][X][c])         // ch 3..5 = original full-res RGB
    }
}
run(colorfix, t)
// tileOutput(id): hard crop of the centre 512x512, no feathering
for (ty in 0 until 512) for (tx in 0 until 512) for (c in 0..2)
    outCanvas[row*512 + ty][col*512 + tx][c] = q8(o[c*295936 + (ty + 16)*544 + (tx + 16)])
// frameRecover(dst w×h): dst[y][x] = RGBA(outCanvas[y + offY][x + offX], 255)
```

**How the low-res style reaches full resolution.** Colorfix is a learned guided upsampler. It takes the
4× nearest-upsampled 8-bit style image together with the full-res original and outputs the final full-res RGB
directly. There is **no** gain map, LUT fit or residual. The 8-bit round trips (q8) between stages are part of
the reference behaviour.

`frameFinish(handle, bmp, lut, p[8])` is exported but unused. It equals frameRecover followed by leicaPixel.

### 4.5 Cost

- One style run plus N colorfix tiles. A 4032×3024 Xperia frame gives offX = 32 and offY = 24, so all 48 tiles.
- On a Mac CPU: style ≈2.3–2.6 s, colorfix ≈0.55 s/tile, i.e. roughly 30 s per photo.
- ORT on the SD 8 Gen 1 is expected to be slower without NNAPI/QNN. Try `addXnnpack` or NNAPI FP16 and accept
  them only if `|Δ| ≤ 1e-4` (§9). Alternatively, run a GLES-free preview and process in the background, as the
  app does (PhotoProcessingService).

### 4.6 restoreTexture(orig, proc) — modifies `proc` in place

Requires equal sizes and w, h ≥ 5. Luma `L = 0.2126R + 0.7152G + 0.0722B` (0..255 floats).

```kotlin
// noise estimate from orig
val step = max(1, sqrt(w*h/16000.0).toInt())
for (y in 1 until h-1 step step) for (x in 1 until w-1 step step) {
    var hs = (x.toUInt()*0x85EBCA77u + y.toUInt()*0x9E3779B1u); hs = hs xor (hs shr 13); hs *= 0xC2B2AE3Du
    val px = min(x + (hs % step.toUInt()).toInt(), w-2); val py = min(y + ((hs shr 16) % step.toUInt()).toInt(), h-2)
    samples += abs(4*L(px,py) - L(px-1,py) - L(px+1,py) - L(px,py-1) - L(px,py+1))
}
val sigma = nth_element(samples, samples.size/5) / 1.133f        // 20th percentile
val thr = 0.8f*sigma + 0.75f;  val reg = 36f*sigma*sigma
// per pixel; neighbours clamped; LO = orig luma, LP = ORIGINAL (unmodified) proc luma
val g3 = [[1,2,1],[2,4,2],[1,2,1]]/16
for each (x,y):
    val bO = g3⊛LO; val oc = LO(x,y); if (abs(oc - bO) <= thr) continue
    val bP = g3⊛LP; val pc = LP(x,y)
    val k  = ((bP*(255-bP)) / max(bO*(255-bO), 64f)).coerceIn(0.5f, 1.5f)
    val dO = k*(oc - bO); val dP = pc - bP
    if (!(abs(dO) > abs(dP) + 0.5f) || dO*dP < -0.25f) continue
    var Sxx=0f; var Syy=0f; var Sxy=0f
    for (r in -1..1) for (c in -1..1) { val gx = LO(x+c+1,y+r) - LO(x+c-1,y+r); val gy = LO(x+c,y+r+1) - LO(x+c,y+r-1)
        Sxx += gx*gx; Syy += gy*gy; Sxy += gx*gy }
    val coh = sqrt((Sxx-Syy)^2 + 4*Sxy^2) / (Sxx + Syy + reg + 1)
    var t = ((coh - 0.35f)/0.4f).coerceIn(0f,1f); t = t*t*(3-2*t)
    val mid = (min(bP, 255-bP)/16f).coerceIn(0f,1f)
    var add = mid * (dO - dP).coerceIn(-12f,12f) * t
    add = add.coerceIn(-min(R,G,B).toFloat(), (255 - max(R,G,B)).toFloat())   // hue-preserving, no clipping
    proc[x,y] = (rint(R+add), rint(G+add), rint(B+add), 255)
```

On failure (OOM or other) the app logs and keeps the output. Then `QualityPass.fullDetail` applies its fallback
sharpening (`SpatialPass.detail`, constants 0.7 / 0.8 / 0.55).

### 4.7 Other natives

- **`hdrSample(bmp, ow, oh)`**: area-weighted box downsample of Rec.709 luma (double precision). Requires
  1..384 and ow ≤ w, oh ≤ h. Returns grey ARGB ints.
- **`restoreDetail(guideFull, guideLow, outFull)`**: legacy pipeline only, not used by Full M9.
  - Noise: Laplacian median / 3.016, then `σ = max(2.5·noise, 2)`.
  - Detail: 3×3 joint-bilateral full-res luma (range weight `1/(1+(Δ/σ)⁴)`) minus bilinear low-res luma,
    clamped to ±24.
  - The detail is multiplied by the midtone mask `min(Y, 255−Y)/24` and added to R, G and B.
- **`outputDenoise`**: not called.

---

## 5. Leica LUT bank + shading (`LeicaParams`, `leica_filter_param_m9_*.bin`, `applyFilter`)

### 5.1 File format (u16 little-endian unless noted; 1,686,102 bytes each)

```
0x00 u16 nScenes = 6
0x02 u16 sceneBlockSize[6] = 216 each
0x0E u16 metaOff = 0x400 (1024)
0x10 u16 lutOff  = 0x1000 (4096)
0x12 u16 grid    = 17           (must be 17, else IOException "M9 LUT size")
0x14 u16 nZoom   = 5
0x16 u16 zoomBlockSize[5] = 102 each           // header ends 0x20
0x20..0x3FF text padding, not read: "aiscene-lux-cct", "uint16_t", "0-1023", "202601082045"
scene block @ metaOff + Σprev:  char name[64] (NUL-terminated); tree
zoom  block @ 0x910 + 102*j:    f32 zoom (0.6, 1.0, 3.0, 4.3, 10.0); tree
tree := u16 nLux; nLux × { u16 luxLo; u16 luxHi; u16 nCct; nCct × { u16 cctLo; u16 cctHi; u16 index } }
LUTs    @ lutOff: 114 × 14,739 bytes (17³ × 3)
Shading @ 0x19B376 (= 4096 + 114*14739): 55 records × 8 × f32 LE = 1,760 bytes   ← the "trailing ~1760 bytes"
```

- The "float region after the scene blocks" (for example 0.6) is the **zoom-block table**:
  0x400 + 6·216 = 0x910.
- Scenes: common, protrait, night, plants, food, sunrise_sunset. Each uses 19 LUTs: common 0–18, protrait
  19–37, …, sunrise_sunset 95–113.
- **Only "common" is used at capture.**

**Scene "common" (snapshot):**

| lux bin | CCT bin → LUT |
|---|---|
| 0–220 | 1–5500 → 0; 6000–10000 → 1 |
| 240–300 | 1–2450 → 2; 2650–3350 → 3; 3600–4400 → 4; 4700–10000 → 5 |
| 320–380 | same CCT split → 6, 7, 8, 9 |
| 400–470 | → 10–13 |
| 490–530 | → 14–17 |
| 550–999 | 1–10000 → 18 |

The preview bin differs in lux bins (0–210, 240–310, 330–380) and in all LUT bytes (mean |Δ| ≈ 11–13 levels).
Its shading data is identical. **The preview bin is never loaded by the capture path**: `LeicaParams(ctx)` uses
snapshot, and `LeicaParams(ctx, true)` has no callers.

**Shading trees.**

- Lux 0–180 is a single record per zoom. The middle lux bins are each split into 3 CCT bins (1–3000,
  3500–4200, 4800–10000). The top lux bin is a single record.
- Records per zoom: 0.6 → 0–10, 1.0 → 11–21, 3.0 → 22–32, 4.3 → 33–43, 10 → 44–54.
- Middle lux bins (zoom 1.0): 200–260, 290–410, 450–510; the top bin is 560–999. Other zooms have slightly
  different bins; parse them from the file.
- Examples:
  - #0 = [2.2, 0.1, 1.0, 1.02, 3.5, 0.6, 1.15, 0.05]
  - #11 = [1.5, 0.1, 1.0, 1.02, 3.5, 0.6, 1.15, 0.05]
  - #15 = [2.35, 0.1, 5.0, 1.02, 4.3, 0.6, 1.15, 0.1]

### 5.2 Selection / blending (exact)

```kotlin
class Bin(val lo: Int, val hi: Int, val index: Int, val sub: List<Bin>)
fun find(list: List<Bin>, v: Int): Pair<Bin, Bin> {           // (below, above) — equal when inside a bin
    for ((i, b) in list.withIndex()) if (v <= b.hi) return if (v >= b.lo || i == 0) b to b else list[i-1] to b
    return list.last() to list.last()
}
fun frac(v: Int, a: Bin, b: Bin) = (b.lo - a.hi).let { if (it == 0) 0f else (v - a.hi).toFloat() / it }
fun corners(tree: List<Bin>, lux: Int, cct: Int): LinkedHashMap<Int, Float> {
    val (l0, l1) = find(tree, lux); val (c00, c01) = find(l0.sub, cct); val (c10, c11) = find(l1.sub, cct)
    val fl = frac(lux, l0, l1); val f0 = frac(cct, c00, c01); val f1 = frac(cct, c10, c11)
    // add weights (summed per index, insertion order):
    // c00:(1-fl)(1-f0)  c01:(1-fl)f0  c10:fl(1-f1)  c11:fl*f1
}
fun lut(scene: String, lux: Int, cct: Int): ByteArray {        // unknown scene → "common"
    val m = corners(scenes[scene] ?: scenes["common"]!!, lux, cct)
    if (m.size == 1) return lutBytes(m.keys.first())
    return ByteArray(14739) { k -> m.entries.sumOf { (i, w) -> w * u8(lutOff + i*14739 + k) }.toInt().toByte() }  // truncate
}
fun shading(zoom: Float, lux: Int, cct: Int): FloatArray {
    val zi = zooms.indexOfLast { zoom >= it }.coerceAtLeast(0)
    return FloatArray(8) { k -> corners(shadingTrees[zi], lux, cct).entries.sumOf { (i, w) -> w * f32(0x19B376 + i*32 + 4*k) } }
}
```

Blending only happens in the gaps between bins (for example lux 221–239, CCT 5501–5999). Values outside the
table clamp to the end bins.

### 5.3 LUT memory layout

- **Index:** `((iR*17 + iG)*17 + iB)*3`, so R is the slowest axis and **B the fastest**.
- **Entry bytes:** stored as **(B, G, R)**: output R = byte+2, G = byte+1, B = byte+0.
- **Verified on the data:** byte0 tracks the fastest axis (corr 0.996), byte2 tracks the slowest axis
  (corr 0.997), and the grey diagonal is near-identity (0, 66, 132, 200, 255).
- **"R-fastest RGB" reading.** This is the same memory with R and B naming swapped. **Use the native mapping
  above**: input R picks the slowest axis.
- **GLES 3 upload.** Upload as a `GL_RGB8` 3D texture with `width` = fastest axis. Then sample with
  `texture(lut, vec3(b, g, r)*(16.0/17.0) + 0.5/17.0).bgr`.

### 5.4 applyFilter / m9::leicaPixel (per pixel, full strength, in place)

```kotlin
// trilinear 3D LUT
val fr = R/255f*16; val fg = G/255f*16; val fb = B/255f*16
val ir = min(floor(fr).toInt(),15); val ig = min(floor(fg).toInt(),15); val ib = min(floor(fb).toInt(),15)
// 8-corner trilinear with weights (1-dr|dr)(1-dg|dg)(1-db|db) → (Rl, Gl, Bl) in [0,1]
// "shading" = radial vignette modulated by a luma sigmoid (s = shading float[8])
val u = ((x + 0.5f)/W - 0.5f) * W / H;  val v = (y + 0.5f)/H - 0.5f          // units of image height
var n = sin((u - 0.5f)*12.9898f + (v - 0.5f)*78.233f) * 43758.547f; n -= floor(n)   // hash dither
val rr = sqrt(u*u + v*v) + n*0.04f*s[2]
val t  = ((rr - s[0]) / (s[1] - s[0])).coerceIn(0f, 1f); val sm = t*t*(3 - 2*t)   // s1 = inner (off), s0 = outer (full)
val yA = 0.499f*Rl + 0.387f*Gl + 0.114f*Bl                                  // control luma (sic)
val f  = s[7] + s[6] / (1 + exp(s[4]*(s[5] - yA)))                           // sigmoid: protects highlights
val gain = s[3] * (f + sm*(1 - f))
val Y  = 0.299f*Rl + 0.587f*Gl + 0.114f*Bl
out = clamp((Rl,Gl,Bl) + Y*(gain - 1), 0, 1) → q = clamp(rint(v*255), 0, 255); A = 255
```

- **Example** (record #0 on 4:3): centre ×1.02, corner midtone ≈ ×0.885, highlights less darkened.
- **Threads:** `min(4, hw, h)` threads when w·h ≥ 1,000,000. This maps 1:1 to a fragment shader.

---

## 6. AE, Tuning (ipe_tables.json), DngMeta, RAW render — **DNG path only**

None of this runs for JPEG input. Port it only if you want a RAW (DNG) front end. On the Xperia,
Camera2 RAW_SENSOR → DNG would follow this path.

### 6.1 `AE.meter(ctx, stats, lux, camera)` → `AE{shortExposure, adrc}`

- **Which file.** `ae_m9.json` is used, or `ae_tele.json` when camera == "tele".
- **Top-level blocks:** `metering` (330 flat dotted keys), `luma` (26), `drc` (53), `stylization` (40; empty in
  tele), `whiteblack` (13), `metering_normal` (331).
- **Table layout.** 2-D tables are `[secondAxisRow][luxColumn]`.

Keys used (`HS = metering."hist_target_by_lux.hist_short_long_adjustment."`):

| Key | ae_m9 | ae_tele |
|---|---|---|
| HS hist_lux_node | [0, 50, 100, 200, 280, 350, 450, 480] | first 7 |
| HS hist_base_bright_start_pct | [.97, .97, .974, .978, .978, .978, .978, .975] | |
| HS hist_dark_tone_end_pct | [.1, .1, .1, .1, .15, .18, .18, .18] | |
| HS hist_dr_node | [0, 10, 25, 50, 70, 100, 150, 220, 250, 300] | |
| HS hist_bright_tone_ref_target [10×8] | row0 [135, 132, 134, 120, 108, 100, 90, 90] … row9 [175, 160, 150, 130, 110, 100, 90, 90] | |
| base_target_lux_node | [0, 123, 173, 283, 343, 423, 483, 513, 583] | [0, 120, 170, 280, 360, 450, 480, 510, 580] |
| base_target_dr_node | [1, 60] | [1, 5, 15, 80] |
| base_target | [[26, 26, 26, 26, 26, 26, 23, 21, 18], [25, 25, 25, 25, 26, 26, 24, 21, 19]] | ≈55 → 30 |
| stylization enable | true | false |
| style_base_target_lux_node | [0, 123, 173, 283, 370, 423, 483, 513, 583] | |
| style_base_target_scale[0] | [.6, .6, .65, .7, .7, .7, .6, .6, .6] | |
| luma center_wghted_mtr_tbl | 16×16 Gaussian, 0.1016..1.0 | |
| luma lux_node | [0, 123, 173, 283, 343, 423, 483, 513, 583] | |
| luma bright_start | [120, 120, 120, 140, 150, 180, 180, 180, 180] | |
| luma bright_end | [180, 180, 180, 180, 190, 220, 220, 230, 230] | |
| luma bright_weight | [.38 ×5, .98 ×4] | |
| drc nor_drc_lux_node | [0, 333, 353, 383, 423, 453, 485, 573] | [0, 250, 330, 350, 380, 420, 460, 530, 570] |
| drc max_drc_gain[0] | [4.5, 4.5, 4, 4, 4, 4, 4, 1] | [5.5, 5.5, 5.5, 4.5, 4.5, 4, 3, 3, 1] |

All other blocks are unused: spot, core, adaptive, mid-tone, flat, night, saturation-prevent, face,
whiteblack, metering_normal.

**`stats` (from `rawStats`).**

- `[0..255]` are the 16×16 zone means of Y×255. Y = 0.299R + 0.587G + 0.114B on linear, D50-balanced camera
  RGB divided by 65535.
- `[256..]` are Y×255 values sampled every `max(1, (int)sqrt(w*h/400000.0))` px, sorted ascending.

```kotlin
fun i1(x, xs, ys): piecewise-linear, clamped at both ends
fun i2(x, y, xs, ys, tbl) = i1(y, ys, tbl.map { row -> i1(x, xs, row) })
fun pct(s, a, b): n = s.size-256; ia=(a*n).toInt(); lo=clamp(ia,0,n-1)+256; hi=min(n, max((b*n).toInt(), ia+1))+256; mean(s[lo until hi])
fun weighted(s, gain, lux): bs,be,bw = i1(lux, lux_node, bright_start/end/weight)
    Σ_i y_i*w_i / Σ w_i, y_i = s[i]*gain, w_i = tbl[i]*(1+(bw-1)*clamp((y_i-bs)/max(be-bs,1e-6),0,1))
meter(stats, lux):
    require(stats.size > 256); if (stats.last() <= 1e-6) return AE(1, 1)
    bright = pct(stats, i1(lux, HSlux, bright_start_pct), 1.0); dark = max(pct(stats, 0, i1(lux, HSlux, dark_end_pct)), 0.001)
    dr = bright/dark
    target = i2(lux, dr, base_target_lux_node, base_target_dr_node, base_target) * (stylization ? i1(lux, style_lux_node, style_scale[0]) : 1)
    g = target / max(weighted(stats,1,lux),1e-6); repeat(20) { gn = min(g*target/max(weighted(stats,g,lux),1e-6), 1024); if (|gn/g-1|<1e-4) break; g = gn }
    ratio = i2(lux, dr, HSlux, hist_dr_node, hist_bright_tone_ref_target) / max(bright,1e-6)
    adrc = max(1, min(i1(lux, nor_drc_lux_node, max_drc_gain[0]), g/max(ratio,1e-9)))
    shortExposure = g/adrc
```

### 6.2 `Tuning.config(camera, cameraToSrgb, lux, cct, ae, second)` → `float[5145]`

`ipe_tables.json` has five tables: m9, normal, tele, tele_normal, m9_lin. Each has these keys, each holding a
list of `{path:[[level, lo, hi]…], data:[…]}` range-tree leaves:

| key | length | use |
|---|---|---|
| gamma152_ipe_v2 | 257 | used |
| cc15_ipe_v2 | 102 | first 9 = 3×3 CCM |
| cv122_ipe_v2 | 14 | used |
| tdl13_ipe_v2 | 964 | first 768 used |
| ltm21_ipe_v2 | 526 | unused |
| tmc202_sw_v2 | 75 | used |

**Axis lookup values:** drc = ae.adrc, gain = 1.0, lux, cct, zoom = 4.0, flag = 0, one = 0.5.

**Per-table axis orders:**

- Non-tele tables:
  - gamma: [one, drc, gain, one, lux, cct]
  - cc and tdl: [drc, flag, lux, cct]
  - cv: [drc, gain, flag, lux, cct]
  - tmc: [drc, gain, lux]
- Tele tables: see fork notes; `scripts2/ipe_sum.py` lists them.

**Tree evaluation.** Nodes are sorted by (lo, hi). At each level:

- If a node contains x, recurse into it.
- Otherwise lerp between the nearest node below and the nearest node above, using
  `(x − below.hi) / (above.lo − below.hi)`.
- If one side is missing, clamp to the other.

```kotlin
T  = if (camera=="tele") "tele" else "m9";  TN = if (camera=="tele") "tele_normal" else "normal"
G  = if (second && has(T+"_lin")) T+"_lin" else T
M  = cc(T)[0..8] · inverse(cc(TN)[0..8]) · cameraToSrgb
gs = if (second) 0.3f * (if (lux > 150 && adrc < 1.33) adrc/1.33 else 1.0) else 1.0
out[0]=shortExposure*gs; out[1..9]=M; out[10..777]=tdl(T) (384 hue-shift deg + 384 sat-delta, 24 hue × 16 sat)
out[778..1034]=gamma(G)/1023; out[1035..5130]=tmc(tmc202(G), adrc) (4096 pts, monotone cubic Hermite, 7 knots); out[5131..5144]=cv(T)
```

- **m9 cv** = [0.299, 0.587, 0.114, 0, 0.49, 0.47, −0.338, −0.338, 0.47, 0.47, −0.162, −0.162, 512, 512].
- **normal cv** uses 0.53 / 0.50 in place of 0.49 / 0.47.

**tmc knot construction.** The curve is built from t[0], t16, t17, t20 and t36..t42:

```kotlin
a = clamp((adrc-1)*t16 + 1 + t17, 1e-6, 1023);  c = min(clamp(t20+1, 1e-6, 1023)*a, 256);  d = a-1;  step = (t42-t38)/4
Gk = [c, a, t39*d+1, t40*d+1, t41*d+1]
seq(x, lo, hi) = if (x <= lo) lo else min(x, hi)
X0 = Y0 = 0;  X6 = Y6 = 1
X1 = seq(t37/c, t36+1e-4, .9995);  X2 = seq(t38/a, X1+1e-4, .9996)
X3 = seq((t38+step)/Gk2, X2+1e-4, .9997);  X4 = seq((t38+2step)/Gk3, X3+1e-4, .9998);  X5 = seq((t38+3step)/Gk4, X4+1e-4, .9999)
re-sequence: Xi = seq(Xi, (i==1 ? 1e-4 : X[i-1]+1e-4), .9994 + i*1e-4)                 for i in 1..5
             Yi = seq(Xi*Gk[i-1]^t0, (i==1 ? 1e-4 : Y[i-1]+1e-4), .9994 + i*1e-4)      for i in 1..5
h_i = X[i+1]-X[i];  del_i = (Y[i+1]-Y[i])/h_i
m0 = 0;  m_i (i=1..5) = (w1+w2)/(w1/del[i-1] + w2/del[i]),  w1 = 2h_i + h[i-1],  w2 = h_i + 2h[i-1]
m6 = ((h4+2h5)*del5 - h5*del4)/(h4+h5)
     if del4*del5 < 0 and |m6| > |3del5|: m6 = 3del5;  else if del5*m6 < 0: m6 = 0
c2_i = (3del_i - 2m_i - m[i+1])/h_i;  c3_i = (m_i - 2del_i + m[i+1])/h_i²
out[k], x = k/4095:  x <= X1 → Y1*x/X1;  else Y_j + m_j*dx + c2_j*dx² + c3_j*dx³ on the last segment j (1..5) containing x
```

### 6.3 Native RAW render (`rawOpen` / `rawRender` / `Ipe::yuv` / `gainmap`)

**Decoder.** `RawApi` dlopens **libzxwdecoder.so**, which is LibRaw's C API. `rawOpen` sets:

- gamma (1, 1), i.e. linear
- 16 bps output
- camera colour space (`output_color = 0`)
- AHD demosaic (3)
- no auto-brighten
- highlight clip (0)
- user_mul = D50 white balance

It requires 3 colours at 16 bits, at least 16 px per side and at most 64 MP.

**Per pixel (cfgA → full-res bitmap):**

1. `rgb *= cfg[0]`.
2. Tone curve as a luminance ratio: `rgb *= tmc[Y]/Y`, with Y = 0.299R + 0.587G + 0.114B and linear
   interpolation in the 4096-entry table.
3. `rgb = max(0, M·rgb)`.
4. HSV: hue shift and saturation scale from bilinear lookups in the 24×16 tables (hue step 15°, sat step 1/15),
   `H' = fmod(H + Δ + 720, 360)`, `S' = clamp(S·(1 + Δs), 0, 1)`.
5. Per-channel 257-entry gamma.
6. Asymmetric YUV:
   - `Y = cv0·R + cv1·G + cv2·B`
   - `dB = B − G`, `dR = R − G`
   - `U' = dB + (dB ≥ 0 ? cv6 : cv7)·dR`
   - `V' = dR + (dR ≥ 0 ? cv10 : cv11)·dB`
   - `U = U'·(U' ≥ 0 ? cv4 : cv5)`, `V = V'·(V' ≥ 0 ? cv8 : cv9)`
7. BT.601 back to RGB: `R = Y + 1.402V`, `G = Y − 0.344136U − 0.714136V`, `B = Y + 1.772U`, then ×255 and
   round.

**Gain bitmap (cfgB: m9_lin tables, 0.3× exposure), half size:**

1. Compute Y×255 at full resolution, and U, V averaged over 2×2 blocks.
2. For each output pixel compute `dR = 359v/256`, `dB = 454u/256`, `dG = (−88u − 183v)/256`.
3. Output grey `clamp(Y + (mean(dR, dG, dB) + max(0, max(dR, dG, dB)))/2, 0, 255)`.

**HDR boost** (`FullM9Engine.boost`):

- `p = 100·count(B byte ≥ 250)/(w·h)`.
- `b = 1.0` if p ≤ 8, `0.8` if p ≥ 20, otherwise `1 − (p − 8)/12·0.2`.
- `boost = (int)(b·500)/100f`, giving 4.0..5.0.
- This becomes `ratioMax` of the RAW gain map.

### 6.4 DngMeta

**Defaults:** lux 300, CCT 5000, zoom 1.0, camera "main".

**IFD walk:** IFD0, SubIFDs (tag 330) and Exif IFD (0x8769), depth ≤ 12, ≤ 256 IFDs. The first occurrence of a
tag wins.

**Tags:**

- ColorMatrix1 (required) and ColorMatrix2.
- AsShotNeutral. If missing, LibRaw `cam_mul` gives `[G/R, 1, G/B]` (`rawNeutral`).
- CalibrationIlluminant1 (default 21) and CalibrationIlluminant2 (default 17).

**EXIF light-source codes → K:**

| Code | K | Code | K |
|---|---|---|---|
| 1 | 5500 | 15 | 3450 |
| 2 | 4200 | 16 | 6504 |
| 3 | 2856 | 17 | 2856 |
| 4–9 | 6504 | 18 | 4874 |
| 10 | 6500 | 19 | 6774 |
| 11 | 7500 | 20 | 5503 |
| 12 | 6400 | 21 | 6504 |
| 13 | 5000 | 22 | 7504 |
| 14 | 4150 | 23 | 5003 |
| 24 | 3200 | other | 6504 |

**Matrix blend:** `w = clamp((1/T − 1/T2)/(1/T1 − 1/T2), 0, 1)`; `CM = w·cm1 + (1 − w)·cm2`.

**CCT:** the McCamy loop of §3.1 (start 5000, ≤ 30 iterations, |Δ| < 1, clamp 1500..20000).

**White balance passed to LibRaw:**

- `camD50 = CM(5003)·[0.96422, 1, 0.82521]`, normalised so G = 1.
- `wb = [1/camD50[0], 1, 1/camD50[2], 1]`; each value must be in (0, 64].
- The image is always rendered **D50-balanced**, and the as-shot neutral is used only for CCT.

**cameraToSrgb:** `inverse(rowNormalize(CM_D50 · sRGB→XYZ))`, using the dcraw method with no Bradford
adaptation. The sRGB→XYZ matrix is:

```
[[0.4124564, 0.3575761, 0.1804375],
 [0.2126729, 0.7151522, 0.072175],
 [0.0193339, 0.119192,  0.9503041]]
```

**Lux:** same EV formula as §3, from tags 829A, 829D and 8827 (falls back to 8833).

**f35 (A405):**

- ≥ 50 → camera "tele" and `zoom = clamp(f35/23, 3.2, 4.3)`.
- 0 < f35 < 18 → throws "Ultra-wide RAW front end is not implemented". The Xperia's 16 mm ultra-wide would hit
  this.

---

## 7. HDR gain map (`FullM9Hdr`, `$Api34`, `GainPlane`, `HdrMath`, `NightHighlights`) — API 34+

The source gain map is always discarded. A new one is synthesised from the final SDR image:

```kotlin
fun luma(px) = (0.2126f*R + 0.7152f*G + 0.0722f*B)/255f
fun smoothstep(e0, e1, x) = clamp((x-e0)/(e1-e0), 0, 1).let { it*it*(3-2*it) }
fun generated(px) = gray(round(255f * smoothstep(0.36f, 0.94f, luma(px))))
// GainPlane.smooth: separable [1,2,1]/4 on the BLUE byte, edge-clamped, integer (+2)/4 rounding; output grey ARGB
fun generate(bmp: Bitmap /*final SDR*/) {
    NightHighlights.prepare(bmp)                                   // night-scene highlight shoulder, see below
    val sc = min(1f, 384f / max(w, h)); val gw = max(1, round(w*sc)); val gh = max(1, round(h*sc))
    var g = NativeM9.hdrSample(bmp, gw, gh)                        // area-average luma, grey ARGB
    g = GainPlane.smooth(g.map(::generated), gw, gh)               // fromPixels
    g = GainPlane.smooth(g, gw, gh)                                // second pass (as coded)
    attach(bmp, g, gw, gh, max = 2.0f)                             // HdrMath.limit(style==1 ? 1.8 : 2.0); M9 = style 8 → 2.0
    NightHighlights.limit(bmp)
}
fun attach(bmp, px, w, h, max) {
    val gm = Gainmap(Bitmap.createBitmap(px, w, h, ARGB_8888))
    gm.setRatioMin(1f,1f,1f); gm.setRatioMax(max,max,max); gm.setGamma(1f,1f,1f)
    gm.setEpsilonSdr(0f,0f,0f); gm.setEpsilonHdr(0f,0f,0f)
    gm.minDisplayRatioForHdrTransition = 1f; gm.displayRatioForFullHdr = max
    bmp.gainmap = gm
}
```

**DNG path.** `attachRaw` uses the half-size RAW gain bitmap instead, with `boost` as ratioMax. It requires
`1 < boost ≤ 8`.

**NightHighlights.prepare** (skipped for RAW):

- Sample a grid with stride `max(1, (dim + 255)/256)` (**UNCERTAIN** per-axis).
- Per sample: `y = round(luma·255)`, `sum += y`; `dark++` if y < 77; `bright++` if max(R, G, B) ≥ 204.
- `night = dark ≥ 0.65n && sum < 76.5n && 0 < bright ≤ 0.15n`.
- If night, for every pixel with `m = max(R, G, B) > 183`: scale R, G, B by `shoulder(m/255)/(m/255)`, where
  - `shoulder(x) = x ≤ 0.72 ? x : 0.72 + 0.28·t/(1 + 0.32·t)`
  - `t = (x − 0.72)/0.28`

**NightHighlights.limit:** if night, `ratioMax = min(ratioMax, 1.2)` and `displayRatioForFullHdr ≤ 1.2`.

**Encoding.** `Bitmap.compress(JPEG, 100)` of a bitmap that carries a gain map writes Ultra HDR (JPEG_R/MPF).

**Unused code.** `HdrMath.source` / `difference` (source-gain-map transfer) are dead code.

---

## 8. Realtime preview (`FullM9Preview` → `lmport.PreviewTone`)

The preview uses **no ONNX model and no LUT**. A GLSL patch is injected into the host preview shader:
`main` is renamed `lmOriginalMain`, and a new `main` applies `lmLook(outColor.rgb, vTexCoord, lmTone)`.

- The look is applied only when drawing to the default framebuffer (`GL_FRAMEBUFFER_BINDING == 0`).
- Full M9 uses **mode 2**.

```glsl
vec3 lmSat(vec3 c,float s){float y=dot(c,vec3(0.2126,0.7152,0.0722));return mix(vec3(y),c,s);}
// mode 2 (Full M9):
float y=dot(c,vec3(0.2126,0.7152,0.0722));
float warmth=1.0-smoothstep(0.65,1.0,y);
c+=vec3(0.010,0.003,-0.007)*warmth;
c=lmSat(c,1.06);
c=clamp(c,0.0,1.0);
// other modes: 1 = mono contrast 1.08; 3 = mode2 + sat 1.25, contrast 1.10, warmth (0.016,0.005,-0.012);
// 4 = sat .96, contrast 1.03, vignette 0.12*smoothstep(.25,.78,r); 5 = split-tone sh(-.018,.012,.024)/hi(.022,.008,-.018), sat .96, con 1.04;
// 6 = cool (-.008,.002,.014), sat 1.035, con .98; 7 = shadow lift +.012, sat 1.025
```

The look works on sRGB-encoded values and uses no per-frame lux or CCT.

**Port recommendation (beyond the original).** For a closer WYSIWYG preview, apply §5.4 in the preview shader
with the preview-bin LUT for the current lux and CCT. This is optional and not what the app does.

---

## 9. M9CpuPolicy — ORT backend/threads selection, fallback, calibration

```kotlin
data class Config(val backend: String /* "CPU" | "XNNPACK" */, val threads: Int /* 1..8 */)  // toString "CPU:8"
fun cores() = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
fun defaults() = Config("CPU", cores())
fun candidates(cur) = linkedSetOf(Config("CPU", min(4, cores())), Config("CPU", min(6, cores())),
                                  Config("XNNPACK", cores()).takeIf { XNNPACK in OrtEnvironment.getAvailableProviders() }) - cur
// persistence: noBackupFilesDir/"fullm9-cpu-<model>.properties": key, backend, threads, time
// key = "p7-cpu-v1|<model>|<sha256>|<ORT version>|<os.arch>|<os.version>|<availableProcessors>"; valid 7 days (604_800_000 ms)
```

**Session options:**

- `intraOp = if (XNNPACK) min(2, cores()) else threads`; `interOp = 1`.
- CPU arena off; memory pattern off; `ALL_OPT`.
- `setSymbolicDimensionValue("height", 864|544)`, `("width", 1120|544)`.
- XNNPACK adds `session.intra_op.allow_spinning=0` and `addXnnpack(intra_op_num_threads=threads)`.
- Telemetry off. FP32 only.

**Fallback:**

- Any `OrtException`, or a session-creation failure on a non-CPU backend, marks `name:config` as FAILED,
  deletes the saved config and recreates the session with `defaults()`. The run is then retried once.
- CPU failures propagate.

**Model cache:**

- Loading "low" evicts "high" and vice versa.
- All sessions and buffers are released after 60 s idle.
- The direct buffers hold 3,870,720 floats (input) and 2,903,040 floats (output).

**Calibration** (colorfix only, when ≥ 16 visible tiles and no saved config):

1. Time 3 tiles on the current config. `median = sorted[1]`. A config becomes the winner if
   `median < 0.9·best`.
2. For each candidate:
   - Run the reference session on the tile and snapshot its 887,808-float output.
   - Swap in the candidate and time it.
   - Accept it only if every value is finite with `|Δ| ≤ 1e-4`, and the interior (16-px border excluded)
     q8 pixels are identical. Otherwise mark it FAILED, restore the output and reject.
3. Save the winner.

There are no wall-clock budgets or timeouts.

**Progress:** 12% after the style model, then `12 + 65·(i+1)/nTiles` per tile.

---

## 10. Helpers (optional for a port)

- **`DenoiseFlow.prepare`** → `OutputQuality.prepare` → `QualityPass.prepare` (style 8):
  - Native `libleicadenoise.so` `leica_filter(bitmap, strong=false)`, run in place on the full-res input
    before the model. Its internals were not disassembled (**UNCERTAIN**).
  - Fallback `SpatialPass.denoise`: MAD·0.7413 sigma, threshold 2.8σ, blends 0.66 / 0.86.
  - Then `ChromaPass.apply`: YCgCo chroma NR, MAD estimate on a 512 grid, constants 2.4 / 2.8 / 3 / 5 / 7 and
    0.35 / 0.65 / 0.72.
  - The source gain map is removed.
  - Port: use a mild luma/chroma NR or skip it. The models were trained on Xiaomi-ISP output, which is already
    denoised.
- **`DenoiseFlow.finish`**: no-op for style 8. **`QualityPass.fullDetail`**: fallback sharpening only if
  restoreTexture did not run.
- **`CloudGraph.hook/palette`**: cloud-offload hooks; null or no-op locally.
- **`CaptureSnapshot`**: JSON-serialised job state for background processing (`schema=1`,
  `pipeline="p7-cpu"`, lux, cct, zoom, provenance, size, orientation, exposure, iso, aperture, focal,
  location, watermark flags).
- **`CaptureState`**: host reflection glue.
- **`ToneTouch`**: UI touch-target enlarger (48 dp). It does no tone adjustment.
- **`ExifOrientation.upright`**: rewrites tag 0x0112 to 1 in APP1.
- **`JpegScan`**: finds the SOS offset (FFDA) and compares entropy-coded payloads, to detect whether the JPEG
  body changed.
- **`OutputQuality` / `LegacyQuality`**: begin/end style bookkeeping. LegacyQuality (`restoreDetail`) belongs
  to the old LMoment path and is not used.

---

## 11. Constants (decimal)

| Where | Constant |
|---|---|
| Frame | canvas 4096×3072; tile 512 (8×6 = 48); tile context 16 → 544; style 1024×768 + 48 pad → 1120×864 (W×H); 4×4 box |
| Norm | n8 = (u − 128)/127.5; q8 = rint(v·127.5 + 128); PAD = −1.003921627998352; SNPE delta 0.007843 (2/255), offset −128 |
| Model switch | lux > 260 && cct < 4692 → low, else high |
| Settings | defaults lux 300, cct 5000, zoom 1.0; lux = clamp(474.9 − 23.45·EV100, 0, 999); vendor lux 0..1000; cct 1500..20000; tele f35 ≥ 50 → zoom = clamp(f35/23, 3.2, 4.3) |
| Scale | fit to 4096×3072 with s = min(4096/w, 3072/h, 1); portrait rotated +90° |
| LUT | 17³×3 = 14,739 bytes; 114 LUTs @ 0x1000; shading 55×8 f32 @ 0x19B376; zoom table 0.6 / 1 / 3 / 4.3 / 10 @ 0x910 |
| Shading | dither hash 12.9898, 78.233, 43758.547; dither amp 0.04; luma A (0.499, 0.387, 0.114); luma B (0.299, 0.587, 0.114) |
| restoreTexture | samples ≈ 16,000; 20th percentile / 1.133; thr 0.8σ + 0.75; reg 36σ²; k ∈ [0.5, 1.5], floor 64; +0.5 / −0.25 gates; coherence (c − 0.35)/0.4; ±12 clamp; midtone /16; hashes 0x85EBCA77, 0x9E3779B1, 0xC2B2AE3D |
| HDR | gain map ≤ 384 px; smoothstep(0.36, 0.94); ratioMax 2.0 (1.8 for style 1); night cap 1.2; night thresholds 77 / 204 / 183, 0.65 / 76.5 / 0.15; shoulder 0.72 / 0.28 / 0.32; RAW boost 4.0..5.0 (8 / 20 %, 250 level) |
| Output | JPEG q100 (DNG EXIF dummy q97); Display P3 bitmap; EXIF Orientation = 1 |
| ORT | idle release 60 s; calibration ≥ 16 tiles, 3 samples, 0.9× improvement, tolerance 1e-4; config TTL 7 days; threads ≤ 8, XNNPACK intra ≤ 2 |
| AE / RAW | 1024 gain cap, 20 iterations, 1e-4; Tuning 0.3, 150, 1.33; YUV→RGB 1.402 / −0.344136 / −0.714136 / 1.772; gainmap 359 / 454 / −88 / −183 (/256); McCamy 0.332, 0.1858, 449, 3525, 6823.3, 5520.33; D50 (0.96422, 1, 0.82521), 5003 K |

---

## 12. Porting notes for the Xperia 1 IV (Android 14, no vendor tags, no QNN HTP v79/v81)

1. **Capture.** Use JPEG from Camera2 (or YUV_420_888 converted to sRGB RGBA8888). Read the per-frame
   `CaptureResult` and compute `luxIndex` (§3.1). For CCT, use the McCamy method on `SENSOR_NEUTRAL_COLOR_POINT`
   with `SENSOR_COLOR_TRANSFORM1/2` and `SENSOR_REFERENCE_ILLUMINANT1/2`. Zoom comes from `CONTROL_ZOOM_RATIO`
   or the physical lens (tele → 3.2..4.3; ultra-wide → 0.6 bin).
2. **Models.** Ship the reconstructed `low.onnx`, `high.onnx` and `colorfix.onnx` (or the .m9w files plus the
   §1.2 decoder). Run them in ORT Android, CPU FP32, with symbolic dims fixed. Optionally try NNAPI or XNNPACK
   with the §9 equality check.
3. **Native Frame logic (§4.2–4.4).** About 150 lines of Kotlin/NEON or a GLES compute shader. Keep the exact
   padding modes and q8 round trips.
4. **Post (§5.4).** applyFilter as a GLES fragment shader with a 3D texture, using the B-fastest / BGR layout.
   Then restoreTexture (§4.6) on the CPU.
5. **HDR (§7).** `Bitmap.setGainmap` (API 34) followed by `compress(JPEG, 100)` gives Ultra HDR on Android 14.
6. **Domain gap (UNCERTAIN).** The models expect Xiaomi ISP output: mild NR, Xiaomi tone curve. Sony JPEGs are
   sharper and flatter, so expect some look deviation. Applying a mild NR before the models (as
   DenoiseFlow.prepare does) is advisable.

### UNCERTAIN summary

- Camera2 → lux-index equivalence (the post-raw boost term) and the COLOR_CORRECTION_GAINS → neutral
  approximation.
- Internals of `libleicadenoise.so`.
- The per-axis stride in NightHighlights.
- JPEG chroma subsampling (framework default).
- Whether the Xiaomi LUT naming is BGR or RGB. This is irrelevant if you follow the native mapping in §5.3.
