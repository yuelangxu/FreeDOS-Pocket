package org.libsdl.app;

import android.content.Context;
import android.content.ContextWrapper;
import android.util.DisplayMetrics;
import android.view.Surface;
import java.io.InputStream;

/** Minimal SDL 2.0.8 JNI contract for headless AUDIO only, not an Android screen.
 * The exact class/method names are part of the upstream libSDL2.so JNI ABI.
 * Video, keyboard and clipboard remain owned by the app's RFB/Canvas interface.
 * ContextWrapper supplies the registerReceiver signature SDL can look up.
 */
public final class SDLActivity extends ContextWrapper {
    private static Context application;
    public static boolean mSeparateMouseAndTouch = true;
    private SDLActivity() { super(null); }
    public static void initialize(Context context) { application = context.getApplicationContext(); }
    public static native void nativeSetupJNI();
    public static Context getContext() { return application; }
    public static Surface getNativeSurface() { return null; }
    public static boolean setActivityTitle(String title) { return false; }
    public static void setWindowStyle(boolean fullscreen) {}
    public static void setOrientation(int width, int height, boolean resizable, String hint) {}
    public static boolean isAndroidTV() { return false; }
    public static int[] inputGetInputDeviceIds(int sources) { return new int[0]; }
    public static boolean sendMessage(int command, int parameter) { return false; }
    public static boolean showTextInput(int x, int y, int width, int height) { return false; }
    public static boolean isScreenKeyboardShown() { return false; }
    public static void clipboardSetText(String value) {}
    public static String clipboardGetText() { return ""; }
    public static boolean clipboardHasText() { return false; }
    public static InputStream openAPKExpansionInputStream(String name) { return null; }
    public static boolean getManifestEnvironmentVariables() { return false; }
    public static DisplayMetrics getDisplayDPI() { return application.getResources().getDisplayMetrics(); }
}
