package com.xperia.leicalook.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.util.concurrent.Executor
import kotlin.math.abs

/**
 * Camera2 controller tuned for the Xperia 1 IV layout:
 *  id 0 = logical rear (physical 2 wide / 3 tele / 4 ultra-wide, zoom ratio 0.63..14.85)
 *  id 1 = front.
 * Lens switching is done through CONTROL_ZOOM_RATIO on the logical camera so the HAL hands over
 * between physical sensors seamlessly, instead of reopening the device.
 */
class CameraController(context: Context, private val listener: Listener) {

    interface Listener {
        fun onOpened(info: Info)
        fun onPreviewResult(result: TotalCaptureResult)
        fun onStill(image: Image, result: TotalCaptureResult, info: Info, request: StillRequest)
        fun onError(message: String)
        /** The device or session died; the preview is frozen until the camera is reopened. */
        fun onFatal(cameraId: String, message: String)
        fun onShutter()
    }

    data class Info(
        val id: String,
        val facing: Int,
        val sensorOrientation: Int,
        val previewSize: Size,
        val stillSize: Size,
        val zoomRange: Range<Float>,
        val activeArray: Rect,
        val characteristics: CameraCharacteristics,
        val scene: SceneEstimator,
    )

    /** Carries the UI state at the moment the shutter was pressed. */
    data class StillRequest(val deviceRotation: Int, val zoom: Float, val payload: Any?)

    private val manager = context.getSystemService(CameraManager::class.java)
    private val thread = HandlerThread("camera").apply { start() }
    private val handler = Handler(thread.looper)
    private val executor = Executor { handler.post(it) }

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var stillReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var info: Info? = null
    private var previewBuilder: CaptureRequest.Builder? = null

    @Volatile var zoom = 1f; private set
    private var evIndex = 0
    private var meteringRegion: MeteringRectangle? = null
    private val stillImages = ArrayDeque<Image>()
    private val stillResults = ArrayDeque<Pair<TotalCaptureResult, StillRequest>>()
    private var lastPreviewResult: TotalCaptureResult? = null
    private var stillInFlight = 0

    val maxStillsInFlight = 2

    fun backCameraId(): String = pickCamera(CameraCharacteristics.LENS_FACING_BACK)
    fun frontCameraId(): String = pickCamera(CameraCharacteristics.LENS_FACING_FRONT)

