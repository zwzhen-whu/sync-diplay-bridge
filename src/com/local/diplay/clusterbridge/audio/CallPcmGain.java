package com.local.diplay.clusterbridge.audio;

/** PCM16 call gain: volume 7 is unity; boost is bounded to +6 dB with saturating limiting. */
public final class CallPcmGain {
    public static final int UNITY_VOLUME = 7;
    public static final float MAX_GAIN = (float)Math.pow(10, 6.0 / 20);
    private CallPcmGain() { }

    public static float gain(int volume, int maximum, boolean muted) {
        if (maximum <= 0) return 1f;
        if (muted || volume <= 0) return 0f;
        int level = Math.min(volume, maximum), unity = Math.min(UNITY_VOLUME, maximum);
        if (level <= unity) return level / (float)unity;
        return (float)Math.pow(10, 6.0 * (level - unity) / (maximum - unity) / 20);
    }

    public static void apply(byte[] pcm, int offset, int length, float gain) {
        if (pcm == null || offset < 0 || length < 0 || offset > pcm.length - length
                || (length & 1) != 0 || (offset & 1) != 0
                || Float.isNaN(gain) || Float.isInfinite(gain) || gain < 0f || gain > MAX_GAIN || gain == 1f) return;
        int end = offset + length;
        for (int i = offset; i < end; i += 2) {
            int sample = (short)((pcm[i] & 255) | (pcm[i + 1] << 8));
            int scaled = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.round(sample * gain)));
            pcm[i] = (byte)scaled;
            pcm[i + 1] = (byte)(scaled >> 8);
        }
    }
}
