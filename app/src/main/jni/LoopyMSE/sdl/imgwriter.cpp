/**
 * imgwriter.cpp (Android — stb_image_write implementation)
 *
 * Full drop-in replacement for desktop src/sdl/imgwriter.cpp.
 * Uses stb_image_write.h instead of SDL2_image — zero external dependency.
 *
 * Implements all functions called by printer.cpp and video.cpp:
 *   save_image_8bpp   — 8bpp paletted (printer seal output)
 *   save_image_16bpp  — 16bpp ARGB1555 (screenshots, screen capture prints)
 *   save_image_32bpp  — 32bpp ARGB8888
 *   save_surface      — SDL_Surface wrapper
 *   parse_image_type  — "png"/"jpg"/"bmp" → IMAGE_TYPE_* constant
 *   image_extension   — IMAGE_TYPE_* → fs::path{".png"} etc.
 *   make_unique_name  — timestamped unique filename component
 */

#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STB_IMAGE_WRITE_STATIC
#include <stb_image_write.h>

#include "imgwriter.h"
#include <log/log.h>

#include <algorithm>
#include <cstring>
#include <ctime>
#include <iomanip>
#include <sstream>
#include <vector>

namespace SDL::ImageWriter
{

// ─── Internal helpers ─────────────────────────────────────────────────────────

static bool write_rgb(int image_type, const std::string& path,
                      int w, int h, const uint8_t* rgb, int stride_bytes)
{
    int ok = 0;
    switch (image_type)
    {
    case IMAGE_TYPE_PNG:
        ok = stbi_write_png(path.c_str(), w, h, 3, rgb, stride_bytes);
        break;
    case IMAGE_TYPE_JPG:
        ok = stbi_write_jpg(path.c_str(), w, h, 3, rgb, 90);
        break;
    default: // BMP
        ok = stbi_write_bmp(path.c_str(), w, h, 3, rgb);
        break;
    }
    if (ok) Log::info("[imgwriter] Saved: %s", path.c_str());
    else    Log::error("[imgwriter] Failed to save: %s", path.c_str());
    return ok != 0;
}

/**
 * Nearest-neighbour scale of an RGB24 buffer.
 * new_w must be different from src_w (caller checks).
 */
static std::vector<uint8_t> scale_nearest(
    const uint8_t* src, int src_w, int src_h, int new_w)
{
    std::vector<uint8_t> dst(new_w * src_h * 3);
    for (int y = 0; y < src_h; y++)
    {
        for (int x = 0; x < new_w; x++)
        {
            int sx = (int)((x * src_w) / new_w);
            sx = std::clamp(sx, 0, src_w - 1);
            const uint8_t* s = src + (y * src_w + sx) * 3;
            uint8_t*       d = dst.data() + (y * new_w + x) * 3;
            d[0] = s[0]; d[1] = s[1]; d[2] = s[2];
        }
    }
    return dst;
}

/** Apply correct_aspect scaling if needed, then write. */
static bool write_with_aspect(
    int image_type, const fs::path& path,
    int w, int h, std::vector<uint8_t>& rgb,
    double correct_aspect)
{
    const std::string path_str = path.string();

    if (correct_aspect > 0)
    {
        int new_w = (int)std::round(w * correct_aspect);
        if (new_w != w && new_w > 0)
        {
            auto scaled = scale_nearest(rgb.data(), w, h, new_w);
            return write_rgb(image_type, path_str, new_w, h, scaled.data(), new_w * 3);
        }
    }
    return write_rgb(image_type, path_str, w, h, rgb.data(), w * 3);
}

// ─── Utility ──────────────────────────────────────────────────────────────────

int parse_image_type(std::string type, int default_type)
{
    std::transform(type.begin(), type.end(), type.begin(), ::tolower);
    if (type == "bmp" || type == ".bmp") return IMAGE_TYPE_BMP;
    if (type == "jpg" || type == "jpeg" || type == ".jpg") return IMAGE_TYPE_JPG;
    if (type == "png" || type == ".png") return IMAGE_TYPE_PNG;
    return default_type;
}

fs::path image_extension(int image_type)
{
    switch (image_type)
    {
    case IMAGE_TYPE_BMP: return {".bmp"};
    case IMAGE_TYPE_JPG: return {".jpg"};
    case IMAGE_TYPE_PNG: return {".png"};
    default:             return {".png"};
    }
}

fs::path make_unique_name(std::string prefix, std::string suffix)
{
    static unsigned int counter = 1;
    std::time_t ts = std::time(nullptr);
    char buf[20];
    std::strftime(buf, sizeof(buf), "%Y%m%d_%H%M%S", std::localtime(&ts));
    return fs::path{prefix + buf + "_" + std::to_string(counter++) + suffix};
}

// ─── save_surface ─────────────────────────────────────────────────────────────

bool save_surface(int image_type, fs::path path, SDL_Surface* surface)
{
    if (!surface) return false;

    const int     w     = surface->w;
    const int     h     = surface->h;
    const int     pitch = surface->pitch;
    const uint8_t* src  = static_cast<const uint8_t*>(surface->pixels);

    // Ensure RGB24 (3 bytes per pixel)
    if (surface->format->BytesPerPixel != 3)
    {
        Log::error("[imgwriter] save_surface: expected RGB24 surface");
        return false;
    }

    std::vector<uint8_t> rgb;
    const uint8_t* write_src;
    int            write_stride;

    if (pitch == w * 3)
    {
        write_src    = src;
        write_stride = pitch;
    }
    else
    {
        rgb.resize(w * h * 3);
        for (int row = 0; row < h; row++)
            memcpy(rgb.data() + row * w * 3, src + row * pitch, w * 3);
        write_src    = rgb.data();
        write_stride = w * 3;
    }

    return write_rgb(image_type, path.string(), w, h, write_src, write_stride);
}

// ─── save_image_16bpp ─────────────────────────────────────────────────────────
// Loopy native pixel format: ARGB1555 (bit 15 = alpha, 14-10 = R, 9-5 = G, 4-0 = B)

bool save_image_16bpp(
    int image_type, fs::path path,
    uint32_t width, uint32_t height, uint16_t data[],
    bool /*transparent*/, double correct_aspect)
{
    const uint32_t n = width * height;
    std::vector<uint8_t> rgb(n * 3);

    for (uint32_t i = 0; i < n; i++)
    {
        const uint16_t px = data[i];
        rgb[i*3+0] = ((px >> 10) & 0x1F) * 255 / 31; // R
        rgb[i*3+1] = ((px >>  5) & 0x1F) * 255 / 31; // G
        rgb[i*3+2] = ( px        & 0x1F) * 255 / 31; // B
    }

    return write_with_aspect(image_type, path, (int)width, (int)height, rgb, correct_aspect);
}

// ─── save_image_8bpp ──────────────────────────────────────────────────────────
// 8bpp paletted; palette entries are ARGB1555 (same as Loopy VRAM palette format)

bool save_image_8bpp(
    int image_type, fs::path path,
    uint32_t width, uint32_t height,
    uint8_t data[], uint32_t num_colors, uint16_t palette[],
    bool transparent, double correct_aspect)
{
    // Build a fast RGB lookup table from the ARGB1555 palette
    std::vector<uint8_t> pal_rgb(num_colors * 3);
    for (uint32_t i = 0; i < num_colors; i++)
    {
        const uint16_t c = palette[i];
        pal_rgb[i*3+0] = ((c >> 10) & 0x1F) * 255 / 31;
        pal_rgb[i*3+1] = ((c >>  5) & 0x1F) * 255 / 31;
        pal_rgb[i*3+2] = ( c        & 0x1F) * 255 / 31;
    }

    const uint32_t n = width * height;
    std::vector<uint8_t> rgb(n * 3);

    for (uint32_t i = 0; i < n; i++)
    {
        const uint8_t idx = data[i];
        if (idx < num_colors)
        {
            rgb[i*3+0] = pal_rgb[idx*3+0];
            rgb[i*3+1] = pal_rgb[idx*3+1];
            rgb[i*3+2] = pal_rgb[idx*3+2];
        }
        else
        {
            // Out-of-range index: black
            rgb[i*3+0] = rgb[i*3+1] = rgb[i*3+2] = 0;
        }
    }

    return write_with_aspect(image_type, path, (int)width, (int)height, rgb, correct_aspect);
}

// ─── save_image_32bpp ─────────────────────────────────────────────────────────
// 32bpp ARGB8888

bool save_image_32bpp(
    int image_type, fs::path path,
    uint32_t width, uint32_t height, uint32_t data[],
    bool /*transparent*/, double correct_aspect)
{
    const uint32_t n = width * height;
    std::vector<uint8_t> rgb(n * 3);

    for (uint32_t i = 0; i < n; i++)
    {
        const uint32_t px = data[i];
        rgb[i*3+0] = (px >> 16) & 0xFF; // R
        rgb[i*3+1] = (px >>  8) & 0xFF; // G
        rgb[i*3+2] =  px        & 0xFF; // B
    }

    return write_with_aspect(image_type, path, (int)width, (int)height, rgb, correct_aspect);
}

} // namespace SDL::ImageWriter
