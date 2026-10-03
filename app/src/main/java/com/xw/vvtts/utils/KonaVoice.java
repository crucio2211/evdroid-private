package com.xw.vvtts.utils;

/**
 * Apple Kona Eloquence role table — vendored for evvdroid Apple-engine mode.
 *
 * Source: https://github.com/GGbond2007/eloquence-tts-android
 * (src/com/xw/vvtts/utils/KonaVoice.java, MIT for code).
 *
 * 8 roles x ECI voice params:
 *   gender(0) headSize(1) pitchBase(2) pitchFluc(3) rough(4) breath(5) speed(6) vol(7)
 */
public final class KonaVoice {

    public static class Voice {
        public final String name;
        public final String nameCn;
        public final int eciVoiceNumber;
        public final int gender;
        public final int headSize;
        public final int pitchBase;
        public final int pitchFluc;
        public final int rough;
        public final int breath;
        public final int speed;
        public final int vol;
        public final int vocalTract;

        Voice(String n, String cn, int vn, int g, int h, int pb, int pf,
              int r, int b, int s, int vo, int vt) {
            name = n; nameCn = cn; eciVoiceNumber = vn;
            gender = g; headSize = h; pitchBase = pb; pitchFluc = pf;
            rough = r; breath = b; speed = s; vol = vo; vocalTract = vt;
        }

        public int param(int eciParam) {
            switch (eciParam) {
                case 0: return gender;
                case 1: return headSize;
                case 2: return pitchBase;
                case 3: return pitchFluc;
                case 4: return rough;
                case 5: return breath;
                case 6: return speed;
                case 7: return vol;
                default: return 50;
            }
        }
    }

    /** Preset 1-8 order: Reed, Shelley, Sandy, Rocko, Flo, Grandma, Grandpa, Eddy */
    public static final Voice[] VOICES = {
        new Voice("Reed", "Reed", 1, 0, 50, 65, 30, 0, 0, 50, 92, 0),
        new Voice("Shelley", "Shelley", 2, 1, 50, 81, 30, 0, 50, 50, 95, 1),
        new Voice("Sandy", "Sandy", 3, 1, 22, 93, 30, 0, 50, 50, 95, 1),
        new Voice("Rocko", "Rocko", 4, 0, 86, 56, 47, 0, 0, 50, 93, 0),
        new Voice("Flo", "Flo", 6, 1, 56, 89, 35, 0, 40, 50, 95, 1),
        new Voice("Grandma", "Grandma", 7, 1, 45, 68, 30, 3, 40, 50, 90, 1),
        new Voice("Grandpa", "Grandpa", 8, 0, 30, 61, 44, 18, 20, 50, 90, 0),
        new Voice("Eddy", "Eddy", 9, 0, 50, 69, 34, 0, 0, 50, 92, 0),
    };

    private KonaVoice() {}

    public static Voice byPreset(int n) {
        if (n < 1 || n > 8) n = 1;
        return VOICES[n - 1];
    }

    public static Voice byEciVoiceNumber(int vn) {
        for (Voice v : VOICES) if (v.eciVoiceNumber == vn) return v;
        return VOICES[0];
    }
}
