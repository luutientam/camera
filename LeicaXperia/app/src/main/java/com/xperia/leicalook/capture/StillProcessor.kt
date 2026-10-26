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
import com.xperia.leicalook.look.LookState
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Full-resolution still pipeline on its own GL context:
 *   YUV_420_888 planes -> (same look shader as preview) -> rotate/crop -> RGBA readback
 *   -> optional neural refinement -> JPEG + EXIF -> MediaStore.
 * The camera Image is copied and closed immediately so burst shooting is not blocked.
 */
class StillProcessor(
    private val context: Context,
    lutSource: LookTextures.LutSource,
    private val listener: Listener,
) {
    interface Listener {
        fun onSaved(uri: Uri, thumb: Bitmap, millis: Long)
        fun onFailed(message: String)
        fun onQueueChanged(pending: Int)
    }

    /** Post-GPU refinement step (neural style); returns the bitmap to save. */
    fun interface Refiner {
        fun refine(bitmap: Bitmap, result: CaptureResult): Bitmap
    }

    class Job(
        val y: ByteBuffer, val uv: ByteBuffer, val width: Int, val height: Int,
        val rotation: Int, val mirror: Boolean, val aspect: Float,
        val state: LookState, val lookName: String, val result: CaptureResult,
        val refiner: Refiner?, val location: android.location.Location?,
    )

    private val thread = HandlerThread("still").apply { start() }
    private val handler = Handler(thread.looper)
    private var egl: EglOffscreen? = null
    private var program: LookProgram? = null
    private val textures = LookTextures().apply { source = lutSource }
    private var yTex = 0
    private var uvTex = 0
    private var fbo = 0
    private var fboTex = 0
    private var fboW = 0
    private var fboH = 0
    private var readBuf: ByteBuffer? = null
    @Volatile private var pending = 0

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
        image: Image, rotation: Int, mirror: Boolean, aspect: Float, state: LookState,
        lookName: String, result: CaptureResult, refiner: Refiner?, location: android.location.Location?,
    ) {
        val w = image.width; val h = image.height
        val y = ByteBuffer.allocateDirect(w * h)
        val uv = ByteBuffer.allocateDirect(w * h / 2)
        try {
            copyPlanes(image, y, uv)
        } finally {
            image.close()
        }
        pending++
        listener.onQueueChanged(pending)
        val job = Job(y, uv, w, h, rotation, mirror, aspect, state, lookName, result, refiner, location)
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
        try {
            egl!!.makeCurrent()
            val bmp = render(job)
            val refined = job.refiner?.let {
                try { it.refine(bmp, job.result) } catch (e: Throwable) { Log.e(TAG, "refine failed", e); bmp }
            } ?: bmp
            val uri = PhotoSaver.save(context, refined, PhotoSaver.Meta(job.result, job.lookName, location = job.location))
            val thumbW = 240
            val thumb = Bitmap.createScaledBitmap(refined, thumbW, thumbW * refined.height / refined.width, true)
            if (refined !== bmp) bmp.recycle()
            refined.recycle()
            listener.onSaved(uri, thumb, System.currentTimeMillis() - t0)
        } catch (e: Throwable) {
            Log.e(TAG, "still failed", e)
            listener.onFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            pending--
            listener.onQueueChanged(pending)
        }
    }

    private fun render(job: Job): Bitmap {
        val w = job.width; val h = job.height
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, yTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, w, h, 0, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, job.y)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, uvTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RG8, w / 2, h / 2, 0, GLES30.GL_RG, GLES30.GL_UNSIGNED_BYTE, job.uv)
        Gl.check("upload yuv")

        // Output geometry: rotate to upright, then centre-crop to the requested aspect (long/short).
        val rot = ((job.rotation % 360) + 360) % 360
        val rw = if (rot % 180 == 0) w else h
        val rh = if (rot % 180 == 0) h else w
        val long = maxOf(rw, rh); val short = minOf(rw, rh)
        val wantLong = minOf(long, (short * job.aspect).toInt())
        val outW = if (rw >= rh) wantLong else short
        val outH = if (rw >= rh) short else wantLong
        val cu = (1f - outW.toFloat() / rw) / 2f
        val cv = (1f - outH.toFloat() / rh) / 2f
        val state = job.state.copy(crop = floatArrayOf(cu, cv, 1f - cu, 1f - cv))

        ensureFbo(outW, outH)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, outW, outH)
        textures.sync(state)
        val mvp = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        program!!.draw(
            intArrayOf(yTex, uvTex), rotationMatrix(rot, job.mirror), mvp, textures, state,
            outW, outH, floatArrayOf(outW.toFloat(), outH.toFloat()), floatArrayOf(1f / w, 1f / h),
        )
        val buf = readBuf?.takeIf { it.capacity() >= outW * outH * 4 }
            ?: ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder()).also { readBuf = it }
        buf.clear()
        GLES30.glReadPixels(0, 0, outW, outH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buf)
        Gl.check("readPixels")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        buf.position(0)
        val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(buf)
        return bmp
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

    private fun ensureFbo(w: Int, h: Int) {
        if (fbo != 0 && fboW == w && fboH == h) return
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            Gl.deleteTexture(fboTex)
        }
        fboTex = Gl.genTexture(GLES30.GL_TEXTURE_2D)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTex, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "fbo incomplete" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        fboW = w; fboH = h
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
