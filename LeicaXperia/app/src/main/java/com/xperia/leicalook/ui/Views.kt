package com.xperia.leicalook.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.min

private fun View.dp(v: Float) = v * resources.displayMetrics.density

const val LEICA_RED = 0xFFE20613.toInt()

/** Leica-style shutter: white ring, red core; shrinks while pressed, spins an arc while processing. */
class ShutterButton @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LEICA_RED }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeCap = Paint.Cap.ROUND }
    private var scale = 1f
    private var busy = 0
    private var sweep = 0f
    private val oval = RectF()
    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 900; repeatCount = ValueAnimator.INFINITE
        addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
    }

    fun setBusy(count: Int) {
        busy = count
        if (count > 0 && !spinner.isStarted) spinner.start()
        if (count == 0) { spinner.cancel(); invalidate() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> animateScale(0.88f)
            MotionEvent.ACTION_UP -> { animateScale(1f); if (isEnabled) performClick() }
            MotionEvent.ACTION_CANCEL -> animateScale(1f)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun animateScale(to: Float) {
        ValueAnimator.ofFloat(scale, to).apply {
            duration = 90; interpolator = DecelerateInterpolator()
            addUpdateListener { scale = it.animatedValue as Float; invalidate() }
        }.start()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(width, height) / 2f - dp(3f)
        ring.strokeWidth = dp(3.5f)
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx, cy, (r - dp(8f)) * scale, core)
        if (busy > 0) {
            arc.strokeWidth = dp(3.5f)
            oval.set(cx - r, cy - r, cx + r, cy + r)
            arc.color = LEICA_RED
            c.drawArc(oval, sweep - 90f, 80f, false, arc)
        }
    }
}

/** Rule-of-thirds grid and focus ring drawn above the GL preview. */
class PreviewOverlay @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var showGrid = false
        set(v) { field = v; invalidate() }
    /** Visible crop inside the 4:3 frame for 1:1 / 3:2 / 16:9 framing, in view pixels. */
    val crop = RectF()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; strokeWidth = 1.5f }
    private val maskPaint = Paint().apply { color = 0xCC000000.toInt() }
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD54F.toInt(); strokeWidth = 4f }
    private var fx = -1f; private var fy = -1f; private var fAlpha = 0f; private var fScale = 1f
    /** -1..1 exposure bias shown next to the focus ring while dragging. */
    var evBias = 0f
        set(v) { field = v; invalidate() }
    private var fadeAnim: ValueAnimator? = null

    fun setAspect(ratio: Float) {
        // ratio = long side / short side of the final photo; frame is 4:3 portrait.
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0) return
        val targetH = min(h, w * ratio)
        val top = (h - targetH) / 2f
        crop.set(0f, top, w, top + targetH)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (crop.isEmpty) crop.set(0f, 0f, w.toFloat(), h.toFloat())
        else setAspect(crop.height() / crop.width())
    }

    fun showFocus(x: Float, y: Float) {
        fx = x; fy = y; fAlpha = 1f; evBias = 0f
        fadeAnim?.cancel()
        ValueAnimator.ofFloat(1.35f, 1f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { fScale = it.animatedValue as Float; invalidate() }
        }.start()
        fadeAnim = ValueAnimator.ofFloat(1f, 0.35f).apply {
            startDelay = 1800; duration = 600
            addUpdateListener { fAlpha = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun hideFocus() { fadeAnim?.cancel(); fx = -1f; invalidate() }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (crop.top > 0f) {
            c.drawRect(0f, 0f, w, crop.top, maskPaint)
            c.drawRect(0f, crop.bottom, w, h, maskPaint)
        }
        if (showGrid) {
            for (i in 1..2) {
                val x = crop.left + crop.width() * i / 3f
                val y = crop.top + crop.height() * i / 3f
                c.drawLine(x, crop.top, x, crop.bottom, gridPaint)
                c.drawLine(crop.left, y, crop.right, y, gridPaint)
            }
        }
        if (fx >= 0f) {
            val r = dp(34f) * fScale
            focusPaint.strokeWidth = dp(1.5f)
            focusPaint.alpha = (255 * fAlpha).toInt()
            c.drawCircle(fx, fy, r, focusPaint)
            c.drawCircle(fx, fy, dp(3f), focusPaint)
            // exposure sun on the right of the ring
            val sx = fx + r + dp(18f)
            val len = dp(60f)
            sunPaint.alpha = (255 * fAlpha).toInt()
            c.drawLine(sx, fy - len, sx, fy + len, gridPaint)
            c.drawCircle(sx, fy - evBias * len, dp(7f), sunPaint)
        }
    }
}

/** Round thumbnail of the last photo. */
class ThumbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var bmp: android.graphics.Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x88FFFFFF.toInt() }
    private val path = android.graphics.Path()
    private val src = android.graphics.Rect(); private val dst = RectF()

    fun set(b: android.graphics.Bitmap?) {
        bmp = b
        scaleX = 0.6f; scaleY = 0.6f
        animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val r = min(width, height) / 2f
        val cx = width / 2f; val cy = height / 2f
        val b = bmp
        if (b != null) {
            path.reset(); path.addCircle(cx, cy, r, android.graphics.Path.Direction.CW)
            c.save(); c.clipPath(path)
            val s = min(b.width, b.height)
            src.set((b.width - s) / 2, (b.height - s) / 2, (b.width + s) / 2, (b.height + s) / 2)
            dst.set(cx - r, cy - r, cx + r, cy + r)
            c.drawBitmap(b, src, dst, paint)
            c.restore()
        }
        border.strokeWidth = dp(1.5f)
        c.drawCircle(cx, cy, r - dp(1f), border)
    }
}
