package io.github.abhiguptag22.myvrplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** "How do you want to watch?" — mode picker. */
class ModeActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var path: String
    private var subtitlePath: String? = null
    private lateinit var subtitleChip: LinearLayout
    private lateinit var subtitleChipText: TextView

    private data class Tile(val title: String, val desc: String, val iconRes: Int, val mode: VrMode?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            setPadding(dp(20), dp(14), dp(20), dp(16))
        }

        // ---- top bar
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        bar.addView(iconButton(R.drawable.ic_arrow_back, 48, BtnStyle.TONAL, contentDescription = "Back") { finish() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titles.addView(text("How do you want to watch?", 22f, Palette.TEXT, Type.medium))
        titles.addView(text(File(path).name, 13f, Palette.TEXT_3, lines = 1).apply {
            ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16); rightMargin = dp(16) })

        subtitleChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rippled(rounded(Palette.SURFACE_2, 22f, Palette.STROKE, 1f))
            setPadding(dp(14), 0, dp(16), 0)
            minimumHeight = dp(44)
            isClickable = true
            setOnClickListener { pickSubtitle() }
        }
        subtitleChip.addView(icon(R.drawable.ic_subtitles, 18, Palette.ACCENT_LIGHT), LinearLayout.LayoutParams(dp(18), dp(18)))
        subtitleChipText = text("Add subtitles", 14f, Palette.TEXT, Type.medium, lines = 1)
        subtitleChip.addView(subtitleChipText, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
        bar.addView(subtitleChip, LinearLayout.LayoutParams(-2, dp(44)))
        bar.addView(iconButton(R.drawable.ic_settings, 44, BtnStyle.TONAL, contentDescription = "Settings") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { leftMargin = dp(10) })
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        // ---- cards
        val cards = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cards.addView(card("2D video", "Regular movies", listOf(
            Tile("Static", "Fixed screen", R.drawable.ic_crop_landscape, VrMode.MONO_STATIC),
            Tile("No VR", "Plain fullscreen", R.drawable.ic_movie, VrMode.NO_VR),
            Tile("Gyro", "Head tracking", R.drawable.ic_3d_rotation, null),
            Tile("180°", "Half sphere", R.drawable.ic_panorama_photosphere, null),
            Tile("360°", "Full sphere", R.drawable.ic_360, null),
        )), LinearLayout.LayoutParams(0, -1, 1.25f))
        cards.addView(card("3D side-by-side", "Left | right halves", listOf(
            Tile("Static", "Fixed screen", R.drawable.ic_vertical_split, VrMode.SBS_STATIC),
            Tile("Gyro", "Head tracking", R.drawable.ic_3d_rotation, null),
            Tile("180°", "Half sphere", R.drawable.ic_panorama_photosphere, null),
            Tile("360°", "Full sphere", R.drawable.ic_360, null),
        )), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(14) })
        cards.addView(card("3D top-bottom", "Over / under halves", listOf(
            Tile("Static", "Fixed screen", R.drawable.ic_horizontal_split, VrMode.TOU_STATIC),
            Tile("Gyro", "Head tracking", R.drawable.ic_3d_rotation, null),
            Tile("180°", "Half sphere", R.drawable.ic_panorama_photosphere, null),
            Tile("360°", "Full sphere", R.drawable.ic_360, null),
        )), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(14) })
        root.addView(cards, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(16) })

        setContentView(root)
        goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
    }

    private fun card(title: String, subtitle: String, tiles: List<Tile>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Palette.SURFACE, 18f, Palette.STROKE, 1f)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(text(title, 16f, Palette.TEXT, Type.medium).apply { setPadding(dp(4), 0, 0, 0) })
            addView(text(subtitle, 12.5f, Palette.TEXT_3).apply { setPadding(dp(4), 0, 0, 0) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })

            // Rows of two tiles (three on the wider 2D card's first row).
            val perRow = if (tiles.size == 5) 3 else 2
            val grid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            tiles.chunked(perRow).forEachIndexed { i, chunk ->
                val r = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
                chunk.forEachIndexed { j, t ->
                    r.addView(tile(t), LinearLayout.LayoutParams(0, -1, 1f).apply { if (j > 0) leftMargin = dp(10) })
                }
                // keep tile widths equal on a short last row
                repeat(perRow - chunk.size) { r.addView(View(context), LinearLayout.LayoutParams(0, -1, 1f).apply { leftMargin = dp(10) }) }
                grid.addView(r, LinearLayout.LayoutParams(-1, 0, 1f).apply { if (i > 0) topMargin = dp(10) })
            }
            addView(grid, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(12) })
        }

    private fun tile(t: Tile): FrameLayout {
        val enabled = t.mode != null
        val lastUsed = enabled && t.mode!!.name == prefs.lastPlayedMode
        val box = FrameLayout(this).apply {
            background = rippled(rounded(
                if (lastUsed) 0x1F4D8DFF else Palette.SURFACE_2, 14f,
                if (lastUsed) Palette.ACCENT else Palette.STROKE, if (lastUsed) 1.5f else 1f))
            isClickable = true
            setOnClickListener {
                if (enabled) play(t.mode!!)
                else Toast.makeText(context, "${t.title} mode is coming in a later version", Toast.LENGTH_SHORT).show()
            }
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            alpha = if (enabled) 1f else 0.38f
        }
        col.addView(icon(t.iconRes, 30, if (enabled) Palette.ACCENT_LIGHT else Palette.TEXT_2))
        col.addView(text(t.title, 15f, Palette.TEXT, Type.medium, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        col.addView(text(t.desc, 11.5f, Palette.TEXT_3, gravity = Gravity.CENTER),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        box.addView(col, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        if (!enabled || lastUsed) {
            val badge = text(if (lastUsed) "LAST USED" else "SOON", 9.5f,
                if (lastUsed) Palette.ON_ACCENT else Palette.TEXT_2, Type.medium).apply {
                letterSpacing = 0.1f
                background = rounded(if (lastUsed) Palette.ACCENT else Palette.SURFACE_3, 8f)
                setPadding(dp(7), dp(3), dp(7), dp(3))
            }
            box.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = dp(8); rightMargin = dp(8) })
        }
        return box
    }

    private fun pickSubtitle() {
        startActivityForResult(
            Intent(this, BrowserActivity::class.java)
                .putExtra(BrowserActivity.EXTRA_PICK_SUBTITLE, true)
                .putExtra(BrowserActivity.EXTRA_START_DIR, File(path).parent),
            REQ_SUBTITLE,
        )
    }

    private fun play(mode: VrMode) {
        prefs.lastPlayedMode = mode.name
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PATH, path)
                .putExtra(PlayerActivity.EXTRA_MODE, mode.name)
                .putExtra(PlayerActivity.EXTRA_SUBTITLE, subtitlePath)
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_SUBTITLE && resultCode == RESULT_OK) {
            subtitlePath = data?.getStringExtra(BrowserActivity.EXTRA_RESULT_PATH)
            subtitleChipText.text = subtitlePath?.let { File(it).name } ?: "Add subtitles"
            subtitleChip.background = rippled(rounded(0x1F4D8DFF, 22f, Palette.ACCENT, 1f))
        }
    }

    companion object {
        const val EXTRA_PATH = "path"
        private const val REQ_SUBTITLE = 7
    }
}

enum class VrMode { NO_VR, MONO_STATIC, SBS_STATIC, TOU_STATIC }
