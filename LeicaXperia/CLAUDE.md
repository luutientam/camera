# Leica Look for Xperia: handoff notes

**Goal (from the user):** build a new camera app that ports **all** the colour/look logic of `~/Desktop/Leica Port 8.9.apk`
(a Xiaomi HyperOS Leica camera port, package `com.kobbin.leica.wmrebuild`) so it runs smoothly and looks professional on
the user's **Sony Xperia 1 IV (XQ-CT72, SM8450 / Snapdragon 8 Gen 1, Android 14)**. The user writes in Vietnamese, so
reply in Vietnamese. This is for personal use on the user's own phone; do not redistribute the extracted assets.

Why the original app fails on this phone: it needs Xiaomi/Qualcomm vendor tags (`com.xiaomi.*`,
`org.codeaurora.qcamera3.*`), which Sony's HAL does not expose (it only has `com.sonymobile.*`). It also needs a QNN HTP
v79/v81 NPU, and the 8 Gen 1 has v69. The result is a black viewfinder.

## Environment
- Project: `~/Documents/GitHub/camera/LeicaXperia` (git repo root is `camera/`; `camera_leica/` there is an unrelated Compose template). AGP 9.4.1 (Kotlin is built in, no kotlin plugin), Gradle 9.6.0 wrapper,
  compileSdk/targetSdk 36, minSdk 31, arm64 only, `onnxruntime-android:1.22.0`. Plain Android Views plus GLES 3; no Compose
  and no AndroidX.
