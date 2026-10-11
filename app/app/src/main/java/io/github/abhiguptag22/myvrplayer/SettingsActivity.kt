package io.github.abhiguptag22.myvrplayer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast

/** Settings, grouped into cards. */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(6))
        }
        bar.addView(iconButton(R.drawable.ic_arrow_back, 48, BtnStyle.TONAL, contentDescription = "Back") { finish() })
        bar.addView(text("Settings", 22f, Palette.TEXT, Type.medium), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        root.addView(bar)

        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(40))
        }
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(column) }
        val center = FrameLayout(this)
        center.addView(scroll, FrameLayout.LayoutParams(dp(680), -1, Gravity.CENTER_HORIZONTAL))
        root.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        build()
        goImmersive()
    }

    private fun build() {
        column.removeAllViews()

        section("Playback").apply {
            toggleRow(R.drawable.ic_speed, "Hardware decoding",
                "Faster and cooler. Turn off if you see artifacts or seeking freezes.", prefs.hwAcceleration) { prefs.hwAcceleration = it }
            divider()
            toggleRow(R.drawable.ic_volume_up, "Resample audio",
                "Keeps voices natural at x0.5, x2 and x4 speed.", prefs.resampleAudio) { prefs.resampleAudio = it }
            divider()
            sliderRow(R.drawable.ic_history, "Network caching", 1f, 20f, 1f, prefs.networkCachingSec.toFloat(),
                { "${it.toInt()} s" }) { prefs.networkCachingSec = it.toInt() }
        }

        section("Display").apply {
            toggleRow(R.drawable.ic_visibility, "Lens distortion",
                "Enables the eye slider in the player. Turn off to save battery.", prefs.lensDistortionEnabled) { prefs.lensDistortionEnabled = it }
            divider()
            toggleRow(R.drawable.ic_palette, "Picture adjustments",
                "Brightness, contrast and saturation controls.", prefs.imageSettingsEnabled) { prefs.imageSettingsEnabled = it }
        }

        section("Controls").apply {
            sliderRow(R.drawable.ic_zoom_in, "Touch control size", 60f, 140f, 5f, prefs.touchControlSize.toFloat(),
                { "${it.toInt()}%" }) { prefs.touchControlSize = it.toInt() }
            divider()
            toggleRow(R.drawable.ic_bluetooth, "Remote: Space / Enter play-pause",
                "For Bluetooth remotes that send keyboard keys instead of media keys.", prefs.bluetoothSpaceEnter) { prefs.bluetoothSpaceEnter = it }
        }

        column.addView(pillButton("Reset all settings", R.drawable.ic_restart_alt, BtnStyle.TONAL, 48, 15f) {
            prefs.resetSettings()
            build()
            Toast.makeText(this, "Settings restored to defaults", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); gravity = Gravity.CENTER_HORIZONTAL })
    }

    private fun section(title: String): LinearLayout {
        column.addView(overline(title).apply { setPadding(dp(6), 0, 0, 0) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18); bottomMargin = dp(8) })
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 16f, Palette.STROKE, 1f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        column.addView(card, LinearLayout.LayoutParams(-1, -2))
        return card
    }

    private fun LinearLayout.divider() {
        addView(View(context).apply { setBackgroundColor(Palette.STROKE) },
            LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(60); rightMargin = dp(16) })
    }

    private fun LinearLayout.rowShell(iconRes: Int): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(16), dp(14))
        }
        val tile = FrameLayout(context).apply {
            background = rounded(Palette.SURFACE_2, 10f)
            addView(icon(iconRes, 20, Palette.ACCENT_LIGHT), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }
        row.addView(tile, LinearLayout.LayoutParams(dp(36), dp(36)))
        addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    private fun LinearLayout.toggleRow(iconRes: Int, title: String, hint: String, initial: Boolean, onSet: (Boolean) -> Unit) {
        val row = rowShell(iconRes)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, Palette.TEXT, Type.medium))
        texts.addView(text(hint, 13f, Palette.TEXT_3).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14); rightMargin = dp(16) })
        val toggle = Toggle(context, initial, onSet)
        row.addView(toggle, LinearLayout.LayoutParams(dp(48), dp(28)))
        row.isClickable = true
        row.background = rippled(rounded(0, 12f))
        row.setOnClickListener { toggle.set(!toggle.checked, true) }
    }

    private fun LinearLayout.sliderRow(iconRes: Int, title: String, min: Float, max: Float, step: Float, initial: Float,
                                       fmt: (Float) -> String, onSet: (Float) -> Unit) {
        val row = rowShell(iconRes)
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(text(title, 16f, Palette.TEXT, Type.medium), LinearLayout.LayoutParams(0, -2, 1f))
        val value = text(fmt(initial), 15f, Palette.ACCENT_LIGHT, Type.medium)
        head.addView(value)
        texts.addView(head)
        val slider = ValueSlider(context, min, max, step, false, fmt).apply {
            this.value = initial
            onChange = { value.text = fmt(it) }
            onRelease = onSet
        }
        texts.addView(slider, LinearLayout.LayoutParams(-1, dp(34)).apply { topMargin = dp(4) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
    }
}
