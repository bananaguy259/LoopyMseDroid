package com.loopymse.droid.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.loopymse.droid.databinding.ActivityBiosSetupBinding
import com.loopymse.droid.util.PreferencesManager
import java.io.File
import java.io.FileOutputStream

/**
 * BiosSetupActivity — first-run (and re-runnable) BIOS file picker.
 *
 * Copies the selected BIOS files to internal storage so the C++ core
 * can always access them via a simple file path (SAF URIs don't work
 * directly in C++).
 */
class BiosSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBiosSetupBinding
    private lateinit var prefs: PreferencesManager

    private var mainBiosUri: Uri? = null
    private var soundBiosUri: Uri? = null

    // ─── File picker launchers ────────────────────────────────────────────────

    private val biosPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleBiosSelected(it, isSound = false) }
    }

    private val soundBiosPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleBiosSelected(it, isSound = true) }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBiosSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        // Pre-populate if BIOS was already configured (re-visiting from Settings)
        if (prefs.hasBios) {
            binding.tvBiosStatus.text = getString(com.loopymse.droid.R.string.bios_selected)
        }
        if (prefs.hasSoundBios) {
            binding.tvSoundBiosStatus.text = getString(com.loopymse.droid.R.string.bios_selected)
        }

        binding.btnSelectBios.setOnClickListener {
            biosPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnSelectSoundBios.setOnClickListener {
            soundBiosPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnContinue.setOnClickListener {
            handleContinue()
        }
    }

    // ─── BIOS handling ────────────────────────────────────────────────────────

    private fun handleBiosSelected(uri: Uri, isSound: Boolean) {
        // Copy to internal storage — C++ can't read SAF URIs directly
        val destFile = File(filesDir, if (isSound) "soundbios.bin" else "bios.bin")
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            showError("Failed to copy BIOS file: ${e.message}")
            return
        }

        // Record URI (for display) and write INI
        if (isSound) {
            soundBiosUri = uri
            prefs.soundBiosUri = uri.toString()
            binding.tvSoundBiosStatus.text = getString(com.loopymse.droid.R.string.bios_selected)
        } else {
            mainBiosUri = uri
            prefs.biosUri = uri.toString()
            binding.tvBiosStatus.text = getString(com.loopymse.droid.R.string.bios_selected)
        }

        binding.tvBiosError.visibility = View.GONE
    }

    private fun handleContinue() {
        // Main BIOS is required; sound BIOS is optional
        if (!prefs.hasBios) {
            binding.tvBiosError.visibility = View.VISIBLE
            return
        }

        // Write the INI file now that we have BIOS paths
        prefs.writeIni()
        prefs.isSetupDone = true

        // Return to MainActivity
        startActivity(Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
        finish()
    }

    private fun showError(msg: String) {
        binding.tvBiosError.text = msg
        binding.tvBiosError.visibility = View.VISIBLE
    }
}
