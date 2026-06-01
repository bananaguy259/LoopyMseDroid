/**
 * ini_parser.cpp
 *
 * Minimal INI file parser replacing Boost program_options.
 * Reads the same loopymse.ini format as the desktop version, including
 * [keyboard-map] and [controller-map] sections so that physical gamepads
 * and keyboards work identically to the desktop.
 */

#include "ini_parser.h"
#include <input/input.h>
#include <log/log.h>

#include <SDL2/SDL.h>
#include <algorithm>
#include <fstream>
#include <string>
#include <unordered_map>

namespace IniParser
{

// ─── String helpers ───────────────────────────────────────────────────────────

static std::string trim(const std::string& s)
{
    size_t a = s.find_first_not_of(" \t\r\n");
    size_t b = s.find_last_not_of(" \t\r\n");
    return (a == std::string::npos) ? "" : s.substr(a, b - a + 1);
}

static std::string lower(std::string s)
{
    std::transform(s.begin(), s.end(), s.begin(), ::tolower);
    return s;
}

static bool to_bool(const std::string& v)
{
    const std::string lv = lower(v);
    return lv == "true" || lv == "1" || lv == "yes";
}

// ─── SDL controller button name → SDL_GameControllerButton ───────────────────

static const std::unordered_map<std::string, SDL_GameControllerButton> CTRL_BTN_MAP = {
    {"a",             SDL_CONTROLLER_BUTTON_A},
    {"b",             SDL_CONTROLLER_BUTTON_B},
    {"x",             SDL_CONTROLLER_BUTTON_X},
    {"y",             SDL_CONTROLLER_BUTTON_Y},
    {"back",          SDL_CONTROLLER_BUTTON_BACK},
    {"guide",         SDL_CONTROLLER_BUTTON_GUIDE},
    {"start",         SDL_CONTROLLER_BUTTON_START},
    {"leftstick",     SDL_CONTROLLER_BUTTON_LEFTSTICK},
    {"rightstick",    SDL_CONTROLLER_BUTTON_RIGHTSTICK},
    {"leftshoulder",  SDL_CONTROLLER_BUTTON_LEFTSHOULDER},
    {"rightshoulder", SDL_CONTROLLER_BUTTON_RIGHTSHOULDER},
    {"dpup",          SDL_CONTROLLER_BUTTON_DPAD_UP},
    {"dpdown",        SDL_CONTROLLER_BUTTON_DPAD_DOWN},
    {"dpleft",        SDL_CONTROLLER_BUTTON_DPAD_LEFT},
    {"dpright",       SDL_CONTROLLER_BUTTON_DPAD_RIGHT},
    {"misc1",         SDL_CONTROLLER_BUTTON_MISC1},
    {"touchpad",      SDL_CONTROLLER_BUTTON_TOUCHPAD},
};

// ─── SDL key name → SDL_Keycode ───────────────────────────────────────────────
// Only the keys that the desktop default INI actually uses

static const std::unordered_map<std::string, SDL_Keycode> KEY_MAP = {
    {"up",      SDLK_UP},     {"down",   SDLK_DOWN},
    {"left",    SDLK_LEFT},   {"right",  SDLK_RIGHT},
    {"return",  SDLK_RETURN}, {"space",  SDLK_SPACE},
    {"escape",  SDLK_ESCAPE},
    {"a", SDLK_a}, {"b", SDLK_b}, {"c", SDLK_c}, {"d", SDLK_d},
    {"e", SDLK_e}, {"f", SDLK_f}, {"g", SDLK_g}, {"h", SDLK_h},
    {"i", SDLK_i}, {"j", SDLK_j}, {"k", SDLK_k}, {"l", SDLK_l},
    {"m", SDLK_m}, {"n", SDLK_n}, {"o", SDLK_o}, {"p", SDLK_p},
    {"q", SDLK_q}, {"r", SDLK_r}, {"s", SDLK_s}, {"t", SDLK_t},
    {"u", SDLK_u}, {"v", SDLK_v}, {"w", SDLK_w}, {"x", SDLK_x},
    {"y", SDLK_y}, {"z", SDLK_z},
    {"1", SDLK_1}, {"2", SDLK_2}, {"3", SDLK_3}, {"4", SDLK_4},
    {"5", SDLK_5}, {"6", SDLK_6}, {"7", SDLK_7}, {"8", SDLK_8},
    {"9", SDLK_9}, {"0", SDLK_0},
};

// ─── Loopy pad key name → Input::PadButton ───────────────────────────────────

static const std::unordered_map<std::string, Input::PadButton> PAD_MAP = {
    {"pad_start", Input::PAD_START},
    {"pad_l1",    Input::PAD_L1},
    {"pad_r1",    Input::PAD_R1},
    {"pad_a",     Input::PAD_A},
    {"pad_b",     Input::PAD_B},
    {"pad_c",     Input::PAD_C},
    {"pad_d",     Input::PAD_D},
    {"pad_up",    Input::PAD_UP},
    {"pad_down",  Input::PAD_DOWN},
    {"pad_left",  Input::PAD_LEFT},
    {"pad_right", Input::PAD_RIGHT},
};

// ─── Default bindings (matches desktop loopymse.ini defaults) ─────────────────

static void register_default_controller_bindings()
{
    const std::unordered_map<SDL_GameControllerButton, Input::PadButton> defaults = {
        {SDL_CONTROLLER_BUTTON_DPAD_UP,      Input::PAD_UP},
        {SDL_CONTROLLER_BUTTON_DPAD_DOWN,    Input::PAD_DOWN},
        {SDL_CONTROLLER_BUTTON_DPAD_LEFT,    Input::PAD_LEFT},
        {SDL_CONTROLLER_BUTTON_DPAD_RIGHT,   Input::PAD_RIGHT},
        {SDL_CONTROLLER_BUTTON_START,        Input::PAD_START},
        {SDL_CONTROLLER_BUTTON_A,            Input::PAD_A},
        {SDL_CONTROLLER_BUTTON_B,            Input::PAD_B},
        {SDL_CONTROLLER_BUTTON_Y,            Input::PAD_C},
        {SDL_CONTROLLER_BUTTON_X,            Input::PAD_D},
        {SDL_CONTROLLER_BUTTON_LEFTSHOULDER, Input::PAD_L1},
        {SDL_CONTROLLER_BUTTON_RIGHTSHOULDER,Input::PAD_R1},
    };
    for (const auto& [btn, pad] : defaults)
        Input::add_controller_binding(btn, pad);
}

static void register_default_keyboard_bindings()
{
    const std::unordered_map<SDL_Keycode, Input::PadButton> defaults = {
        {SDLK_UP,     Input::PAD_UP},
        {SDLK_DOWN,   Input::PAD_DOWN},
        {SDLK_LEFT,   Input::PAD_LEFT},
        {SDLK_RIGHT,  Input::PAD_RIGHT},
        {SDLK_RETURN, Input::PAD_START},
        {SDLK_z,      Input::PAD_A},
        {SDLK_x,      Input::PAD_B},
        {SDLK_c,      Input::PAD_C},
        {SDLK_v,      Input::PAD_D},
        {SDLK_q,      Input::PAD_L1},
        {SDLK_w,      Input::PAD_R1},
    };
    for (const auto& [key, pad] : defaults)
        Input::add_key_binding(key, pad);
}

// ─── Public API ───────────────────────────────────────────────────────────────

bool parse_config(const std::string& path, Options::Args& args)
{
    // Always register default bindings first — INI overrides them if present
    register_default_controller_bindings();
    register_default_keyboard_bindings();

    std::ifstream file(path);
    if (!file.is_open())
    {
        Log::warn("[IniParser] Could not open: %s — using defaults", path.c_str());
        return false;
    }

    std::string section;
    std::string line;

    while (std::getline(file, line))
    {
        line = trim(line);
        if (line.empty() || line[0] == '#' || line[0] == ';') continue;

        // Section header
        if (line[0] == '[')
        {
            size_t end = line.find(']');
            if (end != std::string::npos)
                section = lower(trim(line.substr(1, end - 1)));
            continue;
        }

        // key=value
        size_t eq = line.find('=');
        if (eq == std::string::npos) continue;

        std::string key   = lower(trim(line.substr(0, eq)));
        std::string value = trim(line.substr(eq + 1));

        // Strip inline comments
        size_t cmt = value.find('#');
        if (cmt != std::string::npos) value = trim(value.substr(0, cmt));
        if (value.empty()) continue;

        // ── [emulator] ────────────────────────────────────────────────────────
        if (section == "emulator")
        {
            if      (key == "bios")                 args.bios                     = value;
            else if (key == "sound_bios")           args.sound_bios               = value;
            else if (key == "run_in_background")    args.run_in_background        = to_bool(value);
            else if (key == "correct_aspect_ratio") args.correct_aspect_ratio     = to_bool(value);
            else if (key == "crop_overscan")        args.crop_overscan            = to_bool(value);
            else if (key == "antialias")            args.antialias                = to_bool(value);
            else if (key == "start_in_fullscreen")  args.start_in_fullscreen      = to_bool(value);
            else if (key == "verbose")              args.verbose                  = to_bool(value);
            else if (key == "int_scale")
            {
                try { args.int_scale = std::stoi(value); } catch (...) {}
            }
            else if (key == "screenshot_image_type")
            {
                // Desktop constants: 1=BMP, 2=PNG, 3=JPG (matches ImageWriter constants)
                if      (value == "png") args.screenshot_image_type = 2;
                else if (value == "jpg" || value == "jpeg") args.screenshot_image_type = 3;
                else    args.screenshot_image_type = 1; // bmp
            }
        }

        // ── [printer] ─────────────────────────────────────────────────────────
        else if (section == "printer")
        {
            if (key == "image_type")
            {
                if      (value == "png") args.printer_image_type = 2;
                else if (value == "jpg" || value == "jpeg") args.printer_image_type = 3;
                else    args.printer_image_type = 1;
            }
            else if (key == "correct_aspect_ratio")
                args.printer_correct_aspect_ratio = to_bool(value);
            else if (key == "view_command")
                args.printer_view_command = value;
        }

        // ── [controller-map] ──────────────────────────────────────────────────
        // Override the default bindings with whatever the INI specifies.
        else if (section == "controller-map")
        {
            auto pad_it = PAD_MAP.find(key);
            if (pad_it == PAD_MAP.end()) continue;

            auto btn_it = CTRL_BTN_MAP.find(lower(value));
            if (btn_it == CTRL_BTN_MAP.end())
            {
                Log::warn("[IniParser] Unknown controller button: %s", value.c_str());
                continue;
            }
            Input::add_controller_binding(btn_it->second, pad_it->second);
        }

        // ── [keyboard-map] ────────────────────────────────────────────────────
        else if (section == "keyboard-map")
        {
            auto pad_it = PAD_MAP.find(key);
            if (pad_it == PAD_MAP.end()) continue;

            auto key_it = KEY_MAP.find(lower(value));
            if (key_it == KEY_MAP.end())
            {
                Log::warn("[IniParser] Unknown key name: %s", value.c_str());
                continue;
            }
            Input::add_key_binding(key_it->second, pad_it->second);
        }
    }

    Log::info("[IniParser] Loaded config: %s", path.c_str());
    return true;
}

void parse_commandline(int, char**, Options::Args&)
{
    // No-op on Android
}

void print_usage()
{
    Log::info("Loopy-MseDroid %s", "1.0.0");
}

} // namespace IniParser
