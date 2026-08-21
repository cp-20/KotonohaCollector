package com.google.android.apps.inputmethod.libs.mozc.session;

/** Java entry point registered by the official Mozc Android JNI library. */
public final class MozcJNI {
    static {
        System.loadLibrary("mozc");
        if (!initialize()) {
            throw new UnsatisfiedLinkError("Mozc JNI registration failed");
        }
    }

    private MozcJNI() {
    }

    private static native boolean initialize();

    public static native boolean onPostLoad(String userProfileDirectory, String dataFilePath);

    public static native byte[] evalCommand(byte[] command);

    public static native String getDataVersion();
}
