package com.xperia.leicalook

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.TotalCaptureResult
import android.location.Location
import android.location.LocationManager
import android.media.Image
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.xperia.leicalook.camera.CameraController
import com.xperia.leicalook.camera.SceneEstimator
import com.xperia.leicalook.capture.StillProcessor
import com.xperia.leicalook.gl.PreviewRenderer
import com.xperia.leicalook.look.Look
import com.xperia.leicalook.look.LookCatalog
import com.xperia.leicalook.look.LookEngine
import com.xperia.leicalook.look.LookState
import com.xperia.leicalook.neural.M9Style
import com.xperia.leicalook.ui.PreviewOverlay
import com.xperia.leicalook.ui.ShutterButton
import com.xperia.leicalook.ui.ThumbView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

class MainActivity : Activity(), CameraController.Listener, StillProcessor.Listener {

    private lateinit var camera: CameraController
    private lateinit var engine: LookEngine
    private lateinit var processor: StillProcessor
    private lateinit var renderer: PreviewRenderer
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: PreviewOverlay
    private lateinit var shutter: ShutterButton
    private lateinit var thumb: ThumbView
    private lateinit var sceneInfo: TextView
    private lateinit var lookName: TextView
    private lateinit var intensity: SeekBar
    private lateinit var zoomBar: LinearLayout
    private lateinit var toast: TextView

