package io.github.abhiguptag22.myvrplayer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import java.io.File

/** Home screen: brand, Browse button, and a "Continue watching" card for the last movie. */
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var continueSlot: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

        val root = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                colors = intArrayOf(0xFF17243D.toInt(), Palette.BG)
                gradientRadius = resources.displayMetrics.widthPixels * 0.75f
                setGradientCenter(0.15f, 0.1f)
            }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(56), dp(24), dp(56), dp(24))
        }

        // ---- left: brand + actions
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logo = FrameLayout(this).apply {
            background = oval(Palette.ACCENT_DIM, Palette.ACCENT, 1.5f)
            addView(icon(R.drawable.ic_head_mounted_device, 34, Palette.ACCENT_LIGHT),
                FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
        }
        left.addView(logo, LinearLayout.LayoutParams(dp(68), dp(68)))
        left.addView(text("MyVR Player", 40f, Palette.TEXT, Type.bold).apply { letterSpacing = -0.01f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(20) })
        left.addView(text("Your movies, made for the headset.", 17f, Palette.TEXT_2),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(pillButton("Browse videos", R.drawable.ic_folder_open, BtnStyle.PRIMARY, 52, 16f) { openBrowser() })
        actions.addView(pillButton("Settings", R.drawable.ic_tune, BtnStyle.TONAL, 52, 16f) {
            startActivity(Intent(this, SettingsActivity::class.java))
        }, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(12) })
        left.addView(actions, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(32) })
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        // ---- right: continue watching
        continueSlot = FrameLayout(this)
        row.addView(continueSlot, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(40) })

        root.addView(row, FrameLayout.LayoutParams(-1, -1))
        root.addView(iconButton(R.drawable.ic_power_settings_new, 44, BtnStyle.GHOST, contentDescription = "Exit") {
            finishAffinity()
        }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.END or Gravity.TOP).apply { topMargin = dp(16); rightMargin = dp(16) })

        setContentView(root)
        goImmersive()
    }

    override fun onResume() {
        super.onResume()
        goImmersive()
        renderContinueCard()
    }

    private fun renderContinueCard() {
        continueSlot.removeAllViews()
        val f = prefs.lastPlayedPath.takeIf { it.isNotEmpty() }?.let { File(it) }?.takeIf { it.exists() }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rippled(rounded(Palette.SURFACE, 20f, Palette.STROKE, 1f))
            setPadding(dp(24), dp(22), dp(24), dp(22))
        }
        if (f == null) {
            card.addView(overline("Get started"))
            card.addView(text("Pick a movie to watch", 22f, Palette.TEXT, Type.medium),
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
            card.addView(text("Browse your folders, choose a video, then pick 2D Static for the classic flat screen.",
                14.5f, Palette.TEXT_2).apply { setLineSpacing(0f, 1.25f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            card.isClickable = true
            card.setOnClickListener { openBrowser() }
            continueSlot.addView(card)
            return
        }

        val pos = prefs.getResume(f)
        val len = prefs.getLength(f)
        card.addView(overline("Continue watching", Palette.ACCENT_LIGHT))
        val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        textCol.addView(text(f.nameWithoutExtension.replace('.', ' ').replace('_', ' '), 20f, Palette.TEXT, Type.medium, lines = 2))
        val meta = when {
            pos > 0 && len > 0 -> "Resume at ${formatTime(pos)}  ·  ${formatTime(len - pos)} left"
            pos > 0 -> "Resume at ${formatTime(pos)}"
            else -> "Play from the start"
        }
        textCol.addView(text(meta, 14f, Palette.TEXT_2), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        line.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(iconButton(R.drawable.ic_play_arrow, 56, BtnStyle.PRIMARY, 30) { resume(f) },
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { leftMargin = dp(16) })
        card.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        if (len > 0) card.addView(progressLine(pos.toFloat() / len), LinearLayout.LayoutParams(-1, dp(4)).apply { topMargin = dp(18) })
        card.isClickable = true
        card.setOnClickListener { resume(f) }
        continueSlot.addView(card)
    }

    private fun resume(f: File) {
        if (!hasStorageAccess()) { openBrowser(); return }
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_PATH, f.absolutePath)
            .putExtra(PlayerActivity.EXTRA_MODE, prefs.lastPlayedMode))
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun openBrowser() {
        if (hasStorageAccess()) {
            startActivity(Intent(this, BrowserActivity::class.java))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Toast.makeText(this, "Allow \"All files access\" so the player can browse your movie folders", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasStorageAccess()) openBrowser()
    }
}
