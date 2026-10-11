package io.github.abhiguptag22.myvrplayer

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/** Every persisted setting. Slider values survive app restarts. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("vrplayer", Context.MODE_PRIVATE)

    // ---- the five on-screen VR sliders -------------------------------------------------
    var hShift by float("hShift", DEF_H_SHIFT)        // "< >"  percent, picture offset inside each eye
    var vShift by float("vShift", -51f)               // "^ v"  percent, picture vertical offset
    var distortion by float("distortion", DEF_DISTORTION) // eye icon, lens distortion strength
    var ipd by float("ipd", DEF_IPD)                  // "> <"  eye-window separation
    var distance by float("distance", DEF_DISTANCE)   // right vertical slider, virtual screen distance

    fun resetSliders() {
        hShift = DEF_H_SHIFT; vShift = DEF_V_SHIFT; distortion = DEF_DISTORTION
        ipd = DEF_IPD; distance = DEF_DISTANCE
    }

    // ---- in-player gear panel ----------------------------------------------------------
    var volume by int("volume", 100)             // 0..100 %
    var hd by int("hd", 100)                     // render resolution %, 30..100
    var brightness by int("brightness", 50)      // 50 = neutral
    var saturation by int("saturation", 50)      // 50 = neutral
    var contrast by int("contrast", 50)          // 50 = neutral
    var subtitleSize by int("subtitleSize", 100) // %
    var subtitlePos by int("subtitlePos", 0)     // % of picture height, + = up
    var scaleIndex by int("scaleIndex", 0)       // index into SCALES
    var invertScale by bool("invertScale", false)
    var loop by bool("loop", true)
    var speedIndex by int("speedIndex", 0)       // index into SPEEDS
    var theatre by bool("theatre", true)         // cinema hall around the screen
    var roomLight by int("roomLight", 0)         // house lights %, 0..100

    // ---- settings screen ---------------------------------------------------------------
    var hwAcceleration by bool("hwAcceleration", true)
    var lensDistortionEnabled by bool("lensDistortionEnabled", true)
    var imageSettingsEnabled by bool("imageSettingsEnabled", true)
    var networkCachingSec by int("networkCachingSec", 3)
    var touchControlSize by int("touchControlSize", 100)
    var resampleAudio by bool("resampleAudio", true)
    var bluetoothSpaceEnter by bool("bluetoothSpaceEnter", true) // treat SPACE/ENTER keys as play/pause

    fun resetSettings() {
        sp.edit()
            .remove("hwAcceleration").remove("lensDistortionEnabled").remove("imageSettingsEnabled")
            .remove("networkCachingSec").remove("touchControlSize").remove("resampleAudio")
            .remove("bluetoothSpaceEnter")
            .apply()
    }

    // ---- navigation ----------------------------------------------------------------------
    var lastFolder by string("lastFolder", "/storage/emulated/0")
    var lastPlayedPath by string("lastPlayedPath", "")
    var lastPlayedMode by string("lastPlayedMode", "MONO_STATIC")

    // ---- resume positions ----------------------------------------------------------------
    /** Canonical path, so /sdcard/... and /storage/emulated/0/... map to the same entry. */
    private fun keyPath(file: File): String = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)

    private fun resumeKey(file: File) = "resume:" + keyPath(file) + ":" + file.length()

    fun getResume(file: File): Long = sp.getLong(resumeKey(file), 0L)

    fun setResume(file: File, ms: Long) {
        sp.edit().putLong(resumeKey(file), ms).apply()
    }

    /** Total length, stored next to the resume point so lists can show watched progress. */
    fun getLength(file: File): Long = sp.getLong("length:" + keyPath(file), 0L)

    fun setLength(file: File, ms: Long) {
        sp.edit().putLong("length:" + keyPath(file), ms).apply()
    }

    /** Subtitle delay in ms (+ = subtitles later), remembered per file. */
    fun getSubtitleDelay(file: File): Long = sp.getLong("subdelay:" + keyPath(file), 0L)

    fun setSubtitleDelay(file: File, ms: Long) {
        if (ms == 0L) sp.edit().remove("subdelay:" + keyPath(file)).apply()
        else sp.edit().putLong("subdelay:" + keyPath(file), ms).apply()
    }

    fun clearResume(file: File) {
        sp.edit().remove(resumeKey(file)).apply()
    }

    // ---- delegates ----------------------------------------------------------------------
    private fun float(key: String, def: Float) = Pref({ sp.getFloat(key, def) }, { sp.edit().putFloat(key, it).apply() })
    private fun int(key: String, def: Int) = Pref({ sp.getInt(key, def) }, { sp.edit().putInt(key, it).apply() })
    private fun bool(key: String, def: Boolean) = Pref({ sp.getBoolean(key, def) }, { sp.edit().putBoolean(key, it).apply() })
    private fun string(key: String, def: String) = Pref({ sp.getString(key, def) ?: def }, { sp.edit().putString(key, it).apply() })

    private class Pref<T>(val get: () -> T, val set: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, p: kotlin.reflect.KProperty<*>): T = get()
        operator fun setValue(thisRef: Any?, p: kotlin.reflect.KProperty<*>, value: T) = set(value)
    }

    companion object {
        const val DEF_H_SHIFT = 0f
        const val DEF_V_SHIFT = 0f
        const val DEF_DISTORTION = 0f
        const val DEF_IPD = 0.46f
        const val DEF_DISTANCE = 49f

        /** Aspect-ratio overrides for the Aspect chip. 0 = video's own aspect. */
        val SCALES = listOf(
            "1:1" to 0f, "4:3" to 4f / 3f, "16:9" to 16f / 9f, "16:10" to 16f / 10f,
            "1.43:1" to 1.43f, "2.35:1" to 2.35f, "2.39:1" to 2.39f, "NO" to -1f,
        )
        val SPEEDS = listOf(1f, 2f, 4f, 0.5f)

        val VIDEO_EXTENSIONS = setOf(
            "mkv", "mp4", "m4v", "avi", "mov", "webm", "wmv", "flv", "ts", "m2ts", "mts",
            "mpg", "mpeg", "3gp", "vob", "ogv", "divx", "xvid", "rmvb", "rm", "asf",
        )
        val SUBTITLE_EXTENSIONS = setOf("srt", "ass", "ssa", "sub", "vtt", "idx", "smi", "txt")
    }
}
