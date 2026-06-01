/**
 * android_bridge.cpp
 *
 * JNI bridge between Kotlin (EmulatorBridge.kt) and the C++ emulator.
 *
 * Initialization flow (Phase 2 corrected):
 *   1. EmulatorActivity.prepareRomFile() → copies ROM to filesDir/current_rom.bin
 *   2. SDLActivity spawns the C++ SDL_main() thread
 *   3. SDL_main() (main.cpp) loads BIOS + ROM from --bios/--cart args,
 *      calls System::initialize(), then calls android_set_rom_loaded(true)
 *   4. From that point, the JNI functions here operate on the live emulator
 *
 * The JNI loadBios/loadRom functions from Phase 1 are removed — they were
 * never called from Kotlin and conflicted with main.cpp's own loading.
 */

#include <jni.h>
#include <string>
#include <fstream>
#include <filesystem>
#include <sys/stat.h>
#include <sys/types.h>
#include <android/log.h>

#include <core/system.h>
#include <core/config.h>
#include <input/input.h>
#include <sound/sound.h>
#include <video/video.h>
#include <log/log.h>

#include "ini_parser.h"

namespace fs = std::filesystem;

// ─── Shared state (accessed by main.cpp via android_bridge_internal.h) ───────

Config::SystemInfo g_config;
std::string        g_internal_storage_path;

static bool g_rom_loaded = false;
static bool g_is_paused  = false;

// ─── Internal helpers (called by main.cpp) ────────────────────────────────────

extern "C" bool android_is_paused()          { return g_is_paused; }
extern "C" Config::SystemInfo* android_get_config() { return &g_config; }
extern "C" void android_set_rom_loaded(bool v) { g_rom_loaded = v; }

// ─── JNI helpers ──────────────────────────────────────────────────────────────

static std::string from_jstring(JNIEnv* env, jstring js)
{
    if (!js) return "";
    const char* c = env->GetStringUTFChars(js, nullptr);
    std::string s(c);
    env->ReleaseStringUTFChars(js, c);
    return s;
}

// ─── Setup ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_setInternalStoragePath(
    JNIEnv* env, jobject, jstring path)
{
    g_internal_storage_path = from_jstring(env, path);
    Log::info("[Bridge] Internal storage: %s", g_internal_storage_path.c_str());
}

// ─── Lifecycle ────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_shutdown(
    JNIEnv*, jobject)
{
    if (g_rom_loaded)
    {
        System::shutdown(g_config);
        g_rom_loaded = false;
        Log::info("[Bridge] System shutdown (SRAM auto-saved by cart.cpp)");
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_reset(
    JNIEnv*, jobject)
{
    if (g_rom_loaded)
    {
        System::shutdown(g_config);
        System::initialize(g_config);
        Log::info("[Bridge] Console reset");
    }
}

// ─── Emulation control ────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_setPaused(
    JNIEnv*, jobject, jboolean paused)
{
    g_is_paused = (bool)paused;
    Sound::set_mute(paused);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_setMuted(
    JNIEnv*, jobject, jboolean muted)
{
    Sound::set_mute((bool)muted);
}

// ─── Input ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_setButtonState(
    JNIEnv*, jobject, jint buttonCode, jboolean pressed)
{
    Input::set_controller_state_raw(buttonCode, (bool)pressed);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_moveMouse(
    JNIEnv*, jobject, jint dx, jint dy)
{
    Input::move_mouse(dx, dy);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_setMouseButton(
    JNIEnv*, jobject, jint buttonCode, jboolean pressed)
{
    Input::set_mouse_button_state(buttonCode, (bool)pressed);
}

// ─── Screenshot ───────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_takeScreenshot(
    JNIEnv* env, jobject)
{
    if (!g_rom_loaded) return nullptr;

    const int  type = g_config.emulator.screenshot_image_type;
    std::string ext = (type == 3) ? "jpg" : (type == 1) ? "bmp" : "png";
    std::string path = g_internal_storage_path + "/temp/screenshot_tmp." + ext;

    Video::dump_current_frame(type, fs::path(path));

    return env->NewStringUTF(path.c_str());
}

// ─── Config reload ────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT void JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_reloadConfig(
    JNIEnv*, jobject)
{
    Options::Args args;
    IniParser::parse_config(g_internal_storage_path + "/loopymse.ini", args);
    g_config.emulator.screenshot_image_type = args.screenshot_image_type;
    g_config.emulator.printer_image_type    = args.printer_image_type;
    Log::info("[Bridge] Config reloaded");
}

// ─── Query ────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_loopymse_droid_emulator_EmulatorBridge_isRomLoaded(
    JNIEnv*, jobject)
{
    return g_rom_loaded ? JNI_TRUE : JNI_FALSE;
}
