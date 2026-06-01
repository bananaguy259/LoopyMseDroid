package com.loopymse.droid.emulator

/**
 * EmulatorBridge — JNI interface between Kotlin and the C++ emulator.
 *
 * All native functions are implemented in android_bridge.cpp.
 *
 * Initialization: main.cpp handles BIOS/ROM loading via getArguments() paths.
 * These JNI functions operate on the live emulator after SDL_main() starts.
 */
object EmulatorBridge {
    // Library is loaded by SDLActivity.onCreate() before any calls are made.

    // ─── Setup ───────────────────────────────────────────────────────────────

    /** Pass the app's internal storage path so C++ can build file paths. */
    external fun setInternalStoragePath(path: String)

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /** Tear down emulation cleanly (SRAM auto-saved by cart.cpp). */
    external fun shutdown()

    /** Reset the virtual console (equivalent to F12 on desktop). */
    external fun reset()

    // ─── Emulation control ───────────────────────────────────────────────────

    /** Pause / resume emulation and audio. */
    external fun setPaused(paused: Boolean)

    /** Mute / unmute audio without pausing emulation. */
    external fun setMuted(muted: Boolean)

    // ─── Input injection ─────────────────────────────────────────────────────

    /**
     * Inject a virtual pad button press/release.
     * buttonCode is a PadButton enum value (PAD_A=0x10, PAD_B=0x80, etc.)
     * These constants are mirrored in VirtualGamepadView.
     */
    external fun setButtonState(buttonCode: Int, pressed: Boolean)

    /** Inject relative mouse movement (Loopy mouse peripheral). */
    external fun moveMouse(dx: Int, dy: Int)

    /** Inject a mouse button state. buttonCode: 1=left, 3=right (SDL constants). */
    external fun setMouseButton(buttonCode: Int, pressed: Boolean)

    // ─── Screenshots ─────────────────────────────────────────────────────────

    /**
     * Save the current frame to a temp file.
     * Returns the absolute path of the saved file, or null on failure.
     * Kotlin then copies it to the gallery via MediaStore.
     */
    external fun takeScreenshot(): String?

    // ─── Settings ────────────────────────────────────────────────────────────

    /** Re-read loopymse.ini and apply display/audio settings to C++ side. */
    external fun reloadConfig()

    // ─── Query ───────────────────────────────────────────────────────────────

    /** True once main.cpp has successfully loaded a ROM and called System::initialize(). */
    external fun isRomLoaded(): Boolean
}
