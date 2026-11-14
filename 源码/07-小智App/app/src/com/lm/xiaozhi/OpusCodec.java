package com.lm.xiaozhi;

import org.concentus.OpusApplication;
import org.concentus.OpusDecoder;
import org.concentus.OpusEncoder;
import org.concentus.OpusException;

/**
 * Opus 编解码（纯 Java，concentus）
 *
 * 上传：16kHz 单声道 / 60ms 帧（960 采样），24kbps VOIP，complexity=1（A7 上最省）
 * 下行：按服务端 hello 里握手的采样率解码（官方服务器是 24000，比 16k 好听不少）
 */
public class OpusCodec {
    public static final int ENC_RATE = 16000;
    public static final int FRAME_MS = 60;
    public static final int ENC_FRAME = ENC_RATE * FRAME_MS / 1000;   // 960 采样

    private final OpusEncoder encoder;
    private final OpusDecoder decoder;
    private final int decRate;

    public OpusCodec(int decSampleRate) {
        this.decRate = decSampleRate > 0 ? decSampleRate : 16000;
        try {
            encoder = new OpusEncoder(ENC_RATE, 1, OpusApplication.OPUS_APPLICATION_VOIP);
            // ★ 2026-11-07 19:20：complexity 1 → 0，24kbps → 16kbps。
            //   A83T 上纯 Java 编码是硬瓶颈，complexity 0 砍掉的是音质最不敏感的
            //   那部分搜索（pitch 细化 / 多档量化），对 ASR 识别率影响可忽略，
            //   但对这颗 CPU 是实打实的省。16kbps VOIP 对 16kHz 语音本来就够。
            encoder.setComplexity(0);
            encoder.setBitrate(16000);
            decoder = new OpusDecoder(decRate, 1);
        } catch (OpusException e) {
            throw new RuntimeException("Opus 初始化失败", e);
        }
    }

    public int decodeRate() { return decRate; }

    /** PCM16(960 采样) → Opus；失败返回 null */
    public byte[] encode(byte[] pcm16) {
        if (pcm16 == null || pcm16.length < ENC_FRAME * 2) return null;
        byte[] out = new byte[1500];
        try {
            int n = encoder.encode(pcm16, 0, ENC_FRAME, out, 0, out.length);
            if (n <= 0) return null;
            byte[] r = new byte[n];
            System.arraycopy(out, 0, r, 0, n);
            return r;
        } catch (OpusException e) {
            return null;
        }
    }

    /** Opus → PCM16（按 decRate）；失败返回 null */
    public byte[] decode(byte[] opus) {
        if (opus == null || opus.length == 0) return null;
        int maxSamples = decRate * 120 / 1000;          // 最多 120ms
        short[] pcm = new short[maxSamples];
        try {
            int n = decoder.decode(opus, 0, opus.length, pcm, 0, maxSamples, false);
            if (n <= 0) return null;
            byte[] out = new byte[n * 2];
            for (int i = 0; i < n; i++) {
                out[i * 2] = (byte) (pcm[i] & 0xff);
                out[i * 2 + 1] = (byte) ((pcm[i] >> 8) & 0xff);
            }
            return out;
        } catch (OpusException e) {
            return null;
        }
    }
}
