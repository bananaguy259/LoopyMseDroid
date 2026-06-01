/**
 * main.cpp (Android port)
 *
 * Modified from upstream src/sdl/main.cpp for Android.
 *
 * Changes from upstream:
 *  1. PREFS_PATH and RESOURCE_PATH come from Android internal storage (set via JNI)
 *     instead of being compile-time filesystem paths.
 *  2. SDL_DROPFILE handling removed — ROM loading is done via EmulatorBridge.loadRom()
 *     from Kotlin before SDL starts.
 *  3. INI parsing uses IniParser:: instead of Options:: (Boost removed).
 *  4. g_is_paused checked in the run loop via android_is_paused().
 *  5. Printer output triggers a JNI callback to Kotlin for gallery saving.
 *
 * Everything else (run loop, frame timing, input routing, audio, video) is
 * identical to the desktop version.
 */

#include <SDL2/SDL.h>
#include <common/bswp.h>
#include <core/config.h>
#include <core/system.h>
#include <input/input.h>
#include <log/log.h>
#include <sound/sound.h>
#include <video/video.h>

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>

// Android-specific headers
#ifdef LOOPYMSE_ANDROID
#include <android/log.h>
#include "android_bridge_internal.h"   // Declares android_is_paused(), android_get_config()
#include "../android/ini_parser.h"
#else
#include "options.h"
#endif

#include "config.h"      // PROJECT_VERSION, PROJECT_DESCRIPTION
#include "imgwriter.h"

#define PRESCALE_FACTOR     4
#define MAX_WINDOW_INT_SCALE 10

namespace imagew = SDL::ImageWriter;

namespace SDL
{

using Video::DISPLAY_HEIGHT;
using Video::DISPLAY_WIDTH;
static constexpr int   FRAME_WIDTH  = 280;
static constexpr int   FRAME_HEIGHT = 240;
static constexpr float ASPECT_CORRECT_SCALE_X = (320.0f / FRAME_WIDTH);

struct Screen
{
    SDL_Renderer* renderer        = nullptr;
    SDL_Window*   window          = nullptr;
    SDL_Texture*  framebuffer     = nullptr;
    SDL_Texture*  prescaled       = nullptr;
    int  visible_scanlines        = DISPLAY_HEIGHT;
    int  window_int_scale         = 1;
    int  prescale                 = 1;
    bool correct_aspect_ratio     = true;
    bool crop_overscan            = true;
    bool antialias                = true;

