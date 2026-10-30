package com.local.diplay.clusterbridge.audio;

import java.util.Arrays;

public final class CallAudioTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    private static short value(byte[] bytes, int at) { return (short)((bytes[at] & 255) | (bytes[at + 1] << 8)); }
    public static void main(String[] args) {
        check(CallPcmGain.gain(0, 30, false) == 0);
        check(CallPcmGain.gain(7, 30, false) == 1);
        check(Math.abs(20 * Math.log10(CallPcmGain.gain(30, 30, false)) - 6) < 0.00001);
        check(CallPcmGain.gain(60, 30, false) == CallPcmGain.MAX_GAIN);
        check(CallPcmGain.gain(10, 30, true) == 0);
        check(CallPcmGain.gain(10, 0, false) == 1);
        float previous = -1;
        for (int volume = 0; volume <= 30; volume++) {
            float next = CallPcmGain.gain(volume, 30, false);
            check(next >= previous && next >= 0 && next <= CallPcmGain.MAX_GAIN); previous = next;
            check(volume < 7 ? next < 1 : volume == 7 ? next == 1 : next > 1);
        }
        byte[] pcm = new byte[65536 * 2 + 4];
        pcm[0] = 19; pcm[1] = 71; pcm[pcm.length - 2] = 43; pcm[pcm.length - 1] = 12;
        for (int i = 0; i < 65536; i++) {
            int sample = i - 32768; pcm[i * 2 + 2] = (byte)sample; pcm[i * 2 + 3] = (byte)(sample >> 8);
        }
        byte[] original = pcm.clone();
        CallPcmGain.apply(pcm, 2, 65536 * 2, 1f); check(Arrays.equals(original, pcm));
        CallPcmGain.apply(pcm, 2, 65536 * 2, CallPcmGain.MAX_GAIN);
        check(pcm[0] == 19 && pcm[1] == 71 && pcm[pcm.length - 2] == 43 && pcm[pcm.length - 1] == 12);
        for (int i = 0; i < 65536; i++) {
            int expected = Math.max(-32768, Math.min(32767, Math.round((i - 32768) * CallPcmGain.MAX_GAIN)));
            check(value(pcm, i * 2 + 2) == expected);
        }
        pcm = original.clone();
        CallPcmGain.apply(pcm, 2, 65536 * 2, 0.5f);
        check(pcm[0] == 19 && pcm[1] == 71 && pcm[pcm.length - 2] == 43 && pcm[pcm.length - 1] == 12);
        for (int i = 0; i < 65536; i++) check(value(pcm, i * 2 + 2) == Math.round((i - 32768) * 0.5f));
        CallPcmGain.apply(pcm, 2, 65536 * 2, 0f);
        for (int i = 2; i < pcm.length - 2; i++) check(pcm[i] == 0);
        int[][] invalid = {{-1, 2}, {0, -1}, {1, 2}, {0, 3}, {0, Integer.MAX_VALUE}, {Integer.MAX_VALUE, 2}};
        for (int[] span : invalid) { pcm = original.clone(); CallPcmGain.apply(pcm, span[0], span[1], 0f); check(Arrays.equals(original, pcm)); }
        for (float invalidGain : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, -1f, 2f}) {
            pcm = original.clone(); CallPcmGain.apply(pcm, 0, pcm.length, invalidGain); check(Arrays.equals(original, pcm));
        }
        check(CallAudioPolicy.lease(true, 16000, 1000, 42, 42));
        check(!CallAudioPolicy.lease(true, 16001, 1000, 42, 42));
        check(!CallAudioPolicy.lease(true, 1000, 1000, 42, 42));
        check(!CallAudioPolicy.lease(false, 16000, 1000, 42, 42));
        check(!CallAudioPolicy.lease(true, 16000, 1000, 42, 43));
        check(!CallAudioPolicy.lease(true, 16000, -1, 42, 42));
        check(CallAudioPolicy.owns("call-volume-3", "com.local.diplay.clusterbridge", 10088, 10088, 32, 32, "100", "100"));
        check(!CallAudioPolicy.owns("call-volume-2", "com.local.diplay.clusterbridge", 10088, 10088, 32, 32, "100", "100"));
        check(!CallAudioPolicy.owns("call-volume-3", "com.local.diplay.clusterbridge", 10088, 10088, 31, 32, "100", "100"));
        check(!CallAudioPolicy.owns("call-volume-3", "com.local.diplay.clusterbridge", 10088, 10088, 32, 32, "100", "101"));
        check(!CallAudioPolicy.owns("call-volume-3", "com.local.diplay.homemap", 10088, 10088, 32, 32, "100", "100"));
        System.out.println("CallAudioTest: " + checks + " checks passed");
    }
}
