# LoopyMseDroid ProGuard rules

# ─── SDL2 Java layer ──────────────────────────────────────────────────────────
# SDL's JNI glue calls back into Java by class/method name reflection.
# These must NOT be renamed or removed.
-keep class com.loopymse.droid.sdl.** { *; }
-keep class org.libsdl.app.** { *; }

# ─── EmulatorBridge ───────────────────────────────────────────────────────────
# JNI methods — names must match the C function names exactly.
-keep class com.loopymse.droid.emulator.EmulatorBridge { *; }
-keepclassmembers class com.loopymse.droid.emulator.EmulatorBridge {
    native <methods>;
}

# ─── EmulatorActivity ─────────────────────────────────────────────────────────
# onPrinterOutput() is called from C++ via JNI by name.
-keepclassmembers class com.loopymse.droid.emulator.EmulatorActivity {
    public void onPrinterOutput(java.lang.String);
    public void takeScreenshot();
}

# ─── AndroidX Preferences ────────────────────────────────────────────────────
-keep class androidx.preference.** { *; }

# ─── Kotlin metadata ──────────────────────────────────────────────────────────
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes SourceFile,LineNumberTable

# ─── General Android ──────────────────────────────────────────────────────────
-dontwarn kotlin.**
-dontwarn kotlinx.**
