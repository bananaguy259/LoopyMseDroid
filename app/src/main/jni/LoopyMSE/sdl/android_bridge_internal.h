#pragma once
/**
 * android_bridge_internal.h
 *
 * Internal C linkage declarations used by main.cpp on Android.
 * Not exposed to JNI — these are pure C++ internal calls between
 * android_bridge.cpp and the SDL main loop.
 */

#include <core/config.h>
#include <string>

// Internal storage path set by EmulatorActivity.getArguments() → main() args
extern std::string g_internal_storage_path;

#ifdef __cplusplus
extern "C" {
#endif

/** Returns true when Kotlin has requested emulation to pause. */
bool android_is_paused();

/** Returns the shared SystemInfo config — BIOS/ROM/emulator settings live here. */
Config::SystemInfo* android_get_config();

/** Called by main.cpp after System::initialize() succeeds to sync the loaded flag. */
void android_set_rom_loaded(bool loaded);

#ifdef __cplusplus
}
#endif
