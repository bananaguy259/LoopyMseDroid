cmake_minimum_required(VERSION 3.22)

project(LoopyMseDroid LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(PROJECT_VERSION     "1.0.0")
set(PROJECT_DESCRIPTION "Loopy-MseDroid")

# ─── Compiler flags ──────────────────────────────────────────────────────────
add_compile_options(
    -O2
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
add_link_options(-Wl,--gc-sections)

# ─── SDL2 ─────────────────────────────────────────────────────────────────────
# SDL2 ships with its own Android CMake support. We add it as a subdirectory.
# The SDL2 folder is a Git submodule at jni/SDL2.
add_subdirectory(SDL2)

# ─── Include paths ───────────────────────────────────────────────────────────
include_directories(
    LoopyMSE           # Makes #include <core/...> etc. work (mirrors desktop src/ layout)
    stb                # For stb_image_write.h
    SDL2/include       # SDL2 public headers
)

# ─── Source files ─────────────────────────────────────────────────────────────

# --- Common ---
set(SRC_COMMON
    LoopyMSE/common/bswp.cpp
)

# --- Log ---
set(SRC_LOG
    LoopyMSE/log/log.cpp
)

# --- Core emulation (SH2 CPU, memory, system) --- ZERO CHANGES from upstream
set(SRC_CORE
    LoopyMSE/core/cart.cpp
    LoopyMSE/core/loopy_io.cpp
    LoopyMSE/core/memory.cpp
    LoopyMSE/core/system.cpp
    LoopyMSE/core/timing.cpp
    LoopyMSE/core/sh2/sh2.cpp
    LoopyMSE/core/sh2/sh2_bus.cpp
    LoopyMSE/core/sh2/sh2_interpreter.cpp
    LoopyMSE/core/sh2/peripherals/sh2_dmac.cpp
    LoopyMSE/core/sh2/peripherals/sh2_intc.cpp
    LoopyMSE/core/sh2/peripherals/sh2_ocpm.cpp
    LoopyMSE/core/sh2/peripherals/sh2_pfc.cpp
    LoopyMSE/core/sh2/peripherals/sh2_serial.cpp
    LoopyMSE/core/sh2/peripherals/sh2_timers.cpp
)

# --- Input --- ZERO CHANGES from upstream
set(SRC_INPUT
    LoopyMSE/input/input.cpp
)

# --- Video --- ZERO CHANGES from upstream
set(SRC_VIDEO
    LoopyMSE/video/render.cpp
    LoopyMSE/video/video.cpp
)

# --- Sound --- ZERO CHANGES from upstream
set(SRC_SOUND
    LoopyMSE/sound/loopysound.cpp
    LoopyMSE/sound/sound.cpp
)

# --- Expansion --- ZERO CHANGES from upstream
set(SRC_EXPANSION
    LoopyMSE/expansion/expansion.cpp
    LoopyMSE/expansion/msm665x/msm665x.cpp
)

# --- Printer --- ZERO CHANGES from upstream
set(SRC_PRINTER
    LoopyMSE/printer/printer.cpp
)

# --- SDL platform layer (lightly modified for Android paths) ---
set(SRC_SDL
    LoopyMSE/sdl/main.cpp
    LoopyMSE/sdl/imgwriter.cpp      # Rewritten to use stb_image_write (no SDL2_image)
    LoopyMSE/sdl/options.cpp        # Rewritten: Boost removed, uses ini_parser
)

# --- Android-specific JNI glue ---
set(SRC_ANDROID
    android/android_bridge.cpp  # JNI functions called from Kotlin
    android/ini_parser.cpp      # Replaces Boost program_options (INI only)
)

# ─── Build the shared library ─────────────────────────────────────────────────
add_library(
    LoopyMseDroid
    SHARED
    ${SRC_COMMON}
    ${SRC_LOG}
    ${SRC_CORE}
    ${SRC_INPUT}
    ${SRC_VIDEO}
    ${SRC_SOUND}
    ${SRC_EXPANSION}
    ${SRC_PRINTER}
    ${SRC_SDL}
    ${SRC_ANDROID}
)

# ─── Link libraries ───────────────────────────────────────────────────────────
target_link_libraries(
    LoopyMseDroid
    SDL2                    # SDL2 Android (audio, video, input)
    android                 # Android NDK (JNI, asset manager, etc.)
    log                     # Android logging (for Log::*)
    GLESv2                  # OpenGL ES (used by SDL2 renderer)
    EGL                     # EGL (window system for OpenGL ES)
    OpenSLES                # Audio backend for SDL2 on Android (API < 26)
)

# ─── Compile definitions ──────────────────────────────────────────────────────
target_compile_definitions(
    LoopyMseDroid PRIVATE
    # SDL_MAIN_HANDLED is intentionally NOT set on Android.
    # SDL2's Android JNI glue calls SDL_main() by symbol name via dlsym().
    # We export our entry point as SDL_main (see main.cpp).
    # On desktop builds SDL_MAIN_HANDLED would be set here instead.
    #
    # Tell our code we're building for Android
    LOOPYMSE_ANDROID
    # Version info (mirrors desktop PROJECT_VERSION / PROJECT_DESCRIPTION)
    PROJECT_VERSION="${PROJECT_VERSION}"
    PROJECT_DESCRIPTION="${PROJECT_DESCRIPTION}"
)
