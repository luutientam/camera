package com.xperia.leicalook.gl

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.xperia.leicalook.look.LookState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws the camera OES texture through the shared look shader. Rendering is on demand: one draw
 * per camera frame (onFrameAvailable -> requestRender), so the GPU idles between frames.
 */
class PreviewRenderer(
    private val view: GLSurfaceView,
    private val onSurfaceReady: (SurfaceTexture) -> Unit,
) : GLSurfaceView.Renderer {

    private var oesTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var program: LookProgram? = null
    private val texMatrix = FloatArray(16)
    private val mvp = FloatArray(16)
    private var viewW = 1
    private var viewH = 1
    @Volatile var mirror = false
    /** Full-resolution size of the saved photo (portrait), so preview grain/vignette match the still. */
    @Volatile var grainRes = floatArrayOf(3024f, 4032f)
    /** One source texel in camera-texture uv space, for the clarity kernel. */
    @Volatile var texel = floatArrayOf(1f / 1440f, 1f / 1080f)
    /** Disables the look entirely (A/B compare while the button is held). */
    @Volatile var bypass = false

    val textures = LookTextures()
    @Volatile var state: LookState = LookState()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        oesTex = Gl.genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        program = LookProgram(LookProgram.Input.OES)
        textures.onContextCreated()
        val st = SurfaceTexture(oesTex)
        st.setOnFrameAvailableListener { view.requestRender() }
        surfaceTexture = st
        onSurfaceReady(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width; viewH = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        textures.sync(state)
        Matrix.setIdentityM(mvp, 0)
        if (mirror) Matrix.scaleM(mvp, 0, -1f, 1f, 1f)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        program!!.draw(
            inputs = intArrayOf(oesTex), texMatrix = texMatrix, mvp = mvp,
            textures = textures, state = if (bypass) LookState.BYPASS else state,
            outW = viewW, outH = viewH, grainRes = grainRes, texel = texel,
        )
    }

    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
    }
}
