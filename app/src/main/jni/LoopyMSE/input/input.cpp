/**
 * input.cpp (Android version)
 *
 * Identical to upstream except for one addition:
 *   set_controller_state_raw(int buttonCode, bool pressed)
 *
 * The desktop version routes physical controller buttons through a binding map
 * (SDL button ID → PadButton enum). On Android, the virtual gamepad already
 * knows the PadButton code directly (it uses the same constants as Input::PadButton),
 * so it bypasses the binding lookup and calls LoopyIO directly.
 *
 * Physical gamepad input still goes through set_controller_state() and the
 * binding map, exactly as on desktop.
 */

#include "input/input.h"
#include <core/loopy_io.h>
#include <unordered_map>

namespace Input
{

static std::unordered_map<int, PadButton>   key_bindings;
static std::unordered_map<int, PadButton>   controller_bindings;
static std::unordered_map<int, MouseButton> mouse_bindings;

void initialize()
{
    // Indicate the gamepad is connected — required for Loopy to accept input
    LoopyIO::set_controller_plugged(true, false);
}

void shutdown()
{
    // nop
}

// ─── Physical controller (via SDL binding map) ────────────────────────────────

void set_controller_state(int button, bool pressed)
{
    auto binding = controller_bindings.find(button);
    if (binding == controller_bindings.end())
        return;

    LoopyIO::update_pad(binding->second, pressed);
}

// ─── Keyboard ─────────────────────────────────────────────────────────────────

void set_key_state(int key, bool pressed)
{
    auto binding = key_bindings.find(key);
    if (binding == key_bindings.end())
        return;

    LoopyIO::update_pad(binding->second, pressed);
}

// ─── Mouse ────────────────────────────────────────────────────────────────────

void set_mouse_button_state(int button, bool pressed)
{
    auto binding = mouse_bindings.find(button);
    if (binding == mouse_bindings.end())
        return;

    LoopyIO::update_mouse_buttons(binding->second, pressed);
}

void move_mouse(int delta_x, int delta_y)
{
    LoopyIO::update_mouse_position(delta_x, delta_y);
}

// ─── Virtual gamepad (Android only) ──────────────────────────────────────────

#ifdef LOOPYMSE_ANDROID
/**
 * Direct injection of a PadButton value — bypasses the binding map.
 * Called from android_bridge.cpp when the virtual gamepad is pressed.
 *
 * buttonCode is one of the PadButton enum values (e.g. PAD_A = 0x0010).
 * VirtualGamepadView uses the same constants, so no translation needed.
 */
void set_controller_state_raw(int buttonCode, bool pressed)
{
    LoopyIO::update_pad(static_cast<PadButton>(buttonCode), pressed);
}
#endif

// ─── Binding registration ─────────────────────────────────────────────────────

void add_key_binding(int code, PadButton pad_button)
{
    key_bindings.emplace(code, pad_button);
}

void add_controller_binding(int code, PadButton pad_button)
{
    controller_bindings.emplace(code, pad_button);
}

void add_mouse_binding(int code, MouseButton mouse_button)
{
    mouse_bindings.emplace(code, mouse_button);
}

} // namespace Input
