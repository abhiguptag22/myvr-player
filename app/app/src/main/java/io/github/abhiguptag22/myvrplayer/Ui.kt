package io.github.abhiguptag22.myvrplayer

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

// ======================================================================================
// Design tokens
// ======================================================================================

object Palette {
    const val BG = 0xFF0B0E13.toInt()
    const val BG_RAISED = 0xFF10141B.toInt()
    const val SURFACE = 0xFF161B24.toInt()
    const val SURFACE_2 = 0xFF1E2531.toInt()
    const val SURFACE_3 = 0xFF283142.toInt()
    const val STROKE = 0x1AFFFFFF
    const val STROKE_STRONG = 0x33FFFFFF
    const val TEXT = 0xFFF3F5F9.toInt()
    const val TEXT_2 = 0xFFA6B0C0.toInt()
    const val TEXT_3 = 0xFF6C7689.toInt()
    const val ACCENT = 0xFF4D8DFF.toInt()
    const val ACCENT_LIGHT = 0xFF8AB4FF.toInt()
    const val ACCENT_DIM = 0x334D8DFF
    const val ON_ACCENT = 0xFFFFFFFF.toInt()
    const val GLASS = 0xCC0E121A.toInt()     // translucent panels over video
    const val SCRIM = 0xB3070A0F.toInt()
    const val RIPPLE = 0x33FFFFFF
}

object Type {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val mono: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

// ======================================================================================
// Drawables
// ======================================================================================

fun Context.rounded(color: Int, radiusDp: Float, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
    }

fun Context.oval(color: Int, strokeColor: Int = 0, strokeDp: Float = 0f): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        if (strokeDp > 0f) setStroke(dp(strokeDp), strokeColor)
    }

/** Background with touch ripple, clipped to the same shape. */
fun Context.rippled(content: GradientDrawable, rippleColor: Int = Palette.RIPPLE): Drawable {
    val mask = GradientDrawable().apply {
        shape = content.shape
        cornerRadius = content.cornerRadius
        setColor(0xFFFFFFFF.toInt())
    }
    return RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
}

// ======================================================================================
// Text
// ======================================================================================

fun Context.text(
    s: CharSequence, sp: Float = 15f, color: Int = Palette.TEXT, face: Typeface = Type.regular,
    lines: Int = 0, gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL,
): TextView = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    typeface = face
    this.gravity = gravity
    includeFontPadding = false
    if (lines > 0) {
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
    }
}

/** Small uppercase label with letter spacing, used for section headers. */
fun Context.overline(s: String, color: Int = Palette.TEXT_3): TextView =
    text(s.uppercase(), 11.5f, color, Type.medium).apply { letterSpacing = 0.12f }

// ======================================================================================
// Icons and buttons
// ======================================================================================

fun Context.icon(res: Int, sizeDp: Int = 24, tint: Int = Palette.TEXT): ImageView = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
}

enum class BtnStyle { PRIMARY, TONAL, GHOST, GLASS }

private fun Context.styleColors(style: BtnStyle): Pair<Int, Int> = when (style) {
    BtnStyle.PRIMARY -> Palette.ACCENT to Palette.ON_ACCENT
    BtnStyle.TONAL -> Palette.SURFACE_3 to Palette.TEXT
    BtnStyle.GHOST -> 0x00000000 to Palette.TEXT
    BtnStyle.GLASS -> 0x26FFFFFF to Palette.TEXT
}

/** Circular icon button. [sizeDp] is the touch target, the glyph is ~45% of it. */
fun Context.iconButton(
    res: Int, sizeDp: Int = 48, style: BtnStyle = BtnStyle.GLASS, glyphDp: Int = (sizeDp * 0.46f).toInt(),
    contentDescription: String? = null, onClick: (View) -> Unit,
): FrameLayout {
    val (bg, fg) = styleColors(style)
    return FrameLayout(this).apply {
        background = rippled(oval(bg))
        isClickable = true
        isFocusable = true
        this.contentDescription = contentDescription
        addView(icon(res, glyphDp, fg), FrameLayout.LayoutParams(dp(glyphDp), dp(glyphDp), Gravity.CENTER))
        layoutParams = ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp))
        setOnClickListener(onClick)
    }
}

