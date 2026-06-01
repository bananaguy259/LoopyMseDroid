package com.loopymse.droid.util

import android.content.Context
import android.content.SharedPreferences
import com.loopymse.droid.ui.RomEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Central manager for all app preferences and the ROM library list.
 *
 * Uses SharedPreferences for all settings.
 * ROM list is stored as a JSON array.
 * Also writes loopymse.ini to internal storage so the C++ core reads it.
 */
class PreferencesManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "loopymse_prefs"

        // Keys
        private const val KEY_ROMS            = "rom_list"
        private const val KEY_BIOS_URI        = "bios_uri"
        private const val KEY_SOUND_BIOS_URI  = "sound_bios_uri"
        private const val KEY_ASPECT_RATIO    = "correct_aspect_ratio"
        private const val KEY_CROP_OVERSCAN   = "crop_overscan"
        private const val KEY_ANTIALIAS       = "antialias"
        private const val KEY_BG_AUDIO        = "run_in_background"
        private const val KEY_GAMEPAD_OPACITY = "gamepad_opacity"
        private const val KEY_GAMEPAD_SHOW    = "show_gamepad"
        private const val KEY_HAPTICS         = "haptics"
        private const val KEY_SCREENSHOT_FMT  = "screenshot_format"
        private const val KEY_PRINTER_FMT     = "printer_format"
        private const val KEY_SETUP_DONE      = "setup_done"
        private const val KEY_SORT_ORDER      = "sort_order"

        // Sort order constants
        const val SORT_NAME      = "name"
        const val SORT_LAST_PLAY = "last_played"
        const val SORT_ADDED     = "added"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ─── Setup ───────────────────────────────────────────────────────────────

    var isSetupDone: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(v) = prefs.edit().putBoolean(KEY_SETUP_DONE, v).apply()

    // ─── BIOS paths ──────────────────────────────────────────────────────────

    var biosUri: String?
        get() = prefs.getString(KEY_BIOS_URI, null)
        set(v) { prefs.edit().putString(KEY_BIOS_URI, v).apply(); writeIni() }

    var soundBiosUri: String?
        get() = prefs.getString(KEY_SOUND_BIOS_URI, null)
        set(v) { prefs.edit().putString(KEY_SOUND_BIOS_URI, v).apply(); writeIni() }

    val hasBios: Boolean get() = biosUri != null
    val hasSoundBios: Boolean get() = soundBiosUri != null

    // ─── Display settings ────────────────────────────────────────────────────

    var correctAspectRatio: Boolean
        get() = prefs.getBoolean(KEY_ASPECT_RATIO, true)
        set(v) { prefs.edit().putBoolean(KEY_ASPECT_RATIO, v).apply(); writeIni() }

    var cropOverscan: Boolean
        get() = prefs.getBoolean(KEY_CROP_OVERSCAN, true)
        set(v) { prefs.edit().putBoolean(KEY_CROP_OVERSCAN, v).apply(); writeIni() }

    var antialias: Boolean
        get() = prefs.getBoolean(KEY_ANTIALIAS, true)
        set(v) { prefs.edit().putBoolean(KEY_ANTIALIAS, v).apply(); writeIni() }

    // ─── Audio settings ──────────────────────────────────────────────────────

    var runInBackground: Boolean
        get() = prefs.getBoolean(KEY_BG_AUDIO, true)
        set(v) { prefs.edit().putBoolean(KEY_BG_AUDIO, v).apply(); writeIni() }

    // ─── Controls settings ───────────────────────────────────────────────────

    /** Gamepad opacity: 0–100 (displayed as %) */
    var gamepadOpacity: Int
        get() = prefs.getInt(KEY_GAMEPAD_OPACITY, 70)
        set(v) = prefs.edit().putInt(KEY_GAMEPAD_OPACITY, v).apply()

    var showGamepad: Boolean
        get() = prefs.getBoolean(KEY_GAMEPAD_SHOW, true)
        set(v) = prefs.edit().putBoolean(KEY_GAMEPAD_SHOW, v).apply()

    var hapticsEnabled: Boolean
        get() = prefs.getBoolean(KEY_HAPTICS, true)
        set(v) = prefs.edit().putBoolean(KEY_HAPTICS, v).apply()

    // ─── File format settings ────────────────────────────────────────────────

    var screenshotFormat: String
        get() = prefs.getString(KEY_SCREENSHOT_FMT, "png") ?: "png"
        set(v) { prefs.edit().putString(KEY_SCREENSHOT_FMT, v).apply(); writeIni() }

    var printerFormat: String
        get() = prefs.getString(KEY_PRINTER_FMT, "png") ?: "png"
        set(v) { prefs.edit().putString(KEY_PRINTER_FMT, v).apply(); writeIni() }

    // ─── Sort order ──────────────────────────────────────────────────────────

    var sortOrder: String
        get() = prefs.getString(KEY_SORT_ORDER, SORT_NAME) ?: SORT_NAME
        set(v) = prefs.edit().putString(KEY_SORT_ORDER, v).apply()

    // ─── ROM library ─────────────────────────────────────────────────────────

    /** Returns the full ROM list, sorted according to current sort order. */
    fun getRomList(): List<RomEntry> {
        val json = prefs.getString(KEY_ROMS, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            RomEntry(
                uriString    = obj.getString("uri"),
                displayName  = obj.getString("name"),
                fileSizeBytes = obj.getLong("size"),
                lastPlayedMs  = obj.getLong("lastPlayed")
            )
        }
        return when (sortOrder) {
            SORT_LAST_PLAY -> list.sortedByDescending { it.lastPlayedMs }
            SORT_ADDED     -> list  // Insertion order = added order
            else           -> list.sortedBy { it.displayName.lowercase() }
        }
    }

    /** Adds a ROM to the library. Returns false if it's already in the list. */
    fun addRom(entry: RomEntry): Boolean {
        val list = getRomList().toMutableList()
        if (list.any { it.uriString == entry.uriString }) return false
        list.add(entry)
        saveRomList(list)
        return true
    }

    /** Removes a ROM from the library by URI. */
    fun removeRom(uriString: String) {
        val list = getRomList().toMutableList()
        list.removeAll { it.uriString == uriString }
        saveRomList(list)
    }

    /** Updates the last-played timestamp for a ROM. */
    fun markRomPlayed(uriString: String) {
        val list = getRomList().toMutableList()
        val idx = list.indexOfFirst { it.uriString == uriString }
        if (idx >= 0) {
            list[idx] = list[idx].copy(lastPlayedMs = System.currentTimeMillis())
            saveRomList(list)
        }
    }

    private fun saveRomList(list: List<RomEntry>) {
        val array = JSONArray()
        list.forEach { entry ->
            val obj = JSONObject()
            obj.put("uri", entry.uriString)
            obj.put("name", entry.displayName)
            obj.put("size", entry.fileSizeBytes)
            obj.put("lastPlayed", entry.lastPlayedMs)
            array.put(obj)
        }
        prefs.edit().putString(KEY_ROMS, array.toString()).apply()
    }

    // ─── INI file writer ─────────────────────────────────────────────────────

    /**
     * Writes loopymse.ini to internal storage so the C++ core reads it.
     * Called whenever any setting that the C++ side reads is changed.
     * BIOS paths are written as absolute paths (after being copied to internal storage).
     */
    fun writeIni() {
        val filesDir = context.filesDir
        val biosPath = File(filesDir, "bios.bin").absolutePath
        val soundBiosPath = File(filesDir, "soundbios.bin").absolutePath

        val ini = buildString {
            appendLine("[emulator]")
            appendLine("bios=$biosPath")
            appendLine("sound_bios=$soundBiosPath")
            appendLine("run_in_background=${if (runInBackground) "true" else "false"}")
            appendLine("correct_aspect_ratio=${if (correctAspectRatio) "true" else "false"}")
            appendLine("crop_overscan=${if (cropOverscan) "true" else "false"}")
            appendLine("antialias=${if (antialias) "true" else "false"}")
            appendLine("start_in_fullscreen=true")
            appendLine("int_scale=4")
            appendLine("screenshot_image_type=$screenshotFormat")
            appendLine()
            appendLine("[printer]")
            appendLine("image_type=$printerFormat")
            appendLine("correct_aspect_ratio=true")
            appendLine()
            // Default keyboard map (not used on Android but kept for C++ compat)
            appendLine("[keyboard-map]")
            appendLine("pad_up=up")
            appendLine("pad_down=down")
            appendLine("pad_left=left")
            appendLine("pad_right=right")
            appendLine("pad_start=return")
            appendLine("pad_a=z")
            appendLine("pad_b=x")
            appendLine("pad_c=c")
            appendLine("pad_d=v")
            appendLine("pad_l1=q")
            appendLine("pad_r1=w")
        }

        File(filesDir, "loopymse.ini").writeText(ini)
    }
}
