package com.xperia.leicalook.gl

import android.opengl.GLES11Ext
import android.opengl.GLES30
import com.xperia.leicalook.look.LookState
import com.xperia.leicalook.look.ToneParams

/**
 * The single colour pipeline shared by preview (OES camera texture) and stills (YUV planes), so
 * what the viewfinder shows is exactly what gets saved. All maths on gamma-encoded sRGB, as in the
 * original APK (nothing is linearised there either).
 *
 *   Leica scene bank LUT (M9/M10-R by lux index + CCT, trilinear)
 *   -> baked neural M9 style LUT
 *   -> cameralook tone: tetrahedral LUT -> RGB curve -> luma curve -> intensify(strength)   [ToneMath.toned]
 *   -> mono / contrast (Leica M3)
 *   -> film: clarity -> elliptical EV vignette -> hashed value-noise grain                 [ToneMath.film]
 */
class LookProgram(private val input: Input) {

    enum class Input { OES, YUV }

    private val program: Int = Gl.program(VS, fragment(input))
    private val loc = HashMap<String, Int>()
    private val curveBuf = FloatArray(8)

    private fun u(name: String) = loc.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    /**
     * @param grainRes output resolution the grain is defined in (full-res photo size), so preview grain
     *                 has the same structure as the saved photo.
     */
    fun draw(
        inputs: IntArray, texMatrix: FloatArray, mvp: FloatArray,
        textures: LookTextures, state: LookState, outW: Int, outH: Int, grainRes: FloatArray,
        texel: FloatArray,
    ) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES30.glUniformMatrix4fv(u("uMvp"), 1, false, mvp, 0)

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
        val id = textures.identityTex()
        val bankOn = state.bankAmount > 0f && textures.bankTex != 0
        val lutOn = state.lutAmount > 0f && textures.lutTex != 0
        val styleOn = state.styleAmount > 0f && textures.styleTex != 0
        bind(GLES30.GL_TEXTURE_3D, if (bankOn) textures.bankTex else id, "uBank")
        bind(GLES30.GL_TEXTURE_3D, if (styleOn) textures.styleTex else id, "uStyle")
        bind(GLES30.GL_TEXTURE_3D, if (lutOn) textures.lutTex else id, "uLook")
        GLES30.glUniform3f(u("uAmt"), if (bankOn) state.bankAmount else 0f,
            if (styleOn) state.styleAmount else 0f, if (lutOn) state.lutAmount else 0f)
        GLES30.glUniform2f(u("uSize"), textures.bankSize, textures.styleSize)
        GLES30.glUniform1i(u("uLookN"), if (lutOn) textures.lutSize.toInt() else 2)

        val t = state.tone
        GLES30.glUniform1f(u("uStrength"), t.strength)
        setCurve("uRgb", "uRgbN", t.rgb)
        setCurve("uLuma", "uLumaN", t.luma)
        GLES30.glUniform2f(u("uMono"), state.mono, state.contrast)
        GLES30.glUniform3f(u("uMonoW"), state.monoWeights[0], state.monoWeights[1], state.monoWeights[2])

