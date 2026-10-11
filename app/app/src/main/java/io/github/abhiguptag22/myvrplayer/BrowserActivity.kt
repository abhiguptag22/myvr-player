package io.github.abhiguptag22.myvrplayer

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Folder browser. Lists sub-folders and playable files of the current folder.
 * With EXTRA_PICK_SUBTITLE it lists subtitle files and returns the chosen path.
 */
class BrowserActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var titleView: TextView
    private lateinit var pathView: TextView
    private lateinit var countView: TextView
    private lateinit var list: ListView
    private lateinit var empty: LinearLayout
    private var pickSubtitle = false
    private var current = File(ROOT)
    private var entries: List<Entry> = emptyList()

    private data class Entry(val file: File, val name: String, val kind: Kind, val meta: String = "", val progress: Float = -1f)
    private enum class Kind { UP, STORAGE, DIR, FILE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        pickSubtitle = intent.getBooleanExtra(EXTRA_PICK_SUBTITLE, false)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
        }

        // ---- top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(14), dp(28), dp(10))
        }
        bar.addView(iconButton(R.drawable.ic_arrow_back, 48, BtnStyle.TONAL, contentDescription = "Back") { goUpOrFinish() })
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleView = text("", 22f, Palette.TEXT, Type.medium, lines = 1)
        pathView = text("", 13f, Palette.TEXT_3, lines = 1).apply { ellipsize = android.text.TextUtils.TruncateAt.START }
        titles.addView(titleView)
        titles.addView(pathView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        bar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })
        countView = text("", 13f, Palette.TEXT_2, Type.medium).apply {
            background = rounded(Palette.SURFACE_2, 14f)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        bar.addView(countView)
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        // ---- list
        val body = FrameLayout(this)
        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            setPadding(dp(20), dp(4), dp(20), dp(24))
            clipToPadding = false
            isVerticalScrollBarEnabled = false
            setOnItemClickListener { _, _, pos, _ -> onPick(entries[pos]) }
        }
        body.addView(list, FrameLayout.LayoutParams(-1, -1))
        empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(icon(R.drawable.ic_folder_open, 48, Palette.TEXT_3))
            addView(text(if (pickSubtitle) "No subtitle files here" else "No videos in this folder", 16f, Palette.TEXT_2, Type.medium),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
            visibility = View.GONE
        }
        body.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val start = intent.getStringExtra(EXTRA_START_DIR)?.let { File(it) } ?: File(prefs.lastFolder)
        show(if (start.isDirectory && start.canRead()) start else File(ROOT))
        goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        if (::list.isInitialized) show(current) // refresh watched progress
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goUpOrFinish()

    private fun goUpOrFinish() {
        val parent = current.parentFile
        if (parent != null && parent.canRead() && current.absolutePath != ROOT) show(parent) else finish()
    }

    private fun show(dir: File) {
        current = dir
        titleView.text = when {
            pickSubtitle -> "Choose subtitles"
            dir.absolutePath == ROOT -> "Internal storage"
            else -> dir.name
        }
        pathView.text = dir.absolutePath.replace(ROOT, "Internal storage").replace("/", "  ›  ").removePrefix("  ›  ")
        pathView.visibility = if (dir.absolutePath == ROOT && !pickSubtitle) View.GONE else View.VISIBLE
        val exts = if (pickSubtitle) Prefs.SUBTITLE_EXTENSIONS else Prefs.VIDEO_EXTENSIONS
        val children = (dir.listFiles() ?: emptyArray()).filter { !it.name.startsWith(".") }
        val dirs = children.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
        val files = children.filter { it.isFile && it.extension.lowercase() in exts }.sortedBy { it.name.lowercase() }

        val out = ArrayList<Entry>()
        if (dir.absolutePath == ROOT) {
            File("/storage").listFiles()
                ?.filter { it.isDirectory && it.canRead() && it.name != "emulated" && it.name != "self" }
                ?.forEach { out.add(Entry(it, "SD card", Kind.STORAGE, it.name)) }
        } else if (dir.parentFile != null) {
            out.add(Entry(dir.parentFile!!, "Up one level", Kind.UP))
        }
        dirs.forEach { d ->
            val n = d.listFiles()?.count { it.isFile && it.extension.lowercase() in exts } ?: 0
            out.add(Entry(d, d.name, Kind.DIR, if (n == 0) "" else if (n == 1) "1 video" else "$n videos"))
        }
        files.forEach { f ->
            val pos = prefs.getResume(f)
            val len = prefs.getLength(f)
            val meta = buildString {
                append(f.extension.uppercase()); append("  ·  "); append(formatSize(f.length()))
                if (pos > 0) { append("  ·  Resume "); append(formatTime(pos)) }
            }
            out.add(Entry(f, f.name, Kind.FILE, meta, if (pos > 0 && len > 0) pos.toFloat() / len else -1f))
        }
        entries = out
        list.adapter = Adapter()
        countView.text = if (pickSubtitle) "${files.size} files" else if (files.size == 1) "1 video" else "${files.size} videos"
        countView.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (dirs.isEmpty() && files.isEmpty()) View.VISIBLE else View.GONE
        if (!pickSubtitle) prefs.lastFolder = dir.absolutePath
    }

    private fun onPick(e: Entry) {
        if (e.kind != Kind.FILE) {
            if (e.file.canRead()) show(e.file)
            return
        }
        if (pickSubtitle) {
            setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT_PATH, e.file.absolutePath))
            finish()
        } else {
            startActivity(Intent(this, ModeActivity::class.java).putExtra(ModeActivity.EXTRA_PATH, e.file.absolutePath))
        }
    }

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 1
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val e = entries[position]
            val ctx = this@BrowserActivity
            val wrap = FrameLayout(ctx).apply {
                layoutParams = AbsListView.LayoutParams(-1, -2)
                setPadding(0, dp(4), 0, dp(4))
            }
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rippled(rounded(if (e.kind == Kind.UP) Palette.BG_RAISED else Palette.SURFACE, 14f, Palette.STROKE, 1f))
                setPadding(dp(16), dp(12), dp(20), dp(12))
                minimumHeight = dp(64)
                isDuplicateParentStateEnabled = true
            }
            val (iconRes, tileColor, iconTint) = when (e.kind) {
                Kind.UP -> Triple(R.drawable.ic_arrow_back, Palette.SURFACE_2, Palette.TEXT_2)
                Kind.STORAGE -> Triple(R.drawable.ic_sd_card, 0x2634C27A, 0xFF5DD69A.toInt())
                Kind.DIR -> Triple(R.drawable.ic_folder, 0x26F5B83D, 0xFFF5C55B.toInt())
                Kind.FILE -> Triple(if (pickSubtitle) R.drawable.ic_subtitles else R.drawable.ic_movie, Palette.ACCENT_DIM, Palette.ACCENT_LIGHT)
            }
            val tile = FrameLayout(ctx).apply {
                background = rounded(tileColor, 10f)
                addView(icon(iconRes, 22, iconTint), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
            }
            row.addView(tile, LinearLayout.LayoutParams(dp(40), dp(40)))

            val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(e.name, 16.5f, if (e.kind == Kind.UP) Palette.TEXT_2 else Palette.TEXT,
                if (e.kind == Kind.FILE) Type.regular else Type.medium, lines = 1))
            if (e.meta.isNotEmpty() && e.kind == Kind.FILE) {
                col.addView(text(e.meta, 12.5f, Palette.TEXT_3), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(5) })
            }
            if (e.progress >= 0f) {
                col.addView(progressLine(e.progress, 3f), LinearLayout.LayoutParams(dp(220), dp(3)).apply { topMargin = dp(8) })
            }
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(16) })

            if (e.kind == Kind.DIR && e.meta.isNotEmpty()) {
                row.addView(text(e.meta, 13f, Palette.TEXT_3), LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(10) })
            }
            if (e.kind == Kind.DIR || e.kind == Kind.STORAGE) row.addView(icon(R.drawable.ic_chevron_right, 22, Palette.TEXT_3))
            if (e.kind == Kind.FILE && !pickSubtitle) row.addView(icon(R.drawable.ic_play_circle, 28, Palette.ACCENT))
            wrap.addView(row, FrameLayout.LayoutParams(-1, -2))
            return wrap
        }
    }

    companion object {
        private const val ROOT = "/storage/emulated/0"
        const val EXTRA_PICK_SUBTITLE = "pickSubtitle"
        const val EXTRA_START_DIR = "startDir"
        const val EXTRA_RESULT_PATH = "path"
    }
}
