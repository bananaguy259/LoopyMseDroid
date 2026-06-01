package com.loopymse.droid.emulator

import android.animation.ObjectAnimator
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.FileObserver
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.core.app.NotificationCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.documentfile.provider.DocumentFile
import com.loopymse.droid.R
import com.loopymse.droid.databinding.ActivityEmulatorBinding
import org.libsdl.app.SDLActivity
import com.loopymse.droid.ui.BiosSetupActivity
import com.loopymse.droid.ui.SettingsActivity
import com.loopymse.droid.util.PreferencesManager
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * EmulatorActivity — the game screen.
 *
 * Extends SDLActivity (SDL2's Android Activity base).
 * SDL creates its GL surface; we layer our overlay on top via addContentView.
 *
 * Phase 2 additions vs Phase 1:
 *  - FileObserver watches filesDir/temp/ for new printer output files
 *  - Touch-as-mouse: drag on the game area drives the Loopy mouse peripheral
 *  - SRAM auto-save whenever the app backgrounds
 */
class EmulatorActivity : SDLActivity(), InputManager.InputDeviceListener {

    companion object {
        const val EXTRA_ROM_URI  = "rom_uri"
        const val EXTRA_ROM_NAME = "rom_name"

        private const val NOTIF_CHANNEL_ID = "loopymse_saves"
        private const val NOTIF_SCREENSHOT = 1001
        private const val NOTIF_PRINTER    = 1002
    }

    private lateinit var binding: ActivityEmulatorBinding
    private lateinit var prefs: PreferencesManager
    private var isPaused    = false
    private var romName     = "Unknown Game"

    // ─── Printer file watcher ─────────────────────────────────────────────────
    // C++ writes printer output to filesDir/temp/. We watch for new files and
    // move them to the gallery automatically — zero C++ changes needed.
    private var printerObserver: FileObserver? = null

    // ─── Touch-as-mouse state ──────────────────────────────────────────────────
    // Tracks a single "mouse" pointer on the game area (not on any virtual button)
    private var mousePointerId   = -1
    private var mouseLastX       = 0f
    private var mouseLastY       = 0f

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = PreferencesManager(this)

        // Tell the C++ bridge where internal storage is — must happen before SDL_main runs
        EmulatorBridge.setInternalStoragePath(filesDir.absolutePath)

        // Write a fresh INI so C++ reads current settings
        prefs.writeIni()

        // Copy ROM from SAF URI to internal storage
        val romUri = intent.getStringExtra(EXTRA_ROM_URI)
        romName    = intent.getStringExtra(EXTRA_ROM_NAME) ?: "Unknown Game"
        if (romUri != null) prepareRomFile(romUri)

        super.onCreate(savedInstanceState)

        // Inflate our overlay layout and layer it on top of SDL's surface
        binding = ActivityEmulatorBinding.inflate(layoutInflater)
        addContentView(
            binding.root,
            android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        hideSystemBars()
        binding.virtualGamepad.configure(prefs)

        // Set up touch-as-mouse forwarding on the root view
        setupTouchMouseForwarding()

        // Physical controller detection
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(this, null)
        updateGamepadVisibility()

        createNotificationChannel()
        startPrinterWatcher()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        if (!isPaused) {
            EmulatorBridge.setPaused(false)
            EmulatorBridge.setMuted(false)
        }
        printerObserver?.startWatching()
    }

    override fun onPause() {
        super.onPause()
        // cart.cpp auto-saves SRAM every 60 frames and on System::shutdown().
        // No manual flush needed here.
        if (!prefs.runInBackground) {
            EmulatorBridge.setPaused(true)
            EmulatorBridge.setMuted(true)
        } else {
            // Still mute audio when backgrounded, even if emulation keeps running
            EmulatorBridge.setMuted(true)
        }
        printerObserver?.stopWatching()
    }

    override fun onDestroy() {
        super.onDestroy()
        printerObserver?.stopWatching()
        val inputManager = getSystemService(Context.INPUT_SERVICE) as InputManager
        inputManager.unregisterInputDeviceListener(this)
        EmulatorBridge.shutdown()
    }

    @Deprecated("Deprecated in API 33")
    override fun onBackPressed() {
        togglePauseMenu()
    }

    // ─── ROM preparation ──────────────────────────────────────────────────────

    private fun prepareRomFile(uriString: String) {
        try {
            val dest = File(filesDir, "current_rom.bin")
            contentResolver.openInputStream(Uri.parse(uriString))?.use { input ->
                FileOutputStream(dest).use { it2 -> input.copyTo(it2) }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── Touch-as-mouse ───────────────────────────────────────────────────────
    //
    // The Loopy had a mouse peripheral. On Android we map a finger drag on the
    // game area (not over any virtual button) to relative mouse movement.
    //
    // We intercept touches at the Activity level via dispatchTouchEvent.
    // VirtualGamepadView handles button zones; we handle everything else.

    private fun setupTouchMouseForwarding() {
        // Nothing to configure — logic is in dispatchTouchEvent below
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Let the normal view hierarchy handle it first
        // (VirtualGamepadView will consume button touches and return true)
        val handled = super.dispatchTouchEvent(event)

        // If the event was NOT consumed by any view (i.e. touched open game area),
        // treat it as mouse input
        if (!handled) {
            handleMouseTouch(event)
            return true
        }
        return handled
    }

    private fun handleMouseTouch(event: MotionEvent) {
        val idx = event.actionIndex
        val pid = event.getPointerId(idx)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                // Only track one mouse pointer at a time; first one wins
                if (mousePointerId == -1) {
                    mousePointerId = pid
                    mouseLastX     = event.getX(idx)
                    mouseLastY     = event.getY(idx)
                    EmulatorBridge.setMouseButton(1 /* SDL_BUTTON_LEFT */, true)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    if (event.getPointerId(i) == mousePointerId) {
                        val x = event.getX(i)
                        val y = event.getY(i)
                        // Scale raw pixel delta down — raw pixels are too fast
                        val dx = ((x - mouseLastX) * 0.5f).toInt()
                        val dy = ((y - mouseLastY) * 0.5f).toInt()
                        if (dx != 0 || dy != 0) {
                            EmulatorBridge.moveMouse(dx, -dy) // Y is inverted
                            mouseLastX = x
                            mouseLastY = y
                        }
                        break
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (pid == mousePointerId) {
                    mousePointerId = -1
                    EmulatorBridge.setMouseButton(1 /* SDL_BUTTON_LEFT */, false)
                }
            }
        }
    }

    // ─── Printer file watcher ─────────────────────────────────────────────────

    private fun startPrinterWatcher() {
        val tempDir = File(filesDir, "temp").also { it.mkdirs() }

        printerObserver = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: pass File directly
            object : FileObserver(tempDir, CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && looksLikePrinterFile(path)) {
                        handleNewPrinterFile(File(tempDir, path))
                    }
                }
            }
        } else {
            // Pre-API 29: pass String path
            @Suppress("DEPRECATION")
            object : FileObserver(tempDir.absolutePath, CREATE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && looksLikePrinterFile(path)) {
                        handleNewPrinterFile(File(tempDir, path))
                    }
                }
            }
        }
        printerObserver?.startWatching()
    }

    private fun looksLikePrinterFile(filename: String): Boolean {
        // printer.cpp names files "print_TIMESTAMP_N.ext"
        val lower = filename.lowercase()
        return lower.startsWith("print_") &&
               (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".bmp"))
    }

    private fun handleNewPrinterFile(file: File) {
        // Small delay to ensure C++ has finished writing the file
        android.os.Handler(mainLooper).postDelayed({
            saveImageToGallery(file, "printer", NOTIF_PRINTER,
                getString(R.string.print_saved))
        }, 300)
    }

    // ─── Pause menu ───────────────────────────────────────────────────────────

    private fun togglePauseMenu() {
        if (binding.pauseMenuContainer.visibility == View.VISIBLE) {
            dismissPauseMenu()
        } else {
            showPauseMenu()
        }
    }

    private fun showPauseMenu() {
        isPaused = true
        EmulatorBridge.setPaused(true)

        val menuView = layoutInflater.inflate(
            R.layout.fragment_pause_menu, binding.pauseMenuContainer, false)
        binding.pauseMenuContainer.addView(menuView)
        binding.pauseMenuContainer.visibility = View.VISIBLE

        menuView.findViewById<View>(R.id.menu_resume).setOnClickListener {
            dismissPauseMenu()
        }
        menuView.findViewById<View>(R.id.menu_reset).setOnClickListener {
            dismissPauseMenu()
            EmulatorBridge.reset()
        }
        menuView.findViewById<View>(R.id.menu_screenshot).setOnClickListener {
            dismissPauseMenu()
            takeScreenshot()
        }
        menuView.findViewById<View>(R.id.menu_load_rom).setOnClickListener {
            finish()
        }
        menuView.findViewById<View>(R.id.menu_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        menuView.findViewById<View>(R.id.menu_quit).setOnClickListener {
            EmulatorBridge.shutdown()
            finish()
        }
    }

    private fun dismissPauseMenu() {
        binding.pauseMenuContainer.removeAllViews()
        binding.pauseMenuContainer.visibility = View.GONE
        isPaused = false
        EmulatorBridge.setPaused(false)
    }

    // ─── Screenshot ───────────────────────────────────────────────────────────

    fun takeScreenshot() {
        // White flash
        binding.screenshotFlash.visibility = View.VISIBLE
        binding.screenshotFlash.alpha = 0.8f
        ObjectAnimator.ofFloat(binding.screenshotFlash, "alpha", 0.8f, 0f).apply {
            duration = 250
            start()
        }
        binding.screenshotFlash.postDelayed({
            binding.screenshotFlash.visibility = View.GONE
        }, 280)

        val tempPath = EmulatorBridge.takeScreenshot()
        if (tempPath != null) {
            saveImageToGallery(File(tempPath), "screenshot", NOTIF_SCREENSHOT,
                getString(R.string.screenshot_saved))
        }
    }

    // ─── Gallery save ─────────────────────────────────────────────────────────

    private fun saveImageToGallery(
        sourceFile: File,
        subfolder: String,
        notifId: Int,
        notifText: String
    ) {
        if (!sourceFile.exists()) return

        val ext      = sourceFile.extension.lowercase()
        val mime     = when (ext) { "jpg", "jpeg" -> "image/jpeg"; "bmp" -> "image/bmp"; else -> "image/png" }
        val stamp    = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "loopy_${stamp}.$ext"
        val relPath  = "Pictures/Loopy-MseDroid" + if (subfolder == "printer") "/Printer" else ""

        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, mime)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, relPath)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            val uri = contentResolver.insert(collection, values) ?: return
            contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }
            showSaveNotification(notifId, notifText, uri)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            sourceFile.delete()
        }
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID, "Saved Files", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Screenshots and printer output"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun showSaveNotification(id: Int, text: String, imageUri: Uri) {
        val notif = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cartridge)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(id, notif)
    }

    // ─── Physical controller detection ───────────────────────────────────────

    override fun onInputDeviceAdded(deviceId: Int)    { updateGamepadVisibility() }
    override fun onInputDeviceRemoved(deviceId: Int)  { updateGamepadVisibility() }
    override fun onInputDeviceChanged(deviceId: Int)  { updateGamepadVisibility() }

    private fun updateGamepadVisibility() {
        val hasPhysical = InputDevice.getDeviceIds().any { id ->
            val d = InputDevice.getDevice(id)
            d != null && d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD
        }
        binding.virtualGamepad.onPhysicalControllerChanged(hasPhysical, prefs.showGamepad)
    }

    // ─── Fullscreen ───────────────────────────────────────────────────────────

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ─── SDLActivity overrides ────────────────────────────────────────────────

    override fun getMainSharedObject(): String = "libLoopyMseDroid.so"

    override fun getArguments(): Array<String> {
        val dir = filesDir.absolutePath
        return arrayOf(
            "loopymse",
            "--config",     "$dir/loopymse.ini",
            "--bios",       "$dir/bios.bin",
            "--sound_bios", "$dir/soundbios.bin",
            "--cart",       "$dir/current_rom.bin"
        )
    }
}