/** Pill button with optional leading icon. */
fun Context.pillButton(
    label: String, iconRes: Int? = null, style: BtnStyle = BtnStyle.PRIMARY, heightDp: Int = 48,
    textSp: Float = 15f, onClick: (View) -> Unit,
): LinearLayout {
    val (bg, fg) = styleColors(style)
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        background = rippled(rounded(bg, heightDp / 2f))
        setPadding(dp(if (iconRes != null) 18 else 22), 0, dp(22), 0)
        minimumHeight = dp(heightDp)
        isClickable = true
        isFocusable = true
        if (iconRes != null) {
            addView(icon(iconRes, 20, fg), LinearLayout.LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(8) })
        }
        addView(text(label, textSp, fg, Type.medium))
        setOnClickListener(onClick)
    }
}

/**
 * Compact "value chip": small caption above a rounded button showing the current value.
 * Used for cycle-through settings (scale, loop, speed ...).
 */
class ValueChip(context: Context, caption: String, initial: String, iconRes: Int?, onClick: () -> Unit) :
    LinearLayout(context) {
    private val valueView: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val btn = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            background = context.rippled(context.rounded(Palette.SURFACE_3, 12f, Palette.STROKE, 1f))
            setPadding(context.dp(14), 0, context.dp(14), 0)
            minimumHeight = context.dp(44)
            minimumWidth = context.dp(84)
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (iconRes != null) btn.addView(context.icon(iconRes, 18, Palette.ACCENT_LIGHT),
            LayoutParams(context.dp(18), context.dp(18)).apply { rightMargin = context.dp(8) })
        valueView = context.text(initial, 15f, Palette.TEXT, Type.medium)
        btn.addView(valueView)
        addView(btn, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(44)))
        addView(context.overline(caption, Palette.TEXT_2).apply { gravity = Gravity.CENTER },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = context.dp(6) })
    }

    var value: String
        get() = valueView.text.toString()
        set(v) { valueView.text = v }
}

/** iOS/Material-style switch drawn with plain views (no AppCompat). */
class Toggle(context: Context, initial: Boolean, private val onChange: (Boolean) -> Unit) : FrameLayout(context) {
    var checked = initial
        private set
    private val knob = View(context)

    init {
        layoutParams = ViewGroup.LayoutParams(context.dp(48), context.dp(28))
        addView(knob, LayoutParams(context.dp(22), context.dp(22), Gravity.CENTER_VERTICAL))
        isClickable = true
        setOnClickListener { set(!checked, true) }
        render(false)
    }

    fun set(v: Boolean, notify: Boolean = false) {
        checked = v
        render(true)
        if (notify) onChange(v)
    }

    private fun render(animate: Boolean) {
        background = context.rounded(if (checked) Palette.ACCENT else Palette.SURFACE_3, 14f,
            if (checked) 0 else Palette.STROKE_STRONG, if (checked) 0f else 1f)
        knob.background = context.oval(if (checked) Palette.ON_ACCENT else Palette.TEXT_2)
        val x = if (checked) context.dp(23).toFloat() else context.dp(3).toFloat()
        if (animate) knob.animate().translationX(x).setDuration(140).start() else knob.translationX = x
    }
}

// ======================================================================================
// Misc
// ======================================================================================

fun Activity.goImmersive() {
    window.decorView.post {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}

/** Thin progress line (e.g. watched fraction of a movie). */
fun Context.progressLine(fraction: Float, heightDp: Float = 3f): FrameLayout = FrameLayout(this).apply {
    background = rounded(0x22FFFFFF, heightDp)
    val fill = View(context).apply { background = rounded(Palette.ACCENT, heightDp) }
    addView(fill, FrameLayout.LayoutParams(0, -1))
    addOnLayoutChangeListener { _, l, _, r, _, _, _, _, _ ->
        val w = ((r - l) * fraction.coerceIn(0f, 1f)).toInt()
        if (fill.layoutParams.width != w) fill.post { fill.layoutParams = fill.layoutParams.apply { width = w } }
    }
}
