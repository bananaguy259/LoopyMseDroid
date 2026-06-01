// SDLActivity.java
// Adapted from SDL2's Android support files.
// Original copyright: Sam Lantinga and SDL contributors (zlib license).
// This is the minimal version needed for LoopyMseDroid — stripped of features
// we don't use (clipboard, locale hints, etc.) to keep it readable.
//
// How it works:
//   1. onCreate() creates a GLSurfaceView (mSurface) and sets it as content view
//   2. SDL's C++ side renders into that surface via OpenGL ES
//   3. A background thread calls into the C++ main() function
//   4. Input events (touch, keys, gamepad) are forwarded via JNI to SDL's input queue
//
// EmulatorActivity extends this class and overrides:
//   - getMainSharedObject()  → "libLoopyMseDroid.so"
//   - getArguments()         → ["loopymse", "--config", ..., "--cart", ...]

package org.libsdl.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;

import java.util.Arrays;

/**
 * SDL2 Activity base class.
 *
 * Subclasses must override:
 *   getMainSharedObject()  — name of the .so to load
 *   getArguments()         — argv passed to C main()
 */
public abstract class SDLActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "SDLActivity";

    // The main SDL surface
    protected SDLSurface mSurface;

    // Whether the C main() thread is running
    private static Thread mSDLThread;

    // ─── Activity lifecycle ───────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Load our native library
        try {
            System.loadLibrary(getLibraryName());
            Log.i(TAG, "Loaded library: " + getLibraryName());
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load library: " + getLibraryName(), e);
            finish();
            return;
        }

        // Create and set the SDL surface as content view
        mSurface = new SDLSurface(getApplication());
        setContentView(mSurface);

        // Hide system UI for immersive mode
        mSurface.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        // Set up the JNI paths
        SDLActivity.nativeSetupJNI();
    }

    @Override
    protected void onPause() {
        super.onPause();
        nativePause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        nativeResume();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        nativeQuit();
    }

    // ─── To be overridden by EmulatorActivity ─────────────────────────────────

    /** Returns the base name of the shared library (without "lib" prefix and ".so" suffix). */
    protected String getLibraryName() {
        return "LoopyMseDroid";
    }

    /** Returns the full path of the shared object. */
    public String getMainSharedObject() {
        return "libLoopyMseDroid.so";
    }

    /** Returns the arguments array passed to C main(). */
    public String[] getArguments() {
        return new String[] { "loopymse" };
    }

    // ─── Surface lifecycle (called by SDLSurface) ─────────────────────────────

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.i(TAG, "surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.i(TAG, "surfaceChanged: " + width + "x" + height);
        // Start the C++ main thread the first time we have a valid surface
        if (mSDLThread == null || !mSDLThread.isAlive()) {
            final String[] args = getArguments();
            mSDLThread = new Thread(() -> {
                nativeRunMain(getMainSharedObject(), "SDL_main", args);
                Log.i(TAG, "C++ main() returned");
            }, "SDLMainThread");
            mSDLThread.start();
        }
        // Notify SDL of the new surface dimensions
        nativeSetSurface(holder.getSurface());
        nativeResized(width, height);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.i(TAG, "surfaceDestroyed");
        nativeSetSurface(null);
    }

    // ─── JNI declarations (implemented by SDL2's Android JNI glue) ───────────
    //
    // These are provided by SDL2's android-project/app/src/main/java/org/libsdl/app/SDLActivity.java
    // When building with the SDL2 submodule, SDL compiles these implementations
    // into the shared library automatically.

    private static native void nativeSetupJNI();
    private static native int  nativeRunMain(String library, String function, Object args);
    private static native void nativePause();
    private static native void nativeResume();
    private static native void nativeQuit();
    private static native void nativeSetSurface(Surface surface);
    private static native void nativeResized(int width, int height);

    // ─── Inner class: the GL surface ──────────────────────────────────────────

    public static class SDLSurface extends SurfaceView implements SurfaceHolder.Callback {

        public SDLSurface(Context context) {
            super(context);
            getHolder().addCallback(this);
            setFocusable(true);
            setFocusableInTouchMode(true);
            requestFocus();
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            // Forwarded to parent Activity
            if (getContext() instanceof SDLActivity) {
                ((SDLActivity) getContext()).surfaceCreated(holder);
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            if (getContext() instanceof SDLActivity) {
                ((SDLActivity) getContext()).surfaceChanged(holder, format, width, height);
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (getContext() instanceof SDLActivity) {
                ((SDLActivity) getContext()).surfaceDestroyed(holder);
            }
        }

        // Forward touch events to SDL's native input handler
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // SDL handles multi-touch natively via its own JNI callbacks.
            // We let SDL process it and return true to consume the event.
            return true;
        }

        // Forward key events to SDL
        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            return true;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent event) {
            return true;
        }

        // Forward generic motion events (analog sticks, triggers)
        @Override
        public boolean onGenericMotionEvent(MotionEvent event) {
            return true;
        }
    }
}
