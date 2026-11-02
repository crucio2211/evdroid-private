#!/usr/bin/env python3
"""Smoke-test Finnish and Brazilian Portuguese language switching.

The test creates an English instance, selects each added language, reads all
eight voice names, synthesizes a short native-language sentence, and returns
to English. This catches a language table that compiles but faults when a
screen reader selects the language or requests its voice data.
"""

import ctypes
import os
import sys

FRAME = 2048
PARAM_INPUT_TYPE = 1
PARAM_LANGUAGE = 9

LANGUAGES = {
    0x70000: ("Brazilian Portuguese", b"O rato roeu a roupa."),
    0x90000: ("Finnish", b"Hei maailma."),
}


def main(path):
    dll = ctypes.CDLL(os.path.abspath(path))
    print("switchlangs: loaded library", flush=True)

    dll.eciNewEx.restype = ctypes.c_void_p
    dll.eciNewEx.argtypes = [ctypes.c_int]
    dll.eciDelete.argtypes = [ctypes.c_void_p]
    dll.eciGetAvailableLanguages.argtypes = [
        ctypes.c_void_p, ctypes.POINTER(ctypes.c_int)]
    dll.eciRegisterCallback.argtypes = [
        ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p]
    dll.eciRegisterCallback.restype = None
    dll.eciSetOutputBuffer.argtypes = [
        ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
    dll.eciSetParam.argtypes = [ctypes.c_void_p, ctypes.c_int, ctypes.c_int]
    dll.eciGetVoiceName.argtypes = [
        ctypes.c_void_p, ctypes.c_int, ctypes.c_char_p]
    dll.eciAddText.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
    dll.eciSynthesize.argtypes = [ctypes.c_void_p]
    dll.eciSynchronize.argtypes = [ctypes.c_void_p]

    count = ctypes.c_int()
    if dll.eciGetAvailableLanguages(None, ctypes.byref(count)):
        raise RuntimeError("could not enumerate engine languages")
    found = (ctypes.c_uint * count.value)()
    if dll.eciGetAvailableLanguages(found, ctypes.byref(count)):
        raise RuntimeError("could not read available engine languages")
    available = set(found[:count.value])
    missing = set(LANGUAGES) - available
    if missing:
        raise RuntimeError(
            "missing language IDs: " +
            ", ".join(hex(value) for value in sorted(missing)))
    print("switchlangs: available languages %s" %
          sorted(hex(value) for value in available), flush=True)

    handle = dll.eciNewEx(0x10000)
    if not handle:
        raise RuntimeError("could not create English engine instance")
    print("switchlangs: created English instance", flush=True)

    buffer = (ctypes.c_short * FRAME)()
    spoken = 0
    callback_type = ctypes.CFUNCTYPE(
        ctypes.c_int, ctypes.c_void_p, ctypes.c_int, ctypes.c_int,
        ctypes.c_void_p)

    @callback_type
    def callback(_handle, message, samples, _data):
        nonlocal spoken
        if message == 0:
            spoken += samples
        return 1

    try:
        dll.eciRegisterCallback(handle, callback, None)
        if not dll.eciSetOutputBuffer(handle, FRAME, buffer):
            raise RuntimeError("engine refused the output buffer")
        if dll.eciSetParam(handle, PARAM_INPUT_TYPE, 1) < 0:
            raise RuntimeError("engine refused the input type")

        for language, (name, sentence) in LANGUAGES.items():
            spoken = 0
            print("switchlangs: selecting " + name, flush=True)
            if dll.eciSetParam(handle, PARAM_LANGUAGE, language) < 0:
                raise RuntimeError("engine refused " + name)
            print("switchlangs: selected " + name, flush=True)

            for voice in range(1, 9):
                voice_name = ctypes.create_string_buffer(64)
                dll.eciGetVoiceName(handle, voice, voice_name)
                if not voice_name.value:
                    raise RuntimeError(
                        "engine returned an empty voice name for %s voice %d"
                        % (name, voice))
            print("switchlangs: read voices for " + name, flush=True)

            if not dll.eciAddText(handle, sentence):
                raise RuntimeError("engine refused text for " + name)
            if not dll.eciSynthesize(handle) or not dll.eciSynchronize(handle):
                raise RuntimeError("engine failed to synthesize " + name)
            if spoken == 0:
                raise RuntimeError("engine produced no audio for " + name)
            print("switchlangs: %s spoke %d samples" % (name, spoken),
                  flush=True)

            if dll.eciSetParam(handle, PARAM_LANGUAGE, 0x10000) < 0:
                raise RuntimeError("engine could not return to English")
            print("switchlangs: returned to English", flush=True)
    finally:
        dll.eciDelete(handle)
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: switchlangs.py <libeci.so>")
    try:
        raise SystemExit(main(sys.argv[1]))
    except Exception as exc:
        print("switchlangs: %s" % exc, file=sys.stderr)
        raise
