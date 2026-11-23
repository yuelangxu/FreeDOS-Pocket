package com.max2idea.android.limbo.jni;

import android.os.ParcelFileDescriptor;
import android.util.Log;
import java.io.File;
import java.io.IOException;

/** JNI ABI shim for the unmodified upstream Limbo 6.0.1 native libraries.
 * Native implementation: https://github.com/limboemu/limbo (GPL-2.0-or-later).
 * Keep this class/package and callback names intact when shrinking an APK.
 */
public final class VMExecutor {
    private static final String TAG = "FreeDOSNative";
    public native String start(String storageDir, String baseDir,
                               String libraryName, String libraryPath,
                               int sdlScaleHint, Object[] args);

    public static void onVMResolutionChanged(int width, int height) {
        Log.i(TAG, "Guest framebuffer " + width + "x" + height);
    }

    /** Limbo's SAF bridge. Ordinary app-private files are opened by QEMU directly. */
    public int get_fd(String path) {
        try {
            ParcelFileDescriptor pfd;
            try {
                pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_WRITE);
            } catch (IOException ex) {
                pfd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
            }
            return pfd.detachFd();
        } catch (IOException ex) {
            Log.e(TAG, "Cannot open guest file", ex);
            return -1;
        }
    }

    /** Called for native descriptors too; adoptFd takes ownership before closing. */
    public int close_fd(int fd) {
        if (fd < 0) return -1;
        try {
            ParcelFileDescriptor.adoptFd(fd).close();
            return 0;
        } catch (IOException ex) {
            Log.w(TAG, "Cannot close guest descriptor " + fd, ex);
            return -1;
        }
    }
}