        val f = if (t.hasFilm) state.filmAmount else 0f
        GLES30.glUniform1f(u("uFilm"), f)
        GLES30.glUniform4f(u("uGrain"), t.grain * f, t.shadows, t.midtones, t.highlights)
        GLES30.glUniform3f(u("uGrainShape"), t.grainSize, t.roughness, t.colorVariation)
        GLES30.glUniform3f(u("uVig"), t.vignette * f, t.midpoint, t.clarity * f)
        GLES30.glUniform1i(u("uSeed"), t.seed)
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
out vec2 vUv;
out vec2 vScreen;
void main() {
    vScreen = vec2(aUv.x, 1.0 - aUv.y);   // top-left origin, like the Java bitmap the film pass expects
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
out vec4 outColor;
uniform sampler3D uBank;
uniform sampler3D uStyle;
uniform sampler3D uLook;
uniform vec3 uAmt;          // bank, style, creative (user slider)
uniform vec2 uSize;         // bank / style grid sizes
uniform int uLookN;
uniform float uStrength;
uniform vec2 uRgb[4];  uniform int uRgbN;
uniform vec2 uLuma[4]; uniform int uLumaN;
uniform vec2 uMono;         // mono amount, contrast
uniform vec3 uMonoW;
uniform float uFilm;
uniform vec4 uGrain;        // grain, shadows, midtones, highlights
uniform vec3 uGrainShape;   // size, roughness, colour variation
uniform vec3 uVig;          // vignette (1/100 EV), midpoint, clarity
uniform int uSeed;
uniform vec2 uGrainRes;
uniform vec2 uTexel;

const vec3 W709 = vec3(0.2126, 0.7152, 0.0722);

vec3 lutTri(sampler3D t, vec3 c, float n) {
    return texture(t, clamp(c, 0.0, 1.0) * ((n - 1.0) / n) + 0.5 / n).rgb;
}

// ToneTable.map: exact tetrahedral interpolation
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

// ToneMath.hash / noise (exact integer hash, value noise on a grainSize lattice)
float jhash(int a, int b, int c) {
    uint h = uint(a) * 0x1f123bb5u + uint(b) * 0x5f356495u + uint(c) * 0x6c8e9cf5u;
    h ^= h >> 16u; h *= 0x7feb352du; h ^= h >> 15u; h *= 0x846ca68bu; h ^= h >> 16u;
    return float(h & 0xFFFFFFu) / 8388607.5 - 1.0;
}
float vnoise(ivec2 p, int seed) {
    float s = max(0.0, uGrainShape.x) / 25.0 + 1.0;
    vec2 f = vec2(p) / s; ivec2 i = ivec2(f); f -= vec2(i);
    float v = (jhash(i.x, i.y, seed) * (1.0 - f.x) + jhash(i.x + 1, i.y, seed) * f.x) * (1.0 - f.y)
            + (jhash(i.x, i.y + 1, seed) * (1.0 - f.x) + jhash(i.x + 1, i.y + 1, seed) * f.x) * f.y;
    float r = min(0.7, max(0.0, uGrainShape.y) / 100.0);
    return v * (1.0 - r) + jhash(p.x, p.y, seed + 83) * r;
}

vec3 grade(vec3 c) {
    if (uAmt.x > 0.0) c = mix(c, lutTri(uBank, c, uSize.x), uAmt.x);
    if (uAmt.y > 0.0) c = mix(c, lutTri(uStyle, c, uSize.y), uAmt.y);
    if (uAmt.z > 0.0) c = mix(c, toned(c), uAmt.z);
    if (uMono.y != 0.0) c = mix(c, c * c * (3.0 - 2.0 * c), uMono.y);
    if (uMono.x > 0.0) c = mix(c, vec3(dot(clamp(c, 0.0, 1.0), uMonoW)), uMono.x);
    return clamp(c, 0.0, 1.0);
}

void main() {
    vec3 c = grade(source(vUv));
    if (uFilm > 0.0) {
        float Lc = dot(c, W709);
        float detail = 0.0;
        if (uVig.z < 0.0) {
            // clarity against a 3x3 [1 2 1] luma blur (source-space neighbours, graded)
            float lb = 0.0;
            for (int j = -1; j <= 1; ++j) for (int i = -1; i <= 1; ++i)
                lb += dot(grade(source(vUv + vec2(float(i), float(j)) * uTexel)), W709) * float((2 - abs(i)) * (2 - abs(j)));
            lb /= 16.0;
            detail = (Lc - lb) * (clamp(uVig.z, -20.0, 0.0) / 100.0);
        }
        ivec2 p = ivec2(vScreen * uGrainRes);
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
            float mono = vnoise(p, uSeed) * amp;
            float cv = min(1.0, max(0.0, uGrainShape.z) / 100.0);
            vec3 col = cv > 0.0 ? vec3(vnoise(p, uSeed + 1), vnoise(p, uSeed + 2), vnoise(p, uSeed + 3)) * amp * cv : vec3(0.0);
            o += mono + col;
        }
        c = o;
    }
    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
    }
}
