package org.libsdl.app;

/** SDL 2.0.8 completes SDL_SetMainReady only when this ABI is registered too.
 * Input travels through RFB; the emulator does not open Android controllers.
 */
public final class SDLControllerManager {
    private SDLControllerManager() {}
    public static native void nativeSetupJNI();
    public static void pollInputDevices() {}
    public static void pollHapticDevices() {}
    public static void hapticRun(int deviceId, int milliseconds) {}
}
