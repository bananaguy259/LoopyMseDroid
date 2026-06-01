package com.loopymse.droid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.*
import com.loopymse.droid.R
import com.loopymse.droid.util.PreferencesManager

/**
 * SettingsActivity — wraps the PreferenceFragment in a plain Activity.
 * Uses AndroidX PreferenceFragmentCompat for a native-feel settings UI.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Simple container — the PreferenceFragment fills it
        val container = android.widget.FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(container)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var prefs: PreferencesManager

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences_settings, rootKey)
            prefs = PreferencesManager(requireContext())

            // ─── Wire up each preference to PreferencesManager ───────────────

            findPreference<SwitchPreferenceCompat>("correct_aspect_ratio")
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.correctAspectRatio = v as Boolean; true
                }

            findPreference<SwitchPreferenceCompat>("crop_overscan")
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.cropOverscan = v as Boolean; true
                }

            findPreference<SwitchPreferenceCompat>("antialias")
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.antialias = v as Boolean; true
                }

            findPreference<SwitchPreferenceCompat>("run_in_background")
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.runInBackground = v as Boolean; true
                }

            findPreference<SwitchPreferenceCompat>("show_gamepad")
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.showGamepad = v as Boolean; true
                }

            findPreference<SeekBarPreference>("gamepad_opacity")?.apply {
                value = prefs.gamepadOpacity
                setOnPreferenceChangeListener { _, v ->
                    prefs.gamepadOpacity = v as Int; true
                }
            }

            findPreference<SwitchPreferenceCompat>("haptics")
                ?.setOnPreferenceChangeListener { _, v ->
                    prefs.hapticsEnabled = v as Boolean; true
                }

            findPreference<ListPreference>("screenshot_format")?.apply {
                value = prefs.screenshotFormat
                setOnPreferenceChangeListener { _, v ->
                    prefs.screenshotFormat = v as String; true
                }
            }

            findPreference<ListPreference>("printer_format")?.apply {
                value = prefs.printerFormat
                setOnPreferenceChangeListener { _, v ->
                    prefs.printerFormat = v as String; true
                }
            }

            // BIOS setup → re-open BiosSetupActivity
            findPreference<Preference>("bios_setup")
                ?.setOnPreferenceClickListener {
                    startActivity(Intent(requireContext(), BiosSetupActivity::class.java))
                    true
                }

            // Source code → open GitHub in browser
            findPreference<Preference>("source_code")
                ?.setOnPreferenceClickListener {
                    val intent = Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/LoopyMSE/LoopyMSE"))
                    startActivity(intent)
                    true
                }

            // Licenses → show NOTICES.md content
            findPreference<Preference>("licenses")
                ?.setOnPreferenceClickListener {
                    showLicensesDialog()
                    true
                }
        }

        private fun showLicensesDialog() {
            // Read NOTICES.md from assets
            val text = try {
                requireContext().assets.open("NOTICES.md")
                    .bufferedReader().readText()
            } catch (e: Exception) {
                "License information unavailable."
            }

            val scroll = android.widget.ScrollView(requireContext())
            val tv = android.widget.TextView(requireContext()).apply {
                this.text = text
                textSize  = 11f
                setPadding(32, 24, 32, 24)
                setTextColor(0xFFAAAAAA.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
            }
            scroll.addView(tv)

            com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.settings_licenses))
                .setView(scroll)
                .setPositiveButton("Close", null)
                .show()
        }
    }
}
