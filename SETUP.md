# Loopy-MseDroid — Build Setup Guide

Complete step-by-step instructions for building the project from scratch.

---

## Prerequisites

| Tool | Version | Where |
|---|---|---|
| Android Studio | Hedgehog 2023.1.1 or newer | developer.android.com/studio |
| Android NDK | r26b or newer | Via Android Studio SDK Manager |
| CMake | 3.22.1 or newer | Via Android Studio SDK Manager |
| Git | Any recent version | git-scm.com |

---

## Step 1 — Open the project

1. Clone or extract `LoopyMseDroid/` to your machine
2. Open **Android Studio → Open** and select the `LoopyMseDroid/` folder
3. Android Studio detects the Gradle project and begins syncing

If Gradle sync fails with **"NDK not found"**:
- Go to **File → Settings → SDK Tools**
- Check **NDK (Side by side)** version 26.x and **CMake** 3.22.x
- Click **Apply**, then re-sync

---

## Step 2 — Add SDL2 as a submodule

SDL2 must be added before the project will compile:

```bash
# Run from the LoopyMseDroid/ root directory
git init
git submodule add https://github.com/libsdl-org/SDL.git \
    app/src/main/jni/SDL2
cd app/src/main/jni/SDL2
git checkout release-2.30.x
cd ../../../../..
```

Verify: `app/src/main/jni/SDL2/CMakeLists.txt` must exist.

### Copy SDL2's Java files

SDL2's Android integration requires its Java layer to be in your project at
`org/libsdl/app/`. Our `SDLActivity.java` stub will be replaced by the real one:

```bash
# Copy SDL2's Android Java files into the project
SDL2_JAVA=app/src/main/jni/SDL2/android-project/app/src/main/java/org/libsdl/app

mkdir -p app/src/main/java/org/libsdl/app

# These are the files SDL2 needs — copy all of them
cp $SDL2_JAVA/SDLActivity.java              app/src/main/java/org/libsdl/app/
cp $SDL2_JAVA/SDLControllerManager.java     app/src/main/java/org/libsdl/app/
cp $SDL2_JAVA/SDLAudioManager.java          app/src/main/java/org/libsdl/app/
cp $SDL2_JAVA/SDLSurface.java               app/src/main/java/org/libsdl/app/ 2>/dev/null || true
cp $SDL2_JAVA/HIDDevice*.java               app/src/main/java/org/libsdl/app/ 2>/dev/null || true
```

> **Why:** SDL2's C JNI code hardcodes `org/libsdl/app/SDLActivity` as the class path
> for all callbacks. The stub `SDLActivity.java` we ship declares the correct native
> method signatures so the project compiles; SDL2's real version actually implements them.

### Update EmulatorActivity to extend real SDLActivity

After copying, our `EmulatorActivity.kt` already imports `org.libsdl.app.SDLActivity`
correctly — no changes needed.

---

## Step 3 — Copy LoopyMSE core source

```bash
git clone https://github.com/LoopyMSE/LoopyMSE.git /tmp/LoopyMSE

# Copy core emulation modules — zero modifications needed
cp -r /tmp/LoopyMSE/src/common     app/src/main/jni/LoopyMSE/common
cp -r /tmp/LoopyMSE/src/core       app/src/main/jni/LoopyMSE/core
cp -r /tmp/LoopyMSE/src/video      app/src/main/jni/LoopyMSE/video
cp -r /tmp/LoopyMSE/src/sound      app/src/main/jni/LoopyMSE/sound
cp -r /tmp/LoopyMSE/src/expansion  app/src/main/jni/LoopyMSE/expansion
cp -r /tmp/LoopyMSE/src/printer    app/src/main/jni/LoopyMSE/printer
cp -r /tmp/LoopyMSE/src/log        app/src/main/jni/LoopyMSE/log

# Assets
cp /tmp/LoopyMSE/assets/gamecontrollerdb.txt  app/src/main/assets/
cp /tmp/LoopyMSE/assets/loopymse.ini          app/src/main/assets/
cp /tmp/LoopyMSE/NOTICES.md                   app/src/main/assets/
```

**Do NOT copy** `src/input/` or `src/sdl/` — our Android-modified versions are
already present and must not be overwritten.

---

## Step 4 — Add stb_image_write

```bash
curl -o app/src/main/jni/stb/stb_image_write.h \
  https://raw.githubusercontent.com/nothings/stb/master/stb_image_write.h
```

---

## Step 5 — Build

In Android Studio: **Build → Make Project** (`Ctrl+F9` / `Cmd+F9`)

First build takes several minutes (SDL2 + emulator core for arm64-v8a).

For a release APK: **Build → Generate Signed Bundle / APK → APK**

---

## Step 6 — First launch

On first launch, the **BIOS Setup** screen appears:

1. Tap **Select bios.bin** → pick your Casio Loopy main BIOS (~512 KB)
2. Tap **Select soundbios.bin** → pick the sound BIOS (optional, needed for audio)
3. Tap **Continue**

Both files are copied to internal storage. Games won't start without the main BIOS.
Then tap **+** on the library screen to add a ROM.

---

## Project structure

```
app/src/main/
├── java/
│   ├── com/loopymse/droid/
│   │   ├── emulator/   EmulatorActivity, VirtualGamepadView, EmulatorBridge
│   │   ├── ui/         MainActivity, BiosSetupActivity, SettingsActivity
│   │   └── util/       PreferencesManager
│   └── org/libsdl/app/ SDLActivity (stub → replace with SDL2's real files in Step 2)
├── jni/
│   ├── CMakeLists.txt
│   ├── SDL2/           ← add in Step 2
│   ├── stb/            ← add in Step 4
│   ├── android/        JNI bridge + INI parser
│   └── LoopyMSE/
│       ├── common/core/video/sound/expansion/printer/log/  ← add in Step 3
│       ├── input/      Android-modified (do not overwrite)
│       └── sdl/        Android-modified (do not overwrite)
└── assets/             loopymse.ini, gamecontrollerdb.txt, NOTICES.md
```

---

## Initialization flow (reference)

1. `EmulatorActivity.onCreate()` — calls `EmulatorBridge.setInternalStoragePath()`,
   writes `loopymse.ini`, copies ROM to `filesDir/current_rom.bin`
2. `SDLActivity.surfaceChanged()` — spawns C++ thread, calls `SDL_main()`
3. `SDL_main()` (our `main.cpp`) — parses `--bios`, `--sound_bios`, `--cart` args,
   loads all files, calls `System::initialize()`, sets `g_rom_loaded = true`
4. Run loop starts — emulation at 60fps, virtual gamepad injecting input via JNI

---

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `UnsatisfiedLinkError: nativeSetupJNI` | SDL2 Java files not copied | Repeat Step 2 (copy SDL2 Java) |
| `CMake Error: SDL2/CMakeLists.txt not found` | SDL2 submodule missing | Repeat Step 2 (git submodule) |
| `undefined reference to set_controller_state_raw` | Wrong input/ folder | Do not copy upstream src/input/ |
| Black screen, no game | BIOS missing or bad path | Check Settings → BIOS Files |
| No sound | Sound BIOS missing | Settings → BIOS Files → set soundbios.bin |
| App crashes on launch | Library load failed | Check logcat for `dlopen failed` |

