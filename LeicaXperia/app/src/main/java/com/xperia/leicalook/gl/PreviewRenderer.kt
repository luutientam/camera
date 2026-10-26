package com.xperia.leicalook.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import com.xperia.leicalook.look.LookState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the camera OES texture through the shared look shader. Rendering is on demand: one draw
 * per camera frame (onFrameAvailable -> requestRender), so the GPU idles between frames.
 *
 * When [grabListener] is set, every [grabIntervalMs] it also renders the un-graded frame into a small
 * FBO and hands an upright RGBA8 copy (row 0 = top) to the listener for the neural encoder / stats.
 */
class PreviewRenderer(
    private val view: GLSurfaceView,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    fun interface GrabListener {
        /** Called on the GL thread; must hand off quickly and call [grabDone] when finished. */
        fun onGrab(rgba: ByteBuffer, w: Int, h: Int)
    }

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program: LookProgram? = null
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    private var viewW = 1
    private var viewH = 1
    @Volatile var mirror = false
    /** Full-resolution size of the saved photo (portrait), so preview grain/vignette match the still. */
    @Volatile var grainRes = floatArrayOf(3024f, 4032f)
    @Volatile var frameAspect = 0.75f
    /** One source texel in camera-texture uv space, for the clarity kernel. */
    @Volatile var texel = floatArrayOf(1f / 1440f, 1f / 1080f)
    /** Disables the look entirely (A/B compare while the button is held). */
    @Volatile var bypass = false

    val textures = LookTextures()
    @Volatile var state: LookState = LookState()

    @Volatile var grabListener: GrabListener? = null
    @Volatile var grabW = 192
    @Volatile var grabH = 256
    var grabIntervalMs = 150L
    @Volatile private var grabBusy = false
    private var lastGrab = 0L
    private var grabFbo = 0
    private var grabTex = 0
    private var grabTexW = 0
    private var grabTexH = 0
    private var grabRead: ByteBuffer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        oesTex = Gl.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        program = LookProgram(LookProgram.Input.OES)
        textures.onContextCreated()
        grabFbo = 0; grabTex = 0
        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener { view.requestRender() }
        surfaceTexture = st
        onSurfaceReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        val s = if (bypass) LookState.BYPASS else state
        textures.sync(s)
        Matrix.setIdentityM(mvp, 0)
        if (mirror) Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        program!!.draw(intArrayOf(oesTex), texMatrix, mvp, textures, s, true, grainRes, texel, frameAspect)

        val l = grabListener
        val now = SystemClock.uptimeMillis()
        if (l != null && !grabBusy && now - lastGrab >= grabIntervalMs) {
            lastGrab = now
            grab(l)
        }
    }

    private fun grab(l: GrabListener) {
        val w = grabW; val h = grabH
        if (grabFbo == 0 || grabTexW != w || grabTexH != h) {
            if (grabFbo != 0) { GLES30.glDeleteFramebuffers(1, intArrayOf(grabFbo), 0); Gl.deleteTexture(grabTex) }
            grabTex = Gl.genTexture(GLES30.GL_TEXTURE_2D)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
            val ids = IntArray(1); GLES30.glGenFramebuffers(1, ids, 0); grabFbo = ids[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, grabFbo)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, grabTex, 0)
            grabTexW = w; grabTexH = h
            grabRead = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, grabFbo)
        GLES30.glViewport(0, 0, w, h)
        program!!.draw(intArrayOf(oesTex), texMatrix, identity, textures, LookState.BYPASS, true, grainRes, texel, frameAspect)
        val rb = grabRead!!
        rb.clear()
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, rb)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        // readPixels row 0 is the bottom of the displayed image: flip to top-first.
        val out = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        val row = ByteArray(w * 4)
        for (y in 0 until h) {
            rb.position((h - 1 - y) * w * 4); rb.get(row); out.put(row)
        }
        out.position(0)
        grabBusy = true
        l.onGrab(out, w, h)
    }

    fun grabDone() { grabBusy = false }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
