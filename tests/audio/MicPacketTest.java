package com.local.diplay.clusterbridge.audio;

public final class MicPacketTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        for (String type : new String[] {"telephony", "speechrecognition", "default", "compatibility"}) {
            int result = MicPacketPolicy.frameMillis(true, "LPCM", type, 48000, 1, 20, false);
            check(result == 10);
            check(48000 * result / 1000 == 480); // RTP clock on the original 48 kHz PCM clock.
            check(48000 * result / 1000 * 2 + 84 == 1044); // Full encrypted IPv6 packet fits 1500.
            check(MicPacketPolicy.frameMillis(false, "LPCM", type, 48000, 1, 20, false) == 20);
            check(MicPacketPolicy.frameMillis(true, "OPUS", type, 48000, 1, 20, false) == 20);
            check(MicPacketPolicy.frameMillis(true, "LPCM", type, 48000, 1, 20, true) == 20);
        }
        for (int rate : new int[] {8000, 16000, 24000, 32000, 44100, 96000, 0, -1})
            check(MicPacketPolicy.frameMillis(true, "LPCM", "telephony", rate, 1, 20, false) == 20);
        for (int channels : new int[] {0, 2, 6})
            check(MicPacketPolicy.frameMillis(true, "LPCM", "telephony", 48000, channels, 20, false) == 20);
        for (String type : new String[] {"media", "alert", "", null})
            check(MicPacketPolicy.frameMillis(true, "LPCM", type, 48000, 1, 20, false) == 20);
        for (int ms : new int[] {-1, 0, 5, 10, 15, 40, 60})
            check(MicPacketPolicy.frameMillis(true, "LPCM", "telephony", 48000, 1, ms, false) == ms);
        check(MicPacketPolicy.lease(true, 16000, 1000, 42, 42));
        check(!MicPacketPolicy.lease(true, 16001, 1000, 42, 42));
        check(!MicPacketPolicy.lease(true, 1000, 1000, 42, 42));
        check(!MicPacketPolicy.lease(false, 16000, 1000, 42, 42));
        check(!MicPacketPolicy.lease(true, 16000, 1000, 42, 43));
        check(!MicPacketPolicy.lease(true, 16000, -1, 42, 42));
        check(MicPacketPolicy.owns("wired-mic-packet-2", "com.local.diplay.clusterbridge", 10088, 10088, 32, 32, "100", "100"));
        check(!MicPacketPolicy.owns("call-volume-3", "com.local.diplay.clusterbridge", 10088, 10088, 32, 32, "100", "100"));
        check(!MicPacketPolicy.owns("wired-mic-packet-2", "com.local.diplay.clusterbridge", 10088, 10088, 31, 32, "100", "100"));
        check(!MicPacketPolicy.owns("wired-mic-packet-2", "com.local.diplay.clusterbridge", 10088, 10088, 32, 32, "100", "101"));
        check(!MicPacketPolicy.owns("wired-mic-packet-2", "other", 10088, 10088, 32, 32, "100", "100"));
        System.out.println("MicPacketTest: " + checks + " checks passed");
    }
}