    bool is_fullscreen()
    {
        return (SDL_GetWindowFlags(window) & SDL_WINDOW_FULLSCREEN_DESKTOP) > 0;
    }
};

static Screen screen;
static SDL_GameController* controller = nullptr;

void shutdown()
{
    if (screen.prescaled)  SDL_DestroyTexture(screen.prescaled);
    if (screen.framebuffer) SDL_DestroyTexture(screen.framebuffer);
    if (screen.renderer)   SDL_DestroyRenderer(screen.renderer);
    if (screen.window)     SDL_DestroyWindow(screen.window);
    SDL_Quit();
}

void open_first_controller()
{
    for (int i = 0; i < SDL_NumJoysticks(); i++)
    {
        if (SDL_IsGameController(i))
        {
            Log::info("Connected to game controller %s", SDL_GameControllerNameForIndex(i));
            controller = SDL_GameControllerOpen(i);
            return;
        }
    }
    controller = nullptr;
}

void update(uint16_t* display_output, int visible_scanlines, uint16_t background_color)
{
    if (visible_scanlines != screen.visible_scanlines)
        screen.visible_scanlines = visible_scanlines;

    void* pixels;
    int   pitch;

    if (SDL_LockTexture(screen.framebuffer, nullptr, &pixels, &pitch) == 0)
    {
        memcpy(pixels, display_output, sizeof(uint16_t) * DISPLAY_WIDTH * DISPLAY_HEIGHT);
        SDL_UnlockTexture(screen.framebuffer);
    }

    if (screen.prescaled)
    {
        SDL_SetRenderTarget(screen.renderer, screen.prescaled);
        SDL_RenderClear(screen.renderer);
        SDL_RenderCopy(screen.renderer, screen.framebuffer, nullptr, nullptr);
    }

    SDL_SetRenderTarget(screen.renderer, nullptr);

    // Background color fill
    uint8_t r = ((background_color >> 10) & 31) * 255 / 31;
    uint8_t g = ((background_color >>  5) & 31) * 255 / 31;
    uint8_t b = ( background_color        & 31) * 255 / 31;
    SDL_SetRenderDrawColor(screen.renderer, r, g, b, 0xFF);
    SDL_RenderClear(screen.renderer);

    SDL_Rect src  = {0, 0, DISPLAY_WIDTH  * screen.prescale, visible_scanlines * screen.prescale};
    SDL_Rect frame = {0, 0, FRAME_WIDTH   * screen.prescale, FRAME_HEIGHT      * screen.prescale};
    if (screen.crop_overscan) frame = src;

    SDL_Rect dest = {0};
    SDL_GetRendererOutputSize(screen.renderer, &dest.w, &dest.h);

    float scale_x = (float)dest.w / frame.w;
    float scale_y = (float)dest.h / frame.h;
    float scale   = SDL_min(scale_x, scale_y);
    if (!screen.antialias && !screen.correct_aspect_ratio)
        scale = SDL_floorf(scale);
    scale_x = scale_y = scale;
    if (screen.correct_aspect_ratio)
        scale_x *= ASPECT_CORRECT_SCALE_X;

    float w = scale_x * src.w;
    float h = scale_y * src.h;
    dest.x = (int)((dest.w - w) / 2);
    dest.y = (int)((dest.h - h) / 2);
    dest.w = (int)w;
    dest.h = (int)h;

    SDL_RenderCopy(
        screen.renderer,
        screen.prescaled ? screen.prescaled : screen.framebuffer,
        &src, &dest
    );
    SDL_RenderPresent(screen.renderer);
}

void initialize(Options::Args& args)
{
    SDL_SetMainReady();

    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_GAMECONTROLLER | SDL_INIT_AUDIO) < 0)
    {
        Log::error("Failed to initialize SDL2: %s", SDL_GetError());
        return;
    }

    SDL_SetHint(SDL_HINT_RENDER_VSYNC, "1");
    SDL_SetHint(SDL_HINT_FRAMEBUFFER_ACCELERATION, "1");
    // On Android, use OpenGL ES renderer
    SDL_SetHint(SDL_HINT_RENDER_DRIVER, "opengles2");

    screen.correct_aspect_ratio = args.correct_aspect_ratio;
    screen.crop_overscan         = args.crop_overscan;
    screen.antialias             = args.antialias;
    screen.prescale              = args.antialias ? PRESCALE_FACTOR : 1;

    // On Android: fullscreen = the whole display
    screen.window = SDL_CreateWindow(
        PROJECT_DESCRIPTION,
        SDL_WINDOWPOS_UNDEFINED, SDL_WINDOWPOS_UNDEFINED,
        0, 0,
        SDL_WINDOW_FULLSCREEN_DESKTOP | SDL_WINDOW_OPENGL
    );

    screen.renderer = SDL_CreateRenderer(
        screen.window, -1,
        SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC
    );

    screen.framebuffer = SDL_CreateTexture(
        screen.renderer,
        SDL_PIXELFORMAT_ARGB1555,
        SDL_TEXTUREACCESS_STREAMING,
        DISPLAY_WIDTH, DISPLAY_HEIGHT
    );
    SDL_SetTextureBlendMode(screen.framebuffer, SDL_BLENDMODE_BLEND);
    SDL_SetTextureScaleMode(screen.framebuffer, SDL_ScaleModeNearest);

    if (screen.prescale > 1)
    {
        screen.prescaled = SDL_CreateTexture(
            screen.renderer,
            SDL_PIXELFORMAT_ARGB1555,
            SDL_TEXTUREACCESS_TARGET,
            DISPLAY_WIDTH  * screen.prescale,
            DISPLAY_HEIGHT * screen.prescale
        );
        SDL_SetTextureBlendMode(screen.prescaled, SDL_BLENDMODE_BLEND);
        SDL_SetTextureScaleMode(screen.prescaled, SDL_ScaleModeBest);
    }

    open_first_controller();

    // Mouse bindings (used by Loopy mouse peripheral via touch emulation)
    Input::add_mouse_binding(SDL_BUTTON_LEFT,  Input::MouseButton::MOUSE_L);
    Input::add_mouse_binding(SDL_BUTTON_RIGHT, Input::MouseButton::MOUSE_R);
}

} // namespace SDL

// ─── Main ─────────────────────────────────────────────────────────────────────

