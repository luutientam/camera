package com.xperia.leicalook.capture

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.media.Image
import android.net.Uri
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.xperia.leicalook.gl.EglOffscreen
import com.xperia.leicalook.gl.Gl
import com.xperia.leicalook.gl.LookProgram
import com.xperia.leicalook.gl.LookTextures
import com.xperia.leicalook.look.LookEngine
import com.xperia.leicalook.look.LookState
import com.xperia.leicalook.neural.M9Style
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Full-resolution still pipeline on its own GL context:
 *   YUV_420_888 planes -> [half-res upright pass -> M9 encoder maps, L-M9 probe]
 *   -> full-res look pass (same shader as preview) with rotate/crop -> RGBA readback
 *   -> NightHighlights shoulder (cameralook tones) -> JPEG + EXIF -> MediaStore.
 * The camera Image is copied and closed immediately so burst shooting is not blocked.
 */
class StillProcessor(
    private val context: Context,
    private val engine: LookEngine,
    private val m9: () -> M9Style?,
    private val listener: Listener,
) {
    interface Listener {
        fun onSaved(uri: Uri, thumb: Bitmap, millis: Long)
        fun onFailed(message: String)
        fun onQueueChanged(pending: Int)
    }

    class Job(
        val y: ByteBuffer, val uv: ByteBuffer, val width: Int, val height: Int,
        val rotation: Int, val mirror: Boolean, val aspect: Float,
        val state: LookState, val lm9: Boolean, val nightShoulder: Boolean,
        val lookName: String, val result: CaptureResult, val location: android.location.Location?,
    )

    private val thread = HandlerThread("still").apply { start() }
    private val handler = Handler(thread.looper)
    private var egl: EglOffscreen? = null
    private var program: LookProgram? = null
    private val textures = LookTextures().apply { source = engine }
    private var yTex = 0
    private var uvTex = 0
    private val fbos = HashMap<Long, IntArray>()
    private val pending = java.util.concurrent.atomic.AtomicInteger(0)

    init {
        handler.post {
            egl = EglOffscreen()
            program = LookProgram(LookProgram.Input.YUV)
            textures.onContextCreated()
            yTex = Gl.genTexture(GLES30.GL_TEXTURE_2D)
            uvTex = Gl.genTexture(GLES30.GL_TEXTURE_2D)
        }
    }

    /** Copies the planes out of [image] and closes it; processing continues asynchronously. */
    fun submit(
        image: Image, rotation: Int, mirror: Boolean, aspect: Float, state: LookState, lm9: Boolean,
        nightShoulder: Boolean, lookName: String, result: CaptureResult, location: android.location.Location?,
    ) {
        val w = image.width; val h = image.height
        val y = ByteBuffer.allocateDirect(w * h)
        val uv = ByteBuffer.allocateDirect(w * h / 2)
        try {
            copyPlanes(image, y, uv)
        } finally {
            image.close()
        }
        listener.onQueueChanged(pending.incrementAndGet())
        val job = Job(y, uv, w, h, rotation, mirror, aspect, state, lm9, nightShoulder, lookName, result, location)
        handler.post { run(job) }
    }

    private fun copyPlanes(img: Image, yOut: ByteBuffer, uvOut: ByteBuffer) {
        val w = img.width; val h = img.height
        val pY = img.planes[0]; val pU = img.planes[1]; val pV = img.planes[2]
        val yb = pY.buffer; val rs = pY.rowStride
        if (rs == w) {
            yb.limit(w * h); yOut.put(yb)
        } else {
            val row = ByteArray(w)
            for (r in 0 until h) { yb.position(r * rs); yb.get(row); yOut.put(row) }
        }
        yOut.flip()
        val cw = w / 2; val ch = h / 2
        val ub = pU.buffer; val vb = pV.buffer
        val ups = pU.pixelStride; val urs = pU.rowStride; val vps = pV.pixelStride; val vrs = pV.rowStride
        val out = ByteArray(cw * 2)
        val uRow = ByteArray(urs); val vRow = ByteArray(vrs)
        for (r in 0 until ch) {
            val ul = minOf(urs, ub.capacity() - r * urs); val vl = minOf(vrs, vb.capacity() - r * vrs)
            ub.position(r * urs); ub.get(uRow, 0, ul)
            vb.position(r * vrs); vb.get(vRow, 0, vl)
            var o = 0
            for (c in 0 until cw) {
                out[o++] = uRow[c * ups]
                out[o++] = vRow[c * vps]
            }
            uvOut.put(out)
        }
        uvOut.flip()
    }

    private fun run(job: Job) {
        val t0 = System.currentTimeMillis()
        var tl = t0
        val laps = StringBuilder()
        fun lap(name: String) { val n = System.currentTimeMillis(); laps.append(name).append(' ').append(n - tl).append("ms "); tl = n }
        var styleVersion = 0
        try {
            egl!!.makeCurrent()
            uploadYuv(job)
            lap("upload")
            val rot = ((job.rotation % 360) + 360) % 360
            val tex = rotationMatrix(rot, job.mirror)
            val rw = if (rot % 180 == 0) job.width else job.height
            val rh = if (rot % 180 == 0) job.height else job.width
            var state = job.state

            // Pass 1: un-graded upright frame at half resolution (GL_LINEAR at exactly 2x = the donor's 2x2 box).
            if (state.style || job.lm9) {
                val hw = rw / 2; val hh = rh / 2
                val half = renderToBuffer(tex, LookState.BYPASS, hw, hh, rw.toFloat() / rh)
                lap("half")
                if (state.style) {
                    val maps = m9()?.maps(half, hw, hh, rw, rh, temporal = false)
                    lap("m9 maps")
                    if (maps != null) {
                        styleVersion = engine.publishStillStyle(maps)
                        state = state.copy(styleVersion = styleVersion)
                    } else state = state.copy(style = false)
                }
                if (job.lm9) {
                    val pw = 320; val ph = maxOf(1, Math.round(rh * 320f / rw))
                    val probe = renderToBuffer(tex, LookState.BYPASS, pw, ph, rw.toFloat() / rh)
                    state = state.copy(lm9Mu = LookEngine.lm9Means(probe, pw, ph))
                }
            }

            // Pass 2: full resolution, rotated upright, centre-cropped to the requested aspect.
            val long = maxOf(rw, rh); val short = minOf(rw, rh)
            val wantLong = minOf(long, (short * job.aspect).toInt())
            val outW = if (rw >= rh) wantLong else short
            val outH = if (rw >= rh) short else wantLong
            val cu = (1f - outW.toFloat() / rw) / 2f
            val cv = (1f - outH.toFloat() / rh) / 2f
            state = state.copy(crop = floatArrayOf(cu, cv, 1f - cu, 1f - cv))
            val buf = renderToBuffer(tex, state, outW, outH, rw.toFloat() / rh)
            lap("full render")
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(buf)
            if (job.nightShoulder) NightHighlights.apply(bmp)
            lap("bitmap")

            val uri = PhotoSaver.save(context, bmp, PhotoSaver.Meta(job.result, job.lookName, location = job.location))
            lap("save")
            Log.i(TAG, "${job.lookName} ${outW}x$outH: $laps")
            val thumbW = 240
            val thumb = Bitmap.createScaledBitmap(bmp, thumbW, thumbW * bmp.height / bmp.width, true)
            bmp.recycle()
            listener.onSaved(uri, thumb, System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            Log.e(TAG, "still failed", e)
            listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (styleVersion != 0) engine.release(styleVersion)
            if (job.state.bankKey != null) engine.release(job.state.bankVersion)
            listener.onQueueChanged(pending.decrementAndGet())
        }
    }

    private fun uploadYuv(job: Job) {
        val w = job.width; val h = job.height
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, job.y)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uvTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, w / 2, h / 2, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, job.uv)
        Gl.check("upload yuv")
        texel[0] = 1f / w; texel[1] = 1f / h
    }

    private val texel = FloatArray(2)
    private val mvp = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }

    /** Renders [state] into an RGBA8 buffer of w x h whose row 0 is the top of the image. */
    private fun renderToBuffer(tex: FloatArray, state: LookState, w: Int, h: Int, frameAspect: Float): ByteBuffer {
        val fbo = fbo(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, w, h)
        textures.sync(state)
        program!!.draw(intArrayOf(yTex, uvTex), tex, mvp, textures, state, false,
            floatArrayOf(w.toFloat(), h.toFloat()), texel, frameAspect)
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        Gl.check("readPixels")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        buf.position(0)
        return buf
    }

    private fun fbo(w: Int, h: Int): Int {
        val key = (w.toLong() shl 32) or h.toLong()
        fbos[key]?.let { return it[0] }
        if (fbos.size > 4) {
            fbos.values.forEach { GLES30.glDeleteFramebuffers(1, intArrayOf(it[0]), 0); Gl.deleteTexture(it[1]) }
            fbos.clear()
        }
        val t = Gl.genTexture(GLES30.GL_TEXTURE_2D)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, ids[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "fbo incomplete" }
        fbos[key] = intArrayOf(ids[0], t)
        return ids[0]
    }

    /**
     * Output uv (row 0 = top of the saved photo; readPixels row 0 is the first row we draw, so no
     * vertical flip is needed) -> source uv, for a clockwise rotation of [rot] degrees.
     */
    private fun rotationMatrix(rot: Int, mirror: Boolean): FloatArray {
        // (u, v) -> (a*u + b*v + c, d*u + e*v + f)
        var a = 1f; var b = 0f; var c = 0f; var d = 0f; var e = 1f; var f = 0f
        when (rot) {
            90 -> { a = 0f; b = 1f; c = 0f; d = -1f; e = 0f; f = 1f }
            180 -> { a = -1f; b = 0f; c = 1f; d = 0f; e = -1f; f = 1f }
            270 -> { a = 0f; b = -1f; c = 1f; d = 1f; e = 0f; f = 0f }
        }
        if (mirror) { c += a; a = -a; f += d; d = -d }
        return floatArrayOf(
            a, d, 0f, 0f,
            b, e, 0f, 0f,
            0f, 0f, 1f, 0f,
            c, f, 0f, 1f,
        )
    }

    fun release() {
        handler.post {
            program?.release()
            egl?.release(); egl = null
        }
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "StillProcessor"

        /** Clockwise rotation that makes the sensor image upright for the current device rotation. */
        fun stillRotation(chars: CameraCharacteristics, deviceDegrees: Int): Int {
            val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            return if (front) (sensor - deviceDegrees + 360) % 360 else (sensor + deviceDegrees) % 360
        }
    }
}
