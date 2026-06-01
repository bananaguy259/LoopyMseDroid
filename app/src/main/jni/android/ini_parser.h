#pragma once
/**
 * ini_parser.h
 *
 * Android replacement for Boost program_options.
 * Provides the same Options::Args struct and parse functions as the desktop
 * src/sdl/options.h, so the rest of the code compiles unchanged.
 */

#include <string>
#include <filesystem>

namespace fs = std::filesystem;

// Reuse the same Options namespace and Args struct as the desktop version
// so main.cpp compiles without changes.
namespace Options
{

struct Args
{
    std::string cart;
    std::string bios;
    std::string sound_bios;
    bool run_in_background        = true;
    bool start_in_fullscreen      = true;
    bool correct_aspect_ratio     = true;
    bool crop_overscan            = true;
    bool antialias                = true;
    bool verbose                  = false;
    int  int_scale                = 4;
    int  screenshot_image_type    = 2;  // 1=BMP, 2=PNG, 3=JPG — matches ImageWriter constants
    int  printer_image_type       = 2;
    bool printer_correct_aspect_ratio = true;
    std::string printer_view_command;
};

} // namespace Options

namespace IniParser
{
    bool parse_config(const std::string& path, Options::Args& args);
    void parse_commandline(int argc, char** argv, Options::Args& args);
    void print_usage();
}
