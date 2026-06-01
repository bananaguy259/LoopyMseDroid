#pragma once
/**
 * imgwriter.h (Android version)
 *
 * API-identical to desktop src/sdl/imgwriter.h.
 * printer.cpp, video.cpp, and main.cpp include this and must compile unchanged.
 *
 * Image type constants match the desktop exactly:
 *   IMAGE_TYPE_BMP = 1
 *   IMAGE_TYPE_PNG = 2
 *   IMAGE_TYPE_JPG = 3
 */

#include <SDL2/SDL.h>
#include <filesystem>
#include <string>

namespace fs = std::filesystem;

namespace SDL::ImageWriter
{

// ─── Constants — must match desktop values exactly ────────────────────────────
const int IMAGE_TYPE_BMP     = 1;
const int IMAGE_TYPE_PNG     = 2;
const int IMAGE_TYPE_JPG     = 3;
const int IMAGE_TYPE_DEFAULT = IMAGE_TYPE_PNG;

// Aspect ratios used by the printer
const double LOOPY_SEAL_ASPECT   = 8.0 / 7.0;
const double LOOPY_SCREEN_ASPECT = 4.0 / 3.0;

// ─── Utility ──────────────────────────────────────────────────────────────────

int      parse_image_type(std::string type, int default_type = IMAGE_TYPE_DEFAULT);
fs::path image_extension(int image_type);
fs::path make_unique_name(std::string prefix = "", std::string suffix = "");

// ─── Save functions ───────────────────────────────────────────────────────────

bool save_surface(int image_type, fs::path path, SDL_Surface* surf);

bool save_image_32bpp(
    int image_type, fs::path path,
    uint32_t width, uint32_t height, uint32_t data[],
    bool transparent = false, double correct_aspect = 0);

bool save_image_16bpp(
    int image_type, fs::path path,
    uint32_t width, uint32_t height, uint16_t data[],
    bool transparent = false, double correct_aspect = 0);

bool save_image_8bpp(
    int image_type, fs::path path,
    uint32_t width, uint32_t height,
    uint8_t data[], uint32_t num_colors, uint16_t palette[],
    bool transparent = false, double correct_aspect = 0);

} // namespace SDL::ImageWriter