// ─── Entry point ──────────────────────────────────────────────────────────────
// On Android, SDL2's JNI glue (SDL_android.c) dlsym-searches for "SDL_main"
// in the loaded .so. We export it explicitly with extern "C" linkage.
// On desktop, the function is a plain main() with no name mangling needed.
#ifdef LOOPYMSE_ANDROID
extern "C" int SDL_main(int argc, char** argv)
#else
int main(int argc, char** argv)
#endif
{
    bool has_quit = false;

    // ── Config ────────────────────────────────────────────────────────────────
    // On Android, the config is pre-loaded by EmulatorBridge before SDL starts.
    // We get the config pointer from the bridge.
#ifdef LOOPYMSE_ANDROID
    Config::SystemInfo* config_ptr = android_get_config();
    Config::SystemInfo& config     = *config_ptr;
#else
    Config::SystemInfo config;
#endif

    Options::Args args;

    // ── Parse arguments ────────────────────────────────────────────────────────
    // On Android: --config, --bios, --cart are passed from EmulatorActivity.getArguments()
    std::string config_path;
    std::string bios_path;
    std::string sound_bios_path;
    std::string cart_path;

    for (int i = 1; i < argc; i++)
    {
        std::string a(argv[i]);
        if      (a == "--config"     && i + 1 < argc) config_path     = argv[++i];
        else if (a == "--bios"       && i + 1 < argc) bios_path       = argv[++i];
        else if (a == "--sound_bios" && i + 1 < argc) sound_bios_path = argv[++i];
        else if (a == "--cart"       && i + 1 < argc) cart_path       = argv[++i];
    }

    // ── Load INI config ────────────────────────────────────────────────────────
#ifdef LOOPYMSE_ANDROID
    if (!config_path.empty())
        IniParser::parse_config(config_path, args);
#else
    Options::parse_config(config_path, args);
    Options::parse_commandline(argc, argv, args);
#endif

    config.emulator.screenshot_image_type    = args.screenshot_image_type;
    config.emulator.printer_image_type       = args.printer_image_type;
    config.emulator.printer_correct_aspect_ratio = args.printer_correct_aspect_ratio;

    Log::set_level(args.verbose ? Log::VERBOSE : Log::INFO);

    // ── Load BIOS from path argument ─────────────────────────────────────────
    // On Android: --bios points to filesDir/bios.bin (copied by BiosSetupActivity)
    // On desktop: --bios or config file path
    if (!bios_path.empty())
    {
        std::ifstream bios_file(bios_path, std::ios::binary);
        if (bios_file.is_open())
        {
            config.bios_rom.assign(std::istreambuf_iterator<char>(bios_file), {});
            Log::info("BIOS loaded from %s (%zu bytes)",
                      bios_path.c_str(), config.bios_rom.size());
        }
        else
        {
            Log::error("Cannot open BIOS file: %s", bios_path.c_str());
        }
    }

    if (config.bios_rom.empty())
    {
#ifdef LOOPYMSE_ANDROID
        Log::error("BIOS not loaded — check BIOS setup in the app settings.");
        // Don't exit; let the run loop spin with no ROM loaded (graceful idle)
#else
        Log::error("BIOS not loaded. Provide via --bios argument.");
        return 1;
#endif
    }

    // ── Load Sound BIOS if provided ───────────────────────────────────────────
    // Prefer --sound_bios arg; fall back to path from INI
    const std::string& sb_path = !sound_bios_path.empty() ? sound_bios_path : args.sound_bios;
    if (!sb_path.empty() && config.sound_rom.empty())
    {
        std::ifstream sf(sb_path, std::ios::binary);
        if (sf.is_open())
        {
            config.sound_rom.assign(std::istreambuf_iterator<char>(sf), {});
            Log::info("Sound BIOS loaded (%zu bytes)", config.sound_rom.size());
        }
        else
        {
            Log::warn("Sound BIOS not found at %s — continuing without audio", sb_path.c_str());
        }
    }

    // ── Load ROM and initialize emulation ─────────────────────────────────────
    // On Android: --cart points to filesDir/current_rom.bin
    // On desktop: --cart from command line
    if (!cart_path.empty() && !config.cart.is_loaded())
    {
        std::ifstream cart_file(cart_path, std::ios::binary);
        if (cart_file.is_open())
        {
            config.cart.rom_path = cart_path;
            config.cart.rom.assign(std::istreambuf_iterator<char>(cart_file), {});

#ifdef LOOPYMSE_ANDROID
            // Derive SRAM save path: filesDir/saves/<romname>.sav
            std::string saves_dir = g_internal_storage_path + "/saves/";
            ::mkdir(saves_dir.c_str(), 0755);
            std::string rom_filename = cart_path.substr(cart_path.find_last_of("/\\") + 1);
            std::string save_name = rom_filename.substr(0, rom_filename.find('.')) + ".sav";
            config.cart.sram_file_path = saves_dir + save_name;

            // Load existing SRAM if present
            std::ifstream sram_file(config.cart.sram_file_path, std::ios::binary);
            if (sram_file.is_open())
                config.cart.sram.assign(std::istreambuf_iterator<char>(sram_file), {});

            // Set screenshot/printer output to temp dir for gallery saving
            config.emulator.image_save_directory =
                fs::path(g_internal_storage_path) / "temp";
            fs::create_directories(config.emulator.image_save_directory);
#endif

            // Parse SRAM size from cart header
            if (config.cart.rom.size() >= 0x18)
            {
                uint32_t sram_start, sram_end;
                memcpy(&sram_start, config.cart.rom.data() + 0x10, 4);
                memcpy(&sram_end,   config.cart.rom.data() + 0x14, 4);
                sram_start = __builtin_bswap32(sram_start);
                sram_end   = __builtin_bswap32(sram_end);
                uint32_t sram_size = sram_end - sram_start + 1;
                config.cart.sram.resize(sram_size, 0xFF);
            }

            if (!config.bios_rom.empty())
            {
                System::initialize(config);
#ifdef LOOPYMSE_ANDROID
                android_set_rom_loaded(true);
#endif
                Log::info("ROM loaded and system initialized: %s", cart_path.c_str());
            }
        }
        else
        {
            Log::error("Cannot open ROM: %s", cart_path.c_str());
        }
    }

    // ── SDL init ──────────────────────────────────────────────────────────────
    SDL::initialize(args);

    // ── Main loop ─────────────────────────────────────────────────────────────
    uint64_t last_frame_ticks = SDL_GetPerformanceCounter();

    while (!has_quit)
    {
        constexpr int framerate_target  = 60;
        constexpr int framerate_max_lag = 5;

        uint64_t ticks_per_frame        = SDL_GetPerformanceFrequency() / framerate_target;
        uint64_t now_ticks              = SDL_GetPerformanceCounter();
        uint64_t ticks_since_last_frame = now_ticks - last_frame_ticks;
        uint64_t draw_frames            = ticks_since_last_frame / ticks_per_frame;
        last_frame_ticks               += draw_frames * ticks_per_frame;

        if (draw_frames > framerate_max_lag)
        {
            last_frame_ticks = now_ticks;
            draw_frames = 1;
        }

        // Check Android pause state (set by Kotlin via JNI)
#ifdef LOOPYMSE_ANDROID
        bool is_paused = android_is_paused();
#else
        bool is_paused = false;
#endif

        if (draw_frames && !is_paused && config.cart.is_loaded())
        {
            while (draw_frames > 0)
            {
                System::run();
                draw_frames--;
            }
            SDL::update(
                System::get_display_output(),
                Video::get_display_scanlines(),
                Video::get_background_color()
            );
        }
        else if (is_paused || !config.cart.is_loaded())
        {
            // Yield CPU when paused — don't busy-spin
            SDL_Delay(16);
        }

        // ── Event processing ──────────────────────────────────────────────────
        SDL_Event e;
        while (SDL_PollEvent(&e))
        {
            switch (e.type)
            {
            case SDL_QUIT:
                has_quit = true;
                break;

            case SDL_KEYDOWN:
                Input::set_key_state(e.key.keysym.sym, true);
                break;

            case SDL_KEYUP:
            {
                SDL_Keycode kc = e.key.keysym.sym;
#ifndef LOOPYMSE_ANDROID
                // Desktop-only shortcuts
                if (kc == SDLK_F11) SDL::toggle_fullscreen();
                else if (kc == SDLK_F12 && config.cart.is_loaded())
                {
                    System::shutdown(config);
                    System::initialize(config);
                }
                else
#endif
                Input::set_key_state(kc, false);
                break;
            }

            case SDL_CONTROLLERBUTTONDOWN:
                if (SDL::controller &&
                    SDL_GameControllerGetButton(SDL::controller, SDL_CONTROLLER_BUTTON_START) &&
                    SDL_GameControllerGetButton(SDL::controller, SDL_CONTROLLER_BUTTON_BACK))
                {
                    has_quit = true;
                }
                Input::set_controller_state(e.cbutton.button, true);
                break;

            case SDL_CONTROLLERBUTTONUP:
                Input::set_controller_state(e.cbutton.button, false);
                break;

            case SDL_WINDOWEVENT:
                if (e.window.event == SDL_WINDOWEVENT_FOCUS_LOST)
                    Sound::set_mute(true);
                else if (e.window.event == SDL_WINDOWEVENT_FOCUS_GAINED)
                    Sound::set_mute(false);
                break;

            case SDL_MOUSEBUTTONDOWN:
                Input::set_mouse_button_state(e.button.button, true);
                break;

            case SDL_MOUSEBUTTONUP:
                Input::set_mouse_button_state(e.button.button, false);
                break;

            case SDL_MOUSEMOTION:
                Input::move_mouse(e.motion.xrel, -e.motion.yrel);
                break;

            case SDL_CONTROLLERDEVICEADDED:
                if (!SDL::controller) SDL::open_first_controller();
                break;

            case SDL_CONTROLLERDEVICEREMOVED:
                if (SDL::controller &&
                    e.cdevice.which ==
                    SDL_JoystickInstanceID(SDL_GameControllerGetJoystick(SDL::controller)))
                {
                    SDL_GameControllerClose(SDL::controller);
                    SDL::open_first_controller();
                }
                break;
            }
        }
    }

    System::shutdown(config);
    SDL::shutdown();
    return 0;
}
