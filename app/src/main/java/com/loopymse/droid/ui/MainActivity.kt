package com.loopymse.droid.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.widget.addTextChangedListener
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.loopymse.droid.R
import com.loopymse.droid.databinding.ActivityMainBinding
import com.loopymse.droid.emulator.EmulatorActivity
import com.loopymse.droid.util.PreferencesManager

/**
 * MainActivity — the ROM library screen.
 *
 * Shows a clean dark list of all added Loopy ROMs.
 * On first launch, routes to BiosSetupActivity.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: RomAdapter
    private var fullRomList: List<RomEntry> = emptyList()

    // ─── File picker launchers ────────────────────────────────────────────────

    /** Launches the system file picker to select a ROM file */
    private val romPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { handleRomSelected(it) }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full edge-to-edge dark UI
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = PreferencesManager(this)

        // Route to BIOS setup if this is first launch or BIOS not configured
        if (!prefs.isSetupDone || !prefs.hasBios) {
            startActivity(Intent(this, BiosSetupActivity::class.java))
            // Don't finish — user can press back to return to empty library
        }

        setupRecyclerView()
        setupSearchBar()
        setupButtons()
        refreshRomList()
    }

    override fun onResume() {
        super.onResume()
        // Re-check BIOS state (user might have just returned from BiosSetupActivity)
        updateBiosWarning()
        // Refresh list (last-played time may have updated)
        refreshRomList()
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupRecyclerView() {
        adapter = RomAdapter(
            onPlay      = { entry -> launchRom(entry) },
            onLongPress = { entry -> showRomContextMenu(entry) }
        )
        binding.recyclerRoms.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            this.adapter  = this@MainActivity.adapter
            itemAnimator  = null // Cleaner feel for a list this small
        }
    }

    private fun setupSearchBar() {
        // When the search bar text changes, filter the list
        binding.searchBar.editText?.addTextChangedListener { editable ->
            val query = editable?.toString() ?: ""
            adapter.filter(query, fullRomList)
        }
    }

    private fun setupButtons() {
        // + FAB: open file picker to add a ROM
        binding.fabAddRom.setOnClickListener {
            romPickerLauncher.launch(
                // Accept any binary file — Loopy ROMs are .bin, but users may have .rom etc.
                arrayOf("application/octet-stream", "*/*")
            )
        }

        // Sort button
        binding.btnSort.setOnClickListener { view ->
            showSortMenu(view)
        }

        // Settings button
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // BIOS warning banner
        binding.biosWarningBanner.setOnClickListener {
            startActivity(Intent(this, BiosSetupActivity::class.java))
        }
    }

    // ─── ROM management ──────────────────────────────────────────────────────

    private fun refreshRomList() {
        fullRomList = prefs.getRomList()
        adapter.submitList(fullRomList.toList())

        // Show/hide empty state
        val isEmpty = fullRomList.isEmpty()
        binding.recyclerRoms.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyState.visibility   = if (isEmpty) View.VISIBLE else View.GONE
    }

    private fun handleRomSelected(uri: Uri) {
        // Persist URI permission so we can re-read this file across app restarts
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        // Read filename and size from the content resolver
        var displayName = "Unknown ROM"
        var fileSize    = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) displayName = cursor.getString(nameIdx)
                if (sizeIdx >= 0) fileSize    = cursor.getLong(sizeIdx)
            }
        }

        // Strip extension for display name
        val cleanName = displayName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')

        val entry = RomEntry(
            uriString     = uri.toString(),
            displayName   = cleanName,
            fileSizeBytes = fileSize,
            lastPlayedMs  = 0L
        )

        if (!prefs.addRom(entry)) {
            // Already in library
            com.google.android.material.snackbar.Snackbar
                .make(binding.root, "Already in library", com.google.android.material.snackbar.Snackbar.LENGTH_SHORT)
                .show()
        }

        refreshRomList()
    }

    private fun launchRom(entry: RomEntry) {
        if (!prefs.hasBios) {
            MaterialAlertDialogBuilder(this)
                .setTitle("BIOS Required")
                .setMessage(getString(R.string.error_bios_required))
                .setPositiveButton("Set Up BIOS") { _, _ ->
                    startActivity(Intent(this, BiosSetupActivity::class.java))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }

        prefs.markRomPlayed(entry.uriString)
        refreshRomList()

        val intent = Intent(this, EmulatorActivity::class.java).apply {
            putExtra(EmulatorActivity.EXTRA_ROM_URI, entry.uriString)
            putExtra(EmulatorActivity.EXTRA_ROM_NAME, entry.displayName)
        }
        startActivity(intent)
    }

    private fun showRomContextMenu(entry: RomEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(entry.displayName)
            .setItems(arrayOf("Play", "Remove from library")) { _, which ->
                when (which) {
                    0 -> launchRom(entry)
                    1 -> {
                        prefs.removeRom(entry.uriString)
                        refreshRomList()
                    }
                }
            }
            .show()
    }

    private fun showSortMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.apply {
            add(0, 0, 0, "Sort by name").isChecked = prefs.sortOrder == PreferencesManager.SORT_NAME
            add(0, 1, 1, "Recently played").isChecked = prefs.sortOrder == PreferencesManager.SORT_LAST_PLAY
            add(0, 2, 2, "Recently added").isChecked = prefs.sortOrder == PreferencesManager.SORT_ADDED
        }
        popup.setOnMenuItemClickListener { item ->
            prefs.sortOrder = when (item.itemId) {
                0 -> PreferencesManager.SORT_NAME
                1 -> PreferencesManager.SORT_LAST_PLAY
                else -> PreferencesManager.SORT_ADDED
            }
            refreshRomList()
            true
        }
        popup.show()
    }

    // ─── BIOS warning ─────────────────────────────────────────────────────────

    private fun updateBiosWarning() {
        binding.biosWarningBanner.visibility =
            if (prefs.hasBios) View.GONE else View.VISIBLE
    }

    // ─── System bars ──────────────────────────────────────────────────────────

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
