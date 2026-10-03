package com.xw.vvtts.core;

/**
 * ECI bridge (libvvtts_core.so) v3 — vendored for evvdroid Apple-engine mode.
 *
 * Source: https://github.com/GGbond2007/eloquence-tts-android
 * (src/com/xw/vvtts/core/VvttsCore.java, MIT for code).
 *
 * This file is the Java surface only. The native implementation
 * (libvvtts_core.so) plus the Apple-derived engine (libeci.so) and language
 * libraries (libenu.so etc.) are NOT committed here. They are downloaded at
 * build time by native/fetch-apple-libs.sh for personal builds only.
 *
 * Apple Eloquence voice data is copyright Apple Inc. See APPLE_ENGINE_NOTES.md.
 */
public class VvttsCore {

    static {
        System.loadLibrary("vvtts_core");
    }

    public static final int CHARSET_1252 = 0;
    public static final int CHARSET_GBK = 6;

    static native long nativeInitEngine(String configDir, String libDir, int dialect);
    static native short[] nativeSynthesize(long handle, int dialect, byte[] text, int charsetId, String outPath);
    static native int nativeSetVoiceParam(long handle, int voice, int param, int value);
    static native int nativeGetVoiceParam(long handle, int voice, int param);
    static native int nativeSetParam(long handle, int param, int value);
    static native int nativeSetStandardVoice(long handle, int voiceNumber);
    static native void nativeShutdown(long handle);
    static native void nativeStop(long handle);

    public static long openEngine(String configDir, String libDir, int dialect) {
        try {
            return nativeInitEngine(configDir, libDir, dialect);
        } catch (Throwable e) {
            android.util.Log.e("VvttsCore", "openEngine native crash", e);
            return 0;
        }
    }

    public static short[] synth(long handle, int dialect, byte[] text, int charsetId, String outPath) {
        try {
            return nativeSynthesize(handle, dialect, text, charsetId, outPath);
        } catch (Throwable e) {
            android.util.Log.e("VvttsCore", "synth native crash", e);
            return null;
        }
    }

    public static int setVoiceParam(long handle, int voice, int param, int value) {
        try {
            return nativeSetVoiceParam(handle, voice, param, value);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int getVoiceParam(long handle, int voice, int param) {
        try {
            return nativeGetVoiceParam(handle, voice, param);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int setParam(long handle, int param, int value) {
        try {
            return nativeSetParam(handle, param, value);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static int setStandardVoice(long handle, int voiceNumber) {
        try {
            return nativeSetStandardVoice(handle, voiceNumber);
        } catch (Throwable e) {
            return -1;
        }
    }

    public static void shutdown(long handle) {
        try {
            nativeShutdown(handle);
        } catch (Throwable e) {}
    }

    public static void stop(long handle) {
        try {
            nativeStop(handle);
        } catch (Throwable e) {}
    }
}