- Build: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; ./gradlew :app:assembleDebug`
- Install: `ANDROID_SERIAL=adb-QV771D6GCK-QxWATQ._adb-tls-connect._tcp ~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`
  (the phone is on wireless debugging and drops off now and then; emulators are also attached, so always set `ANDROID_SERIAL`).
  An install built on another machine has a different debug key: `adb uninstall com.xperia.leicalook` first.
- Python for asset work: create a venv with numpy, pillow, onnx and onnxruntime, and run scripts with `-I`.
  `tools/prepare_assets.py "<apk>" app/src/main/assets` regenerates the assets.
- **Do not run jadx**: the auto-mode classifier denied it as "code from external". Use
  `~/Library/Android/sdk/build-tools/36.0.0/dexdump -d` or `docs/tools/minidex.py` (pure-Python dex disassembler) instead.

## Device facts (docs/xperia1iv_camera_dump.txt = `dumpsys media.camera`)
- Camera id **0** = logical rear (physical 2 = 24 mm f/1.7, 3 = tele 10.22 mm f/2.3, 4 = ultra-wide 16 mm f/2.2).
  `CONTROL_ZOOM_RATIO` range 0.629–14.85, FULL, RAW. Id 1 = front, id 5 = ToF.
- YUV_420_888 up to 4032x3024. AE FPS ranges include [30,60] and [60,60]. EV ±2 in steps of 1/12. AF regions max 32,
  AE regions max 1.
- Leica focal stops in the UI: 16/24/35/50/85/125 mm, which is zoom ratio = mm/24.

## Reverse-engineering results (full detail in docs/)
- `docs/spec_cameralook.md`: the 23 cameralook tones. Names and exact params are already in `LookCatalog.kt`.
  The CLU1 LUT format and the GLSL ports are in §11 (tetrahedral LUT → RGB curve → luma curve → intensify(strength) → film
  pass with clarity, EV vignette and hash grain). Skipped on purpose: pass 2 sharpening (Sony ISP already sharpens) and HDR
  gain maps. The NightHighlights shoulder (§6) is in `capture/NightHighlights.kt` (TONE looks, stills only).
- `docs/spec_donor120.md`: Leica styles. Port chains (§1 "Recommended port chains"):
  - M9: M9Distill → m9 bank LUT → warm(-0.95 EV, 0.3).
  - M9+: the same with warm(-1.3, 0.5), then the boost matrix (sat 1.25, contrast 1.1, warm 0.04; §10).
  - M10R: M9Distill → MadridShader(grain 0.3, vignette 1.0; §11).
  - L-M9: Lm9Stats (§7.1, exact, per-pixel soft segmentation plus per-class curves; the class means come from a 320 px probe).
  - M3: MonoRows (§8, exact).
  - M13/M4/LP9 need assets that are not in the APK, so they are skipped.
  - The "warm" algorithm (§5.4) is native and unknown; implement the guess and tune it by eye.
- `docs/spec_fullm9.md`: the fullm9 pipeline (AE json, ipe_tables, .m9w decoding, shading). That work also reconstructed `docs/models/{low,high,colorfix}.onnx` from the `.m9w` files
  (styletrans low/high plus colorfix, the full M9 neural pipeline; CPU-heavy, ~4–5 s per photo). It is optional as an
  "HQ" capture mode.
- **Key finding: m9_distill is an HDRNet bilateral affine model** (verified exactly, error 4e-7):
  `out = clip(A(x)·rgb + b(x) + clip(R(x), -16, 16), 0, 1)`.
  - A (3x3) and b come from a 12-channel grid (`/head_grid/Conv_output_0`, lowres/16), resized bilinearly (half-pixel)
    to floor(full/16) and then to full.
  - R = `/dec/dec.6/Conv_output_0` (lowres/4), resized to full.
  - Channel layout: coeff[i*3+j] = A[i][j], coeff[9+i] = b[i].
  - The donor feeds lowres = a 2x2 box-downsample of the full image (in 1024 tiles with a 96 px halo).
  - `app/src/main/assets/models/m9_distill_maps.onnx` is the extracted encoder-only submodel (input `lowres` only).
    `neural/M9Style.kt` runs it and builds the RGBA16F map textures. The shader does the per-pixel affine, so it runs in
    the 60 fps preview (small lowres, temporal smoothing) and at full 12 MP for stills. Stills should use lowres = half-res
    upright frame, as the donor does. Running the full graph at 12 MP needs about 1.8 GB, so don't.
- Leica bank `.bin` format: `look/LeicaBank.kt` (LUT data at 0x1000, 17³ RGB8, R fastest). M3 and M10R banks are all
  identity. After the LUTs there are 55 "shading records" of 8 floats each (zoom-indexed shading). The donor always
  used lux 200 / CCT 5500 (common LUT #0). This port picks the LUT from real scene values instead.
  `camera/SceneEstimator.kt` turns EV100 into a Qualcomm-style lux index (23.45 units per EV, EV15 → 120) and computes CCT
  with the DNG algorithm from Sony's COLOR_TRANSFORM matrices and the neutral point.
  `LeicaBank.corners` is the exact linear-fraction blend of spec_donor120 §5.2; the 8 shading floats are applied
  (`shade()` in the shader, spec_fullm9 §5.4).

## Code status
The refactor to the new `LookState` model is finished and the app builds. The shader (`gl/LookProgram.kt`), the style-map
textures, the preview grab (`gl/PreviewRenderer.kt`), the two-pass stills with NightHighlights (`capture/StillProcessor.kt`)
and `MainActivity` all use it. It launches on the phone without crashing.

Look data versions (bank LUTs, style maps) share one counter in `LookEngine`. Versions created for a still are pinned until
`StillProcessor` releases them, because the preview publishes about 7 versions/s and prunes old ones.

**Next:** test on the phone. Check the preview frame rate (`dumpsys SurfaceFlinger --latency "<SurfaceView ...(BLAST)#n>"`;
gfxinfo only sees the UI thread), take photos with each look, pull them from `DCIM/LeicaLook`, and inspect them. Compare
against the Python references (`docs/tools/cameralook_ref.py`, onnxruntime on m9_distill.onnx). Then tune the warm guess
by eye.

Optional later: an "HQ" mode using `docs/models/high.onnx`/`low.onnx` + `colorfix.onnx` (styletrans; lux threshold 260 and
cct 4692 in `assets/leica/styletrans_params.json`; input (c−128)/127.5 at 864x1120; see spec_donor120 §4) as a background
job after capture; a RAW/DNG option; a settings screen.

## Previously installed on the phone (by the earlier session)
`com.agc.cam` (AGC GCam 8.4) and `com.kobbin.leica.wmrebuild` (the Leica port, black viewfinder). Leave them unless the
user asks to uninstall them.
