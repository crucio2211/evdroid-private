/* evv_rate.c - micro-JNI bridge for the Apple-engine 16 kHz experiment.
 *
 * Calls eciNewAudioFormat2(session, format) with a zeroed ECIaudioFormat
 * carrying only the requested rate at offset +8 (u64), which is where
 * SynthThread::newAudioFormat reads it (observed: [fmt+8] compared against
 * 22050). Everything else is left zero so replaceDefaultFieldsWithValues
 * fills the defaults, same as a fresh session.
 *
 * Return values (negative = bridge-side failure, never touch the session):
 *   -999 null session   -998 null hECI   -997 no libeci
 *   -996 no eciNewAudioFormat2 export
 * Otherwise the engine's own return (Apple ECI signals errors in bit 63).
 *
 * The caller validates with a calibration synth (PCM length ratio) and
 * reopens the session on any failure, so a refused format can never break
 * normal 11 kHz output. See AppleEngine.kt probe16k().
 */

#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdint.h>

static void *g_libeci = 0;
static int (*pNewAudioFormat2)(void *, void *) = 0;

JNIEXPORT jint JNICALL
Java_org_evvdroid_AppleEngine_nativeNewAudioFormat(JNIEnv *env, jclass clazz,
                                                   jlong sessionHandle, jint rateHz) {
    (void)env;
    (void)clazz;
    if (sessionHandle == 0)
        return -999;
    /* vvtts_core's SynthSession starts with void* hECI (see vvtts_core.c). */
    void *hECI = *(void **)(intptr_t)sessionHandle;
    if (!hECI)
        return -998;
    if (!g_libeci) {
        g_libeci = dlopen("libeci.so", RTLD_NOW | RTLD_GLOBAL);
        if (!g_libeci)
            return -997;
        pNewAudioFormat2 = (int (*)(void *, void *))dlsym(g_libeci, "eciNewAudioFormat2");
        if (!pNewAudioFormat2)
            return -996;
    }
    static unsigned char fmt[128];
    memset(fmt, 0, sizeof(fmt));
    *(uint64_t *)(fmt + 8) = (uint64_t)(uint32_t)rateHz;
    return pNewAudioFormat2(hECI, fmt);
}