    private val main = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }

    private lateinit var looks: List<Look>
    private var look: Look? = null
    private val intensities = HashMap<String, Float>()
    private var info: CameraController.Info? = null
    private var surfaceTexture: SurfaceTexture? = null
    private var useFront = false
    private var deviceDegrees = 0
    private var aspectIndex = 0
    private var aiEnabled = true
    @Volatile private var scene: SceneEstimator.Scene? = null
    private var lastUri: Uri? = null
    private var m9: M9Style? = null

    private val aspects = listOf("4:3" to 4f / 3f, "3:2" to 1.5f, "16:9" to 16f / 9f, "1:1" to 1f)
    // Leica focal lengths mapped onto the Xperia 1 IV's 24 mm main camera.
    private val focalStops = listOf(16, 24, 35, 50, 85, 125)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        engine = LookEngine(assets)
        looks = LookCatalog.all(engine.toneCount())
        camera = CameraController(this, this)
        processor = StillProcessor(this, engine, this)
        bg.execute { m9 = M9Style.create(this, engine) }

        glView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        shutter = findViewById(R.id.shutter)
        thumb = findViewById(R.id.thumb)
        sceneInfo = findViewById(R.id.sceneInfo)
        lookName = findViewById(R.id.lookName)
        intensity = findViewById(R.id.intensity)
        zoomBar = findViewById(R.id.zoomBar)
        toast = findViewById(R.id.toast)

        glView.setEGLContextClientVersion(3)
        glView.preserveEGLContextOnPause = true
        renderer = PreviewRenderer(glView) { st -> main.post { onSurfaceReady(st) } }
        renderer.textures.source = engine
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY

        layoutPreview()
        setupLooks()
        setupControls()
        setupGestures()
        aiEnabled = prefs.getBoolean("ai", true)
        updateAiButton()
        aspectIndex = prefs.getInt("aspect", 0)
        applyAspect()

        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) {
                if (o == ORIENTATION_UNKNOWN) return
                val d = ((o + 45) / 90 * 90) % 360
                if (d != deviceDegrees) { deviceDegrees = d; rotateUi(d) }
            }
        }.enable()
    }

    private fun layoutPreview() {
        val root = findViewById<View>(R.id.root)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()).top
            val w = resources.displayMetrics.widthPixels
            val topBar = findViewById<View>(R.id.topBar)
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top
            topBar.requestLayout()
            val frame = findViewById<View>(R.id.previewFrame)
            val lp = frame.layoutParams as FrameLayout.LayoutParams
            lp.height = w * 4 / 3
            lp.topMargin = top + dp(52)
            frame.layoutParams = lp
            insets
        }
    }

    // ---------------------------------------------------------------- permissions & lifecycle

    override fun onResume() {
        super.onResume()
        glView.onResume()
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION), 1)
        } else {
            surfaceTexture?.let { openCamera(it) }
        }
    }

    override fun onPause() {
        camera.close()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        camera.release()
        processor.release()
        renderer.release()
        m9?.close()
        bg.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            surfaceTexture?.let { openCamera(it) }
        } else showToast("Cần quyền Camera")
    }

    private fun onSurfaceReady(st: SurfaceTexture) {
        surfaceTexture = st
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera(st)
    }

    private fun openCamera(st: SurfaceTexture) {
        val id = if (useFront) camera.frontCameraId() else camera.backCameraId()
        renderer.mirror = useFront
        camera.open(id, st, 4f / 3f)
    }

    // ---------------------------------------------------------------- camera callbacks (camera thread)

    override fun onOpened(info: CameraController.Info) {
        this.info = info
        main.post { buildZoomBar(info) ; pushState() }
    }

    private var lastInfoUpdate = 0L

    override fun onPreviewResult(result: TotalCaptureResult) {
        val i = info ?: return
        val s = i.scene.update(result) ?: return
        scene = s
        val l = look ?: return
        val before = engine.currentBankVersion
        if (l.bank != null && engine.updateBank(l, s) != before) pushState()
        m9?.onPreviewScene(s)
        val now = System.currentTimeMillis()
        if (now - lastInfoUpdate > 400) {
            lastInfoUpdate = now
            val exp = s.exposureNs
            val shutterTxt = if (exp >= 500_000_000L) "%.1fs".format(exp / 1e9) else "1/${(1e9 / exp).roundToInt()}"
            val txt = "ISO ${s.iso}  $shutterTxt  ${s.cct.roundToInt() / 50 * 50}K  LV ${"%.1f".format(s.ev100)}"
            main.post { sceneInfo.text = txt }
        }
    }

    override fun onShutter() {
        main.post {
            val f = findViewById<View>(R.id.flash)
            f.alpha = 0.85f
            f.animate().alpha(0f).setDuration(160).start()
        }
    }

    override fun onStill(image: Image, result: TotalCaptureResult, info: CameraController.Info, request: CameraController.StillRequest) {
        @Suppress("UNCHECKED_CAST")
        val p = request.payload as Pair<Look, LookState>
        val (l, baseState) = p
        // Re-pick the bank LUT from the still's own exposure/AWB, not the preview's.
        val shotScene = info.scene.estimate(result) ?: scene
        val state = if (l.bank != null) {
            val lut = engine.blendBank(l.bank, l.bankScene, shotScene?.luxIndex ?: 200f, shotScene?.cct ?: 5000f)
            baseState.copy(bankVersion = engine.publishBankForStill(lut))
        } else baseState
        val rot = StillProcessor.stillRotation(info.characteristics, request.deviceRotation)
        val refiner = if (l.neural && aiEnabled) m9?.refiner(shotScene, baseState.styleAmount) else null
        processor.submit(
            image, rot, info.facing == CameraCharacteristics.LENS_FACING_FRONT, aspects[aspectIndex].second,
            state, l.name, result, refiner, lastLocation(),
        )
    }

    override fun onError(message: String) {
        main.post { showToast(message) }
    }

    // ---------------------------------------------------------------- still callbacks

    override fun onSaved(uri: Uri, thumb: Bitmap, millis: Long) {
        main.post {
            lastUri = uri
            this.thumb.set(thumb)
        }
    }

    override fun onFailed(message: String) {
        main.post { showToast("Lỗi lưu ảnh: $message") }
    }

    override fun onQueueChanged(pending: Int) {
        main.post { shutter.setBusy(pending) }
    }

    // ---------------------------------------------------------------- looks

    private fun setupLooks() {
        val strip = findViewById<LinearLayout>(R.id.lookStrip)
        val saved = prefs.getString("look", "m9")
        looks.forEach { l -> intensities[l.id] = prefs.getFloat("int_${l.id}", l.defaultIntensity) }
        for (l in looks) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(2))
                tag = l.id
            }
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(8).toFloat())
                }
                setBackgroundColor(0xFF222222.toInt())
            }
            item.addView(img, LinearLayout.LayoutParams(dp(60), dp(60)))
            val tv = TextView(this).apply {
                text = l.name; textSize = 9f; setTextColor(0xCCFFFFFF.toInt()); maxLines = 1
                gravity = Gravity.CENTER
            }
            item.addView(tv, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT))
            item.setOnClickListener { if (look?.id == l.id) toggleIntensity() else selectLook(l, true) }
            strip.addView(item)
            l.thumb?.let { path ->
                bg.execute {
                    val b = try { assets.open(path).use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
                    if (b != null) main.post { img.setImageBitmap(b) }
                }
            }
        }
        selectLook(looks.firstOrNull { it.id == saved } ?: looks[1], false)
        intensity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val l = look ?: return
                intensities[l.id] = p / 100f
                findViewById<TextView>(R.id.intensityLabel).text = "$p%"
                pushState()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                look?.let { prefs.edit().putFloat("int_${it.id}", intensities[it.id] ?: 1f).apply() }
            }
        })
    }

    private fun selectLook(l: Look, user: Boolean) {
        look = l
        lookName.text = l.name
        prefs.edit().putString("look", l.id).apply()
        val strip = findViewById<LinearLayout>(R.id.lookStrip)
        for (i in 0 until strip.childCount) {
            val v = strip.getChildAt(i)
            val sel = v.tag == l.id
            v.getChildAt(0).foreground = if (sel) getDrawable(R.drawable.look_sel) else null
            (v as LinearLayout).getChildAt(1).let { (it as TextView).setTextColor(if (sel) 0xFFE20613.toInt() else 0xCCFFFFFF.toInt()) }
        }
        val p = ((intensities[l.id] ?: l.defaultIntensity) * 100).roundToInt()
        intensity.progress = p
        findViewById<TextView>(R.id.intensityLabel).text = "$p%"
        if (l.bank != null) engine.updateBank(l, scene)
        if (user) vibrate()
        pushState()
    }

    private fun toggleIntensity() {
        val row = findViewById<View>(R.id.intensityRow)
        row.visibility = if (row.visibility == View.VISIBLE) View.INVISIBLE else View.VISIBLE
    }

    private fun View.getChildAt(i: Int) = (this as ViewGroup).getChildAt(i)

    /** Builds the immutable shader state for the current look/intensity and hands it to GL. */
    private fun currentState(): LookState {
        val l = look ?: return LookState()
        val k = intensities[l.id] ?: l.defaultIntensity
        val styleOn = l.neural && aiEnabled && (m9?.ready == true)
        return LookState(
            bankKey = l.bank, bankVersion = engine.currentBankVersion, bankAmount = if (l.bank != null) k else 0f,
            styleVersion = engine.currentStyleVersion, styleAmount = if (styleOn) k else 0f,
            lutAsset = l.lut, lutAmount = if (l.lut != null) k else 0f, tone = l.tone,
            mono = l.mono, monoWeights = l.monoWeights, contrast = l.contrast * k,
            filmAmount = k,
        )
    }

    fun pushState() {
        renderer.state = currentState()
        glView.requestRender()
    }

    // ---------------------------------------------------------------- controls

    private fun setupControls() {
        shutter.setOnClickListener { takePhoto() }
        findViewById<View>(R.id.btnSwitch).setOnClickListener {
            useFront = !useFront
            vibrate()
            surfaceTexture?.let { openCamera(it) }
        }
        thumb.setOnClickListener {
            lastUri?.let { startActivity(Intent(Intent.ACTION_VIEW, it).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
        }
        findViewById<TextView>(R.id.btnGrid).setOnClickListener {
            overlay.showGrid = !overlay.showGrid
            (it as TextView).setTextColor(if (overlay.showGrid) 0xFFE20613.toInt() else 0xFFFFFFFF.toInt())
        }
        findViewById<TextView>(R.id.btnAspect).setOnClickListener {
            aspectIndex = (aspectIndex + 1) % aspects.size
            prefs.edit().putInt("aspect", aspectIndex).apply()
            applyAspect()
        }
        findViewById<TextView>(R.id.btnQuality).setOnClickListener {
            aiEnabled = !aiEnabled
            prefs.edit().putBoolean("ai", aiEnabled).apply()
            updateAiButton()
            showToast(if (aiEnabled) "AI Leica: BẬT" else "AI Leica: TẮT (chỉ LUT)")
            pushState()
        }
        findViewById<View>(R.id.btnCompare).setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { renderer.bypass = true; glView.requestRender(); v.alpha = 0.5f }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { renderer.bypass = false; glView.requestRender(); v.alpha = 1f }
            }
            true
        }
    }

    private fun updateAiButton() {
        findViewById<TextView>(R.id.btnQuality).setTextColor(if (aiEnabled) 0xFFE20613.toInt() else 0x88FFFFFF.toInt())
    }

    private fun applyAspect() {
        val (label, ratio) = aspects[aspectIndex]
        findViewById<TextView>(R.id.btnAspect).text = label
        overlay.post { overlay.setAspect(ratio) }
    }

    private fun takePhoto() {
        val l = look ?: return
        val ok = camera.capture(CameraController.StillRequest(deviceDegrees, camera.zoom, l to currentState()))
        if (!ok) showToast("Đang xử lý…") else vibrate()
    }

    private fun buildZoomBar(info: CameraController.Info) {
        zoomBar.removeAllViews()
        if (info.facing == CameraCharacteristics.LENS_FACING_FRONT) { zoomBar.visibility = View.GONE; return }
        zoomBar.visibility = View.VISIBLE
        for (mm in focalStops) {
            val ratio = mm / 24f
            if (ratio < info.zoomRange.lower - 0.02f || ratio > info.zoomRange.upper) continue
            val tv = TextView(this).apply {
                text = "$mm"; textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt())
                tag = ratio
                setOnClickListener { animateZoom(ratio.coerceAtLeast(info.zoomRange.lower)); vibrate() }
            }
            zoomBar.addView(tv, LinearLayout.LayoutParams(dp(40), dp(36)))
        }
        highlightZoom(camera.zoom)
    }

    private fun highlightZoom(z: Float) {
        var best: View? = null; var bestD = Float.MAX_VALUE
        for (i in 0 until zoomBar.childCount) {
            val v = zoomBar.getChildAt(i); val r = v.tag as Float
            val d = abs(ln(r / z)); if (d < bestD) { bestD = d; best = v }
        }
        for (i in 0 until zoomBar.childCount) {
            val v = zoomBar.getChildAt(i) as TextView
            val sel = v === best && bestD < 0.08f
            v.background = if (sel) getDrawable(R.drawable.zoom_sel) else null
            v.setTextColor(if (sel) 0xFFE20613.toInt() else 0xFFFFFFFF.toInt())
        }
        if (bestD >= 0.08f) showToast("%.0fmm".format(z * 24), 600)
    }

    private var zoomAnim: android.animation.ValueAnimator? = null

    private fun animateZoom(target: Float) {
        zoomAnim?.cancel()
        val from = camera.zoom
        zoomAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                // interpolate in log space so the perceived zoom speed is constant
                val t = it.animatedValue as Float
                val z = Math.exp(ln(from.toDouble()) + (ln(target.toDouble()) - ln(from.toDouble())) * t).toFloat()
                camera.setZoom(z)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { highlightZoom(target) }
            })
            start()
        }
    }

    // ---------------------------------------------------------------- gestures

    private var evBias = 0f

    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val i = info ?: return true
                val z = (camera.zoom * d.scaleFactor).coerceIn(i.zoomRange.lower, i.zoomRange.upper)
                camera.setZoom(z)
                highlightZoom(z)
                return true
            }
        })
        var downX = 0f; var downY = 0f; var dragging = false; var startBias = 0f
        overlay.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (scale.isInProgress || e.pointerCount > 1) { dragging = true; return@setOnTouchListener true }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; dragging = false; startBias = evBias }
                MotionEvent.ACTION_MOVE -> {
                    val dy = e.y - downY
                    if (abs(dy) > dp(12) && overlay.evBias != 0f || abs(dy) > dp(24)) {
                        dragging = true
                        evBias = (startBias - dy / dp(160)).coerceIn(-1f, 1f)
                        overlay.evBias = evBias
                        camera.setExposureCompensation(evBias * 2f)
                    }
                }
                MotionEvent.ACTION_UP -> if (!dragging) {
                    overlay.showFocus(e.x, e.y)
                    evBias = 0f; camera.setExposureCompensation(0f)
                    tapToFocus(e.x / v.width, e.y / v.height)
                    v.performClick()
                }
            }
            true
        }
        overlay.setOnLongClickListener { camera.resetFocus(); overlay.hideFocus(); true }
    }

    /** View coordinates (portrait, upright) -> sensor-normalised coordinates. */
    private fun tapToFocus(x: Float, y: Float) {
        val i = info ?: return
        var sx: Float; var sy: Float
        when (i.sensorOrientation) {
            90 -> { sx = y; sy = 1f - x }
            270 -> { sx = 1f - y; sy = x }
            180 -> { sx = 1f - x; sy = 1f - y }
            else -> { sx = x; sy = y }
        }
        if (i.facing == CameraCharacteristics.LENS_FACING_FRONT) sy = 1f - sy
        camera.focusAt(sx, sy)
    }

    private fun rotateUi(deg: Int) {
        val r = when (deg) { 90 -> -90f; 270 -> 90f; 180 -> 180f; else -> 0f }
        listOf(R.id.thumb, R.id.btnSwitch, R.id.btnAspect, R.id.btnGrid, R.id.btnQuality, R.id.btnCompare)
            .forEach { findViewById<View>(it).animate().rotation(r).setDuration(200).start() }
        for (i in 0 until zoomBar.childCount) zoomBar.getChildAt(i).animate().rotation(r).setDuration(200).start()
    }

    // ---------------------------------------------------------------- helpers

    @Suppress("MissingPermission")
    private fun lastLocation(): Location? {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LocationManager::class.java) ?: return null
        return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000L }
    }

    private fun vibrate() {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
    }

    private fun showToast(msg: String, ms: Long = 1400) {
        toast.text = msg
        toast.animate().cancel()
        toast.alpha = 1f
        toast.animate().alpha(0f).setStartDelay(ms).setDuration(300).start()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
}
