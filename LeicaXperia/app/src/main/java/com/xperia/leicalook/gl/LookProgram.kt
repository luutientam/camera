package com.xperia.leicalook.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.xperia.leicalook.look.LookState

/**
 * The single colour pipeline shared by preview (OES camera texture) and stills (YUV planes), so
 * what the viewfinder shows is exactly what gets saved. All maths on gamma-encoded sRGB, as in the
 * port (nothing is linearised there either).
 *
 * grade():  M9 distill affine maps -> Leica bank LUT -> warm(density EV, neutral warmth)
 *           -> M9+ boost matrix -> L-M9 class curves -> cameralook tone -> M3 mono curve
 * then:     mix(source, graded, amount) -> cameralook film | Madrid vignette+grain | M3 grain
 */
class LookProgram(private val input: Input) {

    enum class Input { OES, YUV }

    private val program: Int = Gl.program(VS, fragment(input))
    private val loc = HashMap<String, Int>()
    private val curveBuf = FloatArray(8)
    private val muBuf = FloatArray(9)

    private fun u(name: String) = loc.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    /**
     * @param flipY    true when drawing to the screen (GL origin bottom-left); false for still FBOs
     *                 whose readPixels row 0 is the top of the saved photo.
     * @param grainRes pixel size of the (cropped) output photo; grain/vignette are defined in it.
     * @param texel    one source texel in source-uv units (clarity kernel).
     */
    fun draw(
        inputs: IntArray, texMatrix: FloatArray, mvp: FloatArray,
        textures: LookTextures, state: LookState, flipY: Boolean, grainRes: FloatArray, texel: FloatArray,
        frameAspect: Float,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(u("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform1f(u("uFlipY"), if (flipY) 1f else 0f)

        // Create the fallback textures BEFORE binding inputs: creation binds on the active unit and would
        // otherwise replace the input texture bound there (first draw of each context lost the UV plane).
        val id3 = textures.identityTex()
        val id2 = textures.zeroTex2D()
        var unit = 0
        fun bind(target: Int, tex: Int, name: String) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            GLES30.glBindTexture(target, tex)
            GLES30.glUniform1i(u(name), unit)
            unit++
        }
        if (input == Input.OES) {
            bind(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inputs[0], "uCam")
        } else {
            bind(GLES30.GL_TEXTURE_2D, inputs[0], "uY")
            bind(GLES30.GL_TEXTURE_2D, inputs[1], "uUV")
        }
        val bankOn = state.bankKey != null && textures.bankTex != 0
        val lutOn = state.lutAsset != null && textures.lutTex != 0
        val styleOn = state.style && textures.styleReady
        bind(GLES30.GL_TEXTURE_3D, if (bankOn) textures.bankTex else id3, "uBank")
        bind(GLES30.GL_TEXTURE_3D, if (lutOn) textures.lutTex else id3, "uLook")
        for (k in 0 until 3) bind(GLES30.GL_TEXTURE_2D, if (styleOn) textures.styleTex[k] else id2, "uStyleA$k")
        bind(GLES30.GL_TEXTURE_2D, if (styleOn) textures.styleTex[3] else id2, "uStyleR")

        GLES30.glUniform1f(u("uAmount"), state.amount)
        GLES30.glUniform4f(u("uOn"), if (styleOn) 1f else 0f, if (bankOn) 1f else 0f, if (lutOn) 1f else 0f, if (state.m3) 1f else 0f)
        GLES30.glUniform1f(u("uBankN"), textures.bankSize)
        GLES30.glUniform1i(u("uLookN"), if (lutOn) textures.lutSize.toInt() else 2)
        val sh = state.shade
        GLES30.glUniform1f(u("uShadeOn"), if (bankOn && sh != null) 1f else 0f)
        if (sh != null) {
            GLES30.glUniform4f(u("uShade0"), sh[0], sh[1], sh[2], sh[3])
            GLES30.glUniform4f(u("uShade1"), sh[4], sh[5], sh[6], sh[7])
        }
        GLES30.glUniform1f(u("uFrameAspect"), frameAspect)
        GLES30.glUniform2f(u("uWarm"), state.warmEv, state.warmNeutral)
        GLES30.glUniform3f(u("uBoost"), state.boostSat, state.boostContrast, state.boostWarm)
        val mu = state.lm9Mu
        GLES30.glUniform1f(u("uLm9"), if (mu != null) 1f else 0f)
        if (mu != null) { System.arraycopy(mu, 0, muBuf, 0, 9); GLES30.glUniform3fv(u("uMu"), 3, muBuf, 0) }

        val t = state.tone
        GLES30.glUniform1f(u("uStrength"), t.strength)
        setCurve("uRgb", "uRgbN", t.rgb)
        setCurve("uLuma", "uLumaN", t.luma)
        val film = if (lutOn && t.hasFilm) state.amount else 0f
        GLES30.glUniform1f(u("uFilm"), film)
        GLES30.glUniform4f(u("uGrain"), t.grain * film, t.shadows, t.midtones, t.highlights)
        GLES30.glUniform3f(u("uGrainShape"), t.grainSize, t.roughness, t.colorVariation)
        GLES30.glUniform3f(u("uVig"), t.vignette * film, t.midpoint, t.clarity * film)
        GLES30.glUniform1i(u("uSeed"), t.seed)
        GLES30.glUniform2f(u("uMadrid"), state.madridGrain, state.madridVignette)
        GLES30.glUniform2f(u("uGrainRes"), grainRes[0], grainRes[1])
        GLES30.glUniform2f(u("uTexel"), texel[0], texel[1])
        GLES30.glUniform4f(u("uCrop"), state.crop[0], state.crop[1], state.crop[2], state.crop[3])

        Gl.QUAD.position(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 16, Gl.QUAD)
        GLES30.glEnableVertexAttribArray(0)
        Gl.QUAD.position(2)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 16, Gl.QUAD)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setCurve(name: String, countName: String, pts: FloatArray) {
        val n = minOf(4, pts.size / 2)
        java.util.Arrays.fill(curveBuf, 0f)
        System.arraycopy(pts, 0, curveBuf, 0, n * 2)
        GLES30.glUniform2fv(u(name), 4, curveBuf, 0)
        GLES30.glUniform1i(u(countName), n)
    }

    fun release() = GLES30.glDeleteProgram(program)

    companion object {
        private const val VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uTexMatrix;
uniform mat4 uMvp;
uniform vec4 uCrop;
uniform float uFlipY;
out vec2 vUv;
out vec2 vScreen;   // output-photo uv, top-left origin
out vec2 vFrame;    // uncropped upright frame uv, top-left origin (neural maps live here)
void main() {
    vScreen = vec2(aUv.x, mix(aUv.y, 1.0 - aUv.y, uFlipY));
    vFrame = mix(uCrop.xy, uCrop.zw, vScreen);
    vec2 uv = mix(uCrop.xy, uCrop.zw, aUv);
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = uMvp * vec4(aPos, 0.0, 1.0);
}
"""

        private fun fragment(input: Input): String {
            val head = if (input == Input.OES) """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
precision highp int;
uniform samplerExternalOES uCam;
vec3 source(vec2 uv) { return texture(uCam, uv).rgb; }
""" else """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uY;
uniform sampler2D uUV;
// Camera2 YUV_420_888 from the ISP is full-range BT.601 (JFIF), the same matrix its JPEG encoder uses.
vec3 source(vec2 uv) {
    float y = texture(uY, uv).r;
    vec2 c = texture(uUV, uv).rg - 0.5;
    return clamp(vec3(y + 1.402 * c.y, y - 0.344136 * c.x - 0.714136 * c.y, y + 1.772 * c.x), 0.0, 1.0);
}
"""
            return head + FS_BODY
        }

        private const val FS_BODY = """
precision highp sampler3D;
in vec2 vUv;
in vec2 vScreen;
in vec2 vFrame;
out vec4 outColor;

uniform float uAmount;
uniform vec4 uOn;            // style, bank, tone, m3
uniform sampler2D uStyleA0; uniform sampler2D uStyleA1; uniform sampler2D uStyleA2; uniform sampler2D uStyleR;
uniform sampler3D uBank;  uniform float uBankN;
uniform float uShadeOn; uniform vec4 uShade0; uniform vec4 uShade1; uniform float uFrameAspect;
uniform vec2 uWarm;          // density EV, neutral warmth
uniform vec3 uBoost;         // saturation, contrast, warm
uniform float uLm9; uniform vec3 uMu[3];
uniform sampler3D uLook;  uniform int uLookN;
uniform float uStrength;
uniform vec2 uRgb[4];  uniform int uRgbN;
uniform vec2 uLuma[4]; uniform int uLumaN;
uniform float uFilm;
uniform vec4 uGrain;         // grain, shadows, midtones, highlights
uniform vec3 uGrainShape;    // size, roughness, colour variation
uniform vec3 uVig;           // vignette (1/100 EV), midpoint, clarity
uniform int uSeed;
uniform vec2 uMadrid;        // grain, vignette
uniform vec2 uGrainRes;
uniform vec2 uTexel;

const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);

// ---- M9 distill: per-pixel affine from bilinearly sampled coefficient maps (HDRNet) ----
vec3 m9style(vec3 c) {
    vec4 a0 = texture(uStyleA0, vFrame);
    vec4 a1 = texture(uStyleA1, vFrame);
    vec4 a2 = texture(uStyleA2, vFrame);
    vec3 r = clamp(texture(uStyleR, vFrame).rgb, -16.0, 16.0);
    // coeff[0..8] = A row-major, coeff[9..11] = b
    float A00 = a0.x, A01 = a0.y, A02 = a0.z, A10 = a0.w;
    float A11 = a1.x, A12 = a1.y, A20 = a1.z, A21 = a1.w;
    float A22 = a2.x; vec3 b = a2.yzw;
    vec3 o = vec3(A00 * c.r + A01 * c.g + A02 * c.b,
                  A10 * c.r + A11 * c.g + A12 * c.b,
                  A20 * c.r + A21 * c.g + A22 * c.b) + b + r;
    return clamp(o, 0.0, 1.0);
}

vec3 lutTri(vec3 c) {
    return texture(uBank, clamp(c, 0.0, 1.0) * ((uBankN - 1.0) / uBankN) + 0.5 / uBankN).rgb;
}

// ---- NativeM9.applyFilter shading: radial vignette modulated by a luma sigmoid (spec_fullm9 5.4) ----
vec3 shade(vec3 c) {
    float u = (vFrame.x - 0.5) * uFrameAspect, v = vFrame.y - 0.5;
    float n = sin((u - 0.5) * 12.9898 + (v - 0.5) * 78.233) * 43758.547; n -= floor(n);
    float rr = sqrt(u * u + v * v) + n * 0.04 * uShade0.z;
    float t = clamp((rr - uShade0.x) / (uShade0.y - uShade0.x), 0.0, 1.0); float sm = t * t * (3.0 - 2.0 * t);
    float yA = 0.499 * c.r + 0.387 * c.g + 0.114 * c.b;
    float f = uShade1.w + uShade1.z / (1.0 + exp(uShade1.x * (uShade1.y - yA)));
    float gain = uShade0.w * (f + sm * (1.0 - f));
    float Y = dot(c, vec3(0.299, 0.587, 0.114));
    return clamp(c + Y * (gain - 1.0), 0.0, 1.0);
}

// ---- "warm original filter" (native in the port; tuned approximation, spec_donor120 5.4) ----
vec3 warm(vec3 c) {
    float mx = max(c.r, max(c.g, c.b)), mn = min(c.r, min(c.g, c.b));
    float sat = mx > 1e-4 ? (mx - mn) / mx : 0.0;
    c *= mix(1.0, exp2(uWarm.x * 0.25), sat);
    float Y = dot(c, W709);
    float w = uWarm.y * 0.03 * (1.0 - sat) * 4.0 * Y * (1.0 - Y);
    return clamp(c + vec3(w, 0.0, -w), 0.0, 1.0);
}

// ---- M9+ boostSaturationContrast (Android ColorMatrix: saturation -> warm scale -> contrast) ----
vec3 boost(vec3 c) {
    float s = uBoost.x;
    vec3 lum = vec3(0.213, 0.715, 0.072) * (1.0 - s);
    float l = dot(lum, c);
    c = vec3(l) + s * c;
    c *= vec3(1.0 + uBoost.z, 1.0, 1.0 - uBoost.z);
    c = uBoost.y * c + (1.0 - uBoost.y) * (128.0 / 255.0);
    return clamp(c, 0.0, 1.0);
}

// ---- L-M9 Lm9Stats: analytic soft segmentation + per-class affine curves ----
const vec3 LM9_M[3] = vec3[3](vec3(0.919, 0.953, 0.959), vec3(0.70, 0.74, 0.70), vec3(0.72, 0.74, 0.76));
const vec3 LM9_K[3] = vec3[3](vec3(1.062, 1.028, 1.0), vec3(0.87, 0.953, 0.858), vec3(0.892, 0.859, 0.864));
vec3 lm9curve(int k, vec3 x) { return clamp((x - uMu[k]) * LM9_K[k] + uMu[k] * LM9_M[k], 0.0, 1.0); }
vec3 lm9(vec3 c) {
    float Y = dot(c, W709);
    float blue = clamp((c.b - c.g - 0.024) / 0.078, 0.0, 1.0);
    float veg0 = clamp((c.g - max(c.r, c.b) - 0.016) / 0.063, 0.0, 1.0);
    float wrm = clamp((c.r - c.b - 0.02) / 0.06, 0.0, 1.0) * clamp((Y - 0.45) / 0.15, 0.0, 1.0);
    float sky = max(blue * clamp((Y - 0.30) / 0.15, 0.0, 1.0), wrm);
    float hl = clamp((Y - 0.80) / 0.15, 0.0, 1.0);
    sky = sky * (1.0 - hl) + hl;
    float veg = veg0 * (1.0 - sky);
    float oth = max(0.0, (1.0 - sky) - veg);
    float s = max(sky + veg + oth, 1e-5);
    return (lm9curve(0, c) * sky + lm9curve(1, c) * veg + lm9curve(2, c) * oth) / s;
}

// ---- cameralook ToneMath.toned (exact tetrahedral LUT, curves, intensify) ----
vec3 L(ivec3 p) { return texelFetch(uLook, p, 0).rgb; }
vec3 lutTetra(vec3 c) {
    vec3 f = clamp(c, 0.0, 1.0) * float(uLookN - 1);
    ivec3 i0 = min(ivec3(f), ivec3(uLookN - 2));
    vec3 d = f - vec3(i0);
    ivec3 e1; ivec3 e2; vec3 w;
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
float curveRgb(float x) {
    x = clamp(x, 0.0, 1.0) * 255.0;
    if (x <= uRgb[0].x) return clamp(uRgb[0].y / 255.0, 0.0, 1.0);
    for (int i = 1; i < 4; ++i) {
        if (i >= uRgbN) break;
        if (x <= uRgb[i].x) return clamp((uRgb[i-1].y + (uRgb[i].y - uRgb[i-1].y) * (x - uRgb[i-1].x) / (uRgb[i].x - uRgb[i-1].x)) / 255.0, 0.0, 1.0);
    }
    return clamp(uRgb[uRgbN - 1].y / 255.0, 0.0, 1.0);
}
float curveLuma(float x) {
    x = clamp(x, 0.0, 1.0) * 255.0;
    if (x <= uLuma[0].x) return clamp(uLuma[0].y / 255.0, 0.0, 1.0);
    for (int i = 1; i < 4; ++i) {
        if (i >= uLumaN) break;
        if (x <= uLuma[i].x) return clamp((uLuma[i-1].y + (uLuma[i].y - uLuma[i-1].y) * (x - uLuma[i-1].x) / (uLuma[i].x - uLuma[i-1].x)) / 255.0, 0.0, 1.0);
    }
    return clamp(uLuma[uLumaN - 1].y / 255.0, 0.0, 1.0);
}
float intensify(float a, float b, float s) {
    float k = (clamp(s, 1.0, 1.8) - 1.0) * (b - a);
    float room = (k >= 0.0) ? 1.0 - b : b;
    if (room <= 0.0) return clamp(b, 0.0, 1.0);
    return clamp(b + k * room / (room + abs(k)), 0.0, 1.0);
}
vec3 toned(vec3 src) {
    vec3 m = lutTetra(src);
    m = vec3(curveRgb(m.r), curveRgb(m.g), curveRgb(m.b));
    float Y = dot(m, W709);
    float Yc = curveLuma(Y);
    vec3 v = clamp((Y > 1e-6) ? m * Yc / Y : vec3(Yc), 0.0, 1.0);
    return vec3(intensify(src.r, v.r, uStrength), intensify(src.g, v.g, uStrength), intensify(src.b, v.b, uStrength));
}

// ---- M3 MonoRows tone curve (fade 10..246, S 0.42), on 0..255 ----
float m3curve(float v) {
    float t = clamp((v - 10.0) / 236.0, 0.0, 1.0);
    t += 0.42 * (t * t * (3.0 - 2.0 * t) - t);
    return t * 236.0 + 10.0;
}

vec3 grade(vec3 c) {
    if (uOn.x > 0.5) c = m9style(c);
    if (uOn.y > 0.5) c = lutTri(c);
    if (uShadeOn > 0.5) c = shade(c);
    if (uWarm.x != 0.0 || uWarm.y != 0.0) c = warm(c);
    if (uBoost.x != 1.0 || uBoost.y != 1.0 || uBoost.z != 0.0) c = boost(c);
    if (uLm9 > 0.5) c = lm9(c);
    if (uOn.z > 0.5) c = toned(c);
    if (uOn.w > 0.5) {
        float g = clamp(floor(dot(c * 255.0, vec3(0.54, 0.40, 0.06))), 0.0, 255.0);
        c = vec3(m3curve(g) / 255.0);
    }
    return c;
}

// ---- noise ----
float jhash(int a, int b, int c) {
    uint h = uint(a) * 0x1f123bb5u + uint(b) * 0x5f356495u + uint(c) * 0x6c8e9cf5u;
    h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u;
    return float(h & 0xFFFFFFu) / 8388607.5 - 1.0;
}
float vnoise(ivec2 p, int seed, float size, float rough) {
    float s = max(0.0, size) / 25.0 + 1.0;
    vec2 f = vec2(p) / s; ivec2 i = ivec2(f); f -= vec2(i);
    float v = (jhash(i.x, i.y, seed) * (1.0 - f.x) + jhash(i.x + 1, i.y, seed) * f.x) * (1.0 - f.y)
            + (jhash(i.x, i.y + 1, seed) * (1.0 - f.x) + jhash(i.x + 1, i.y + 1, seed) * f.x) * f.y;
    float r = min(0.7, max(0.0, rough) / 100.0);
    return v * (1.0 - r) + jhash(p.x, p.y, seed + 83) * r;
}
float nrand(vec2 n) { return fract(sin(dot(n, vec2(12.9898, 78.233))) * 43758.5453); }
float smoothDown(float e0, float e1, float x) { float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0); return t * t * (3.0 - 2.0 * t); }

void main() {
    vec3 src = source(vUv);
    vec3 c = mix(src, grade(src), uAmount);
    ivec2 p = ivec2(vScreen * uGrainRes);

    if (uFilm > 0.0) {
        // cameralook ToneMath.film
        float Lc = dot(c, W709);
        float detail = 0.0;
        if (uVig.z < 0.0) {
            float lb = 0.0;
            for (int j = -1; j <= 1; ++j) for (int i = -1; i <= 1; ++i) {
                vec3 s = source(vUv + vec2(float(i), float(j)) * uTexel);
                lb += dot(mix(s, grade(s), uAmount), W709) * float((2 - abs(i)) * (2 - abs(j)));
            }
            detail = (Lc - lb / 16.0) * (clamp(uVig.z, -20.0, 0.0) / 100.0);
        }
        vec2 n = (2.0 * vec2(p) + 1.0 - uGrainRes) / max(uGrainRes, vec2(1.0));
        float d = dot(n, n) * 0.5;
        float mp = clamp(uVig.y / 100.0, 0.0, 0.85);
        float t = clamp((d - mp) / (1.0 - mp), 0.0, 1.0); t = t * t * (3.0 - 2.0 * t);
        float gain = exp2(clamp(uVig.x / 100.0, -1.25, 1.25) * t);
        float band = (Lc < 0.5) ? uGrain.y + (uGrain.z - uGrain.y) * (Lc * 2.0)
                                : uGrain.z + (uGrain.w - uGrain.z) * (Lc * 2.0 - 1.0);
        float amp = clamp(uGrain.x, 0.0, 100.0) / 100.0 * max(0.0, band) / 100.0 * 0.06;
        vec3 o = (c + detail) * gain;
        if (amp > 0.0) {
            o += vnoise(p, uSeed, uGrainShape.x, uGrainShape.y) * amp;
            float cv = min(1.0, max(0.0, uGrainShape.z) / 100.0);
            if (cv > 0.0) o += vec3(vnoise(p, uSeed + 1, uGrainShape.x, uGrainShape.y),
                                    vnoise(p, uSeed + 2, uGrainShape.x, uGrainShape.y),
                                    vnoise(p, uSeed + 3, uGrainShape.x, uGrainShape.y)) * amp * cv;
        }
        c = o;
    }

    if (uMadrid.x > 0.0 || uMadrid.y > 0.0) {
        // MadridShader.apply: luminance-protected vignette + shadow-weighted multiplicative noise
        vec2 q = vec2(p) / uGrainRes;
        float dx = (q.x - 0.5) * 0.75, dy = q.y - 0.5;
        float d = sqrt(dx * dx + dy * dy);
        float P = pow(100.0, clamp(floor(dot(c, vec3(0.2125, 0.7154, 0.0721)) * 255.0 + 0.5), 0.0, 255.0) / 255.0 - 1.0);
        float vig = 1.0 - (1.0 - smoothDown(1.4, 0.2, d)) * uMadrid.y;
        c *= P + (1.0 - P) * vig;
        float amt = clamp((1.0 - dot(c, vec3(0.299, 0.114, 0.587))) * uMadrid.x, 0.0, 1.0);
        if (amt > 0.0) {
            vec2 ij = vec2(p & 255) / 255.0;
            float nz = (nrand(ij + 0.07) + nrand(ij + 0.11) + nrand(ij + 0.13) + nrand(ij + 0.17)
                      + nrand(ij + 0.19) + nrand(ij + 0.23)) / 6.0 * 2.0;
            c += (c * nz - c) * amt;
        }
    }

    if (uOn.w > 0.5) {
        // M3 MonoRows grain: smooth value noise, cell = max(1, W/1400) px, amp 13*(0.3+0.7*4l(1-l)) levels
        float l = c.r;
        float amp = (clamp(4.0 * l * (1.0 - l), 0.0, 1.0) * 0.7 + 0.3) * 13.0 / 255.0 * uAmount;
        float cell = max(1.0, uGrainRes.x / 1400.0);
        vec2 f = vec2(p) / cell; ivec2 i = ivec2(f); f -= vec2(i);
        float g = mix(mix(jhash(i.x, i.y, 977), jhash(i.x + 1, i.y, 977), f.x),
                      mix(jhash(i.x, i.y + 1, 977), jhash(i.x + 1, i.y + 1, 977), f.x), f.y);
        c = vec3(c.r + g * amp);
    }
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
