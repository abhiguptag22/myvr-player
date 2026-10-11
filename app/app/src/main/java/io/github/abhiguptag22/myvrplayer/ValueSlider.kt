package io.github.abhiguptag22.myvrplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Thin-track slider with a round thumb. Dragging is relative (grab anywhere and move), which makes
 * small adjustments easy; a tap nudges the value one step toward the tap.
 * If the range spans zero, the accent fill grows from zero, so offsets read naturally.
 */
@SuppressLint("ViewConstructor")
class ValueSlider(
    context: Context,
    val min: Float,
    val max: Float,
    val step: Float,
    val vertical: Boolean = false,
    val format: (Float) -> String,
) : View(context) {

    var value: Float = min
        set(v) {
            val snapped = snap(v)
            if (snapped != field) {
                field = snapped
                invalidate()
            }
        }

    var onChange: ((Float) -> Unit)? = null
    var onRelease: ((Float) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
    /** Draw the value in a bubble on the thumb (used by the vertical distance slider). */
    var showBubble = false

    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(4 * d, 0f, 1 * d, 0x80000000.toInt())
    }
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x404D8DFF }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Type.medium; textSize = 13 * d
    }
    private val rect = RectF()

    private var dragStartPos = 0f
    private var dragStartValue = 0f
    private var dragging = false
    private var pressed = false

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // for the thumb shadow
    }

    private fun snap(v: Float): Float {
        val c = v.coerceIn(min, max)
        return if (step > 0f) (min + ((c - min) / step).roundToInt() * step).coerceIn(min, max) else c
    }

    private val pad get() = (if (showBubble) 22f else 12f) * d
    private val length get() = (if (vertical) height else width) - 2 * pad

    private fun posOf(v: Float): Float {
        val t = (v - min) / (max - min)
        return if (vertical) pad + (1f - t) * length else pad + t * length
    }

    override fun onDraw(canvas: Canvas) {
        val track = 4 * d
        val zero = if (min < 0f && max > 0f) 0f else min
        val p = posOf(value)
        val z = posOf(zero)
        if (vertical) {
            val cx = width / 2f
            rect.set(cx - track / 2, pad, cx + track / 2, height - pad)
            canvas.drawRoundRect(rect, track, track, trackPaint)
            rect.set(cx - track / 2, minOf(p, z), cx + track / 2, maxOf(p, z))
            canvas.drawRoundRect(rect, track, track, fillPaint)
            drawThumb(canvas, cx, p)
        } else {
            val cy = height / 2f
            rect.set(pad, cy - track / 2, width - pad, cy + track / 2)
            canvas.drawRoundRect(rect, track, track, trackPaint)
            rect.set(minOf(p, z), cy - track / 2, maxOf(p, z), cy + track / 2)
            canvas.drawRoundRect(rect, track, track, fillPaint)
            drawThumb(canvas, p, cy)
        }
    }

    private fun drawThumb(canvas: Canvas, x: Float, y: Float) {
        if (showBubble) {
            val label = format(value)
            val w = maxOf(40 * d, bubbleText.measureText(label) + 18 * d)
            val h = 28 * d
            rect.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
            if (vertical) rect.set(width / 2f - w / 2, y - h / 2, width / 2f + w / 2, y + h / 2)
            canvas.drawRoundRect(rect, h / 2, h / 2, bubblePaint)
            canvas.drawText(label, rect.centerX(), rect.centerY() - (bubbleText.descent() + bubbleText.ascent()) / 2, bubbleText)
            return
        }
        if (pressed) canvas.drawCircle(x, y, 16 * d, haloPaint)
        canvas.drawCircle(x, y, (if (pressed) 10f else 8f) * d, thumbPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pos = if (vertical) -e.y else e.x
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouchStart?.invoke()
                dragStartPos = pos
                dragStartValue = value
                dragging = false
                pressed = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val delta = pos - dragStartPos
                if (!dragging && abs(delta) > 4 * d) dragging = true
                if (dragging && length > 0) {
                    val nv = snap(dragStartValue + delta / length * (max - min))
                    if (nv != value) {
                        value = nv
                        onChange?.invoke(value)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                    val thumb = posOf(value)
                    val tapPos = if (vertical) e.y else e.x
                    val up = if (vertical) tapPos < thumb else tapPos > thumb
                    val nv = snap(value + if (up) step else -step)
                    if (nv != value) {
                        value = nv
                        onChange?.invoke(value)
                    }
                }
                pressed = false
                dragging = false
                invalidate()
                onRelease?.invoke(value)
            }
        }
        return true
    }
}

/** Icon + title + live value on one line, slider underneath. */
@SuppressLint("ViewConstructor")
class SliderRow(context: Context, iconRes: Int, title: String, val slider: ValueSlider) : LinearLayout(context) {
    private val valueView: TextView

    init {
        orientation = VERTICAL
        val head = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(12), 0, context.dp(12), 0)
        }
        head.addView(context.icon(iconRes, 18, Palette.ACCENT_LIGHT), LayoutParams(context.dp(18), context.dp(18)))
        head.addView(context.text(title, 13f, Palette.TEXT_2, Type.medium, lines = 1),
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = context.dp(8) })
        valueView = context.text(slider.format(slider.value), 14f, Palette.TEXT, Type.medium)
        head.addView(valueView)
        addView(head, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(20)))
        addView(slider, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(28)))
        val prev = slider.onChange
        slider.onChange = { prev?.invoke(it); refresh() }
    }

    /** Wraps a later-assigned onChange too. */
    fun listen(block: (Float) -> Unit) {
        slider.onChange = { block(it); refresh() }
    }

    fun refresh() {
        valueView.text = slider.format(slider.value)
    }
}

/**
 * Seek bar: thin track that thickens while dragging, elapsed fill, round thumb.
 * Reports the target only on release (one seek per drag).
 */
class TimeBar(context: Context) : View(context) {
    var fraction = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var onScrub: ((Float) -> Unit)? = null
    var onCommit: ((Float) -> Unit)? = null
    var onTouchStart: (() -> Unit)? = null
    var scrubbing = false
        private set

    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ACCENT }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()
    private val pad get() = 10 * d

    override fun onDraw(canvas: Canvas) {
        val h = (if (scrubbing) 8f else 5f) * d
        val cy = height / 2f
        val w = width - 2 * pad
        rect.set(pad, cy - h / 2, pad + w, cy + h / 2)
        canvas.drawRoundRect(rect, h, h, track)
        val x = pad + w * fraction
        rect.set(pad, cy - h / 2, x, cy + h / 2)
        canvas.drawRoundRect(rect, h, h, fill)
        canvas.drawCircle(x, cy, (if (scrubbing) 11f else 8f) * d, thumb)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val f = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onTouchStart?.invoke()
                scrubbing = true
                fraction = f
                onScrub?.invoke(f)
            }
            MotionEvent.ACTION_MOVE -> { fraction = f; onScrub?.invoke(f) }
            MotionEvent.ACTION_UP -> { scrubbing = false; fraction = f; onCommit?.invoke(f) }
            MotionEvent.ACTION_CANCEL -> { scrubbing = false; invalidate() }
        }
        return true
    }
}