    private fun pickCamera(facing: Int): String {
        var best: String? = null
        var bestScore = -1f
        for (id in manager.cameraIdList) {
            val c = manager.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != facing) continue
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE !in caps) continue
            val zr = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            var score = (zr?.upper ?: 1f) / (zr?.lower ?: 1f)
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps) score += 100f
            if (score > bestScore) { bestScore = score; best = id }
        }
        return best ?: manager.cameraIdList.first()
    }

    @SuppressLint("MissingPermission")
    fun open(cameraId: String, surfaceTexture: SurfaceTexture, targetAspect: Float) {
        handler.post {
            closeInternal()
            try {
                val chars = manager.getCameraCharacteristics(cameraId)
                val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                val stillSize = map.getOutputSizes(ImageFormat.YUV_420_888)
                    .filter { abs(it.width.toFloat() / it.height - targetAspect) < 0.01f }
                    .maxByOrNull { it.width * it.height }
                    ?: map.getOutputSizes(ImageFormat.YUV_420_888).maxBy { it.width * it.height }
                // Preview: 4:3 around 1440p — sharp on the 1644x3840 panel, cheap for 60 fps.
                val previewSize = map.getOutputSizes(SurfaceTexture::class.java)
                    .filter { abs(it.width.toFloat() / it.height - targetAspect) < 0.01f && it.width <= 1920 }
                    .maxByOrNull { it.width * it.height } ?: Size(1440, 1080)
                surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
                val zr = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
                info = Info(
                    cameraId,
                    chars.get(CameraCharacteristics.LENS_FACING)!!,
                    chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90,
                    previewSize, stillSize, zr,
                    chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!,
                    chars, SceneEstimator(chars),
                )
                zoom = zoom.coerceIn(zr.lower, zr.upper)
                previewSurface = Surface(surfaceTexture)
                stillReader = ImageReader.newInstance(stillSize.width, stillSize.height, ImageFormat.YUV_420_888, maxStillsInFlight + 1)
                    .apply {
                        setOnImageAvailableListener({ rd ->
                            rd.acquireNextImage()?.let { stillImages.addLast(it) }
                            drainStills()
                        }, handler)
                    }
                manager.openCamera(cameraId, executor, stateCallback)
            } catch (e: Exception) {
                Log.e(TAG, "open failed", e)
                listener.onError(e.message ?: "open failed")
            }
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            device = camera
            createSession(camera)
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close(); if (device == camera) device = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close(); if (device == camera) device = null
            listener.onFatal(camera.id, "camera error $error")
        }
    }

    private fun createSession(camera: CameraDevice) {
        val reader = stillReader!!
        val outputs = listOf(OutputConfiguration(previewSurface!!), OutputConfiguration(reader.surface))
        val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputs, executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    startPreview()
                    listener.onOpened(info!!)
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    listener.onFatal(camera.id, "session configuration failed")
                }
            })
        camera.createCaptureSession(config)
    }

    private fun startPreview() {
        val d = device ?: return
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        b.addTarget(previewSurface!!)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, continuousAf())
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, previewFpsRange())
        b.setIf(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
        b.setIf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        b.setIf(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST,
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        b.setIf(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        b.setIf(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE,
            CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES)
        applyCommon(b)
        previewBuilder = b
        repeat()
    }

    /**
     * Sets [key] only when the device lists [value] in [available]. The Xperia front camera has no
     * OIS and no AF, and its HAL rejects the whole request (-22) for an unsupported mode.
     */
    private fun CaptureRequest.Builder.setIf(key: CaptureRequest.Key<Int>, value: Int, available: CameraCharacteristics.Key<IntArray>) {
        if (info?.characteristics?.get(available)?.contains(value) == true) set(key, value)
    }

    private fun hasAf(): Boolean =
        info?.characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.contains(CameraMetadata.CONTROL_AF_MODE_AUTO) == true

    private fun continuousAf(): Int {
        val modes = info?.characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: return CameraMetadata.CONTROL_AF_MODE_OFF
        return if (CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE in modes) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        else CameraMetadata.CONTROL_AF_MODE_OFF
    }

    private fun previewFpsRange(): Range<Int> {
        val ranges = info!!.characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(30, 30)
        // Prefer a variable 30..60 range: 60 fps in good light, still lets AE lengthen exposure indoors.
        return ranges.firstOrNull { it.lower == 30 && it.upper == 60 }
            ?: ranges.filter { it.upper >= 30 }.maxWithOrNull(compareBy({ it.upper }, { -it.lower }))
            ?: ranges.last()
    }

    private fun applyCommon(b: CaptureRequest.Builder) {
        b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex)
        val region = meteringRegion
        if (region != null) {
            if (hasAf()) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        }
    }

    private fun repeat() {
        val s = session ?: return
        val b = previewBuilder ?: return
        try {
            s.setRepeatingRequest(b.build(), previewCallback, handler)
        } catch (e: IllegalStateException) {
            // Device closed under us (HAL error): drop the dead session so later calls are no-ops.
            Log.w(TAG, "setRepeatingRequest: ${e.message}")
            session = null
            info?.let { listener.onFatal(it.id, e.message ?: "camera closed") }
        } catch (e: Exception) {
            Log.w(TAG, "setRepeatingRequest", e)
        }
    }

    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            lastPreviewResult = result
            listener.onPreviewResult(result)
        }
    }

    fun setZoom(ratio: Float) {
        handler.post {
            val i = info ?: return@post
            val z = ratio.coerceIn(i.zoomRange.lower, i.zoomRange.upper)
            if (abs(z - zoom) < 0.001f) return@post
            zoom = z
            previewBuilder?.set(CaptureRequest.CONTROL_ZOOM_RATIO, z)
            repeat()
        }
    }

    fun setExposureCompensation(ev: Float) {
        handler.post {
            val c = info?.characteristics ?: return@post
            val step = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: return@post
            val range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: return@post
            val idx = Math.round(ev / step).coerceIn(range.lower, range.upper)
            if (idx == evIndex) return@post
            evIndex = idx
            previewBuilder?.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, idx)
            repeat()
        }
    }

    /**
     * Tap to focus/meter. [nx], [ny] are normalised coordinates in sensor (active array) space after
     * the caller undid the display rotation; the crop of the current zoom ratio is applied here.
     */
    fun focusAt(nx: Float, ny: Float) {
        handler.post {
            val i = info ?: return@post
            val s = session ?: return@post
            val b = previewBuilder ?: return@post
            val aa = i.activeArray
            // With CONTROL_ZOOM_RATIO, regions are expressed in the full active array coordinates
            // of the un-zoomed field of view; map through the centred crop.
            val cw = aa.width() / zoom.coerceAtLeast(1f); val ch = aa.height() / zoom.coerceAtLeast(1f)
            val cx = aa.centerX() - cw / 2 + nx * cw; val cy = aa.centerY() - ch / 2 + ny * ch
            val half = (minOf(aa.width(), aa.height()) * 0.06f).toInt()
            val rect = Rect((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            rect.intersect(aa)
            val region = MeteringRectangle(rect, MeteringRectangle.METERING_WEIGHT_MAX - 1)
            meteringRegion = region
            try {
                applyCommon(b)
                if (!hasAf()) { repeat(); return@post }
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
                s.capture(b.build(), null, handler)
                b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
                repeat()
            } catch (e: Exception) {
                Log.w(TAG, "focusAt", e)
            }
        }
    }

    /** Returns to continuous AF/AE over the whole frame (long press or after recomposition). */
    fun resetFocus() {
        handler.post {
            meteringRegion = null
            val b = previewBuilder ?: return@post
            b.set(CaptureRequest.CONTROL_AF_MODE, continuousAf())
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            repeat()
        }
    }

    fun capture(request: StillRequest): Boolean {
        if (stillInFlight >= maxStillsInFlight) return false
        handler.post { captureInternal(request) }
        return true
    }

    private fun captureInternal(req: StillRequest) {
        val d = device ?: return
        val s = session ?: return
        val reader = stillReader ?: return
        stillInFlight++
        val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        b.addTarget(reader.surface)
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, previewBuilder?.get(CaptureRequest.CONTROL_AF_MODE) ?: continuousAf())
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO)
        b.setIf(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)
        b.setIf(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_HIGH_QUALITY, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)
        b.setIf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)
        b.setIf(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        applyCommon(b)
        val token = req.copy(zoom = zoom)
        try {
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
                    listener.onShutter()
                }

                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    stillResults.addLast(result to token)
                    drainStills()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: android.hardware.camera2.CaptureFailure) {
                    stillInFlight--
                    listener.onError("capture failed (${failure.reason})")
                }
            }, handler)
        } catch (e: Exception) {
            stillInFlight--
            Log.e(TAG, "capture", e)
            listener.onError(e.message ?: "capture failed")
        }
    }

    /** Pairs images with their results in order; both arrive on the camera thread. */
    private fun drainStills() {
        val i = info ?: return
        while (stillImages.isNotEmpty() && stillResults.isNotEmpty()) {
            val img = stillImages.removeFirst()
            val (res, r) = stillResults.removeFirst()
            stillInFlight--
            listener.onStill(img, res, i, r)
        }
    }

    fun lastResult(): CaptureResult? = lastPreviewResult

    fun close() {
        handler.post { closeInternal() }
    }

    private fun closeInternal() {
        try { session?.close() } catch (_: Exception) {}
        session = null
        device?.close(); device = null
        stillReader?.close(); stillReader = null
        previewSurface?.release(); previewSurface = null
        previewBuilder = null
        stillInFlight = 0
        stillImages.forEach { it.close() }; stillImages.clear(); stillResults.clear()
        meteringRegion = null
    }

    fun release() {
        close()
        thread.quitSafely()
    }

    companion object {
        private const val TAG = "CameraController"
    }
}
