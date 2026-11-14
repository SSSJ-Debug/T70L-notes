package com.lm.xiaozhi;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * XzClient —— 一条 WebSocket = 一段会话（上下文在服务端按会话保存）
 *
 * 与小智官方固件同协议（wss://api.tenclass.net/xiaozhi/v1/）：
 *   hello → 服务端回 session_id + 音频参数
 *   listen{state:start, mode:manual} → 二进制 Opus 帧 → listen{state:stop}
 *   ← stt（识别） / tts{sentence_start,text} / 二进制 Opus 音频 / tts{state:stop}
 *
 * 关键点：**同一个连接里可以来回多轮**，服务端会记住前面说过的话（实测 5 轮不掉上下文）。
 * 所以"长对话"不需要新开连接，开一次聊到底。
 */
public class XzClient {
    static final String TAG = "XzClient";
    static final String HOST = "api.tenclass.net";
    static final int PORT = 443;
    static final String PATH = "/xiaozhi/v1/";

    // T70L 的身份（已绑定 xiaozhi.me，勿改）
    static final String DEVICE_ID = "02:00:00:00:00:00";  // 设备 MAC，请改成你自己的
    static final String CLIENT_ID = "YOUR_XIAOZHI_CLIENT_ID";  // 小智开放平台申请，见 README
    static final String TOKEN = "test-token";

    // ★ 2026-11-07 19:30 上行格式实验开关 ★
    //   hello 里的 audio_params.format 是**我们自己声明**的，服务端按它解码上行音频。
    //   本轮实测：这台 A83T 跑 Dalvik + 纯 Java Concentus，Opus 编码稳定 71ms/帧，
    //   而一帧只有 60ms 音频 —— 天生追不上实时，队列必然积压，收尾必等 1.2 秒。
    //   降到 complexity=0 也只从 76 降到 71，说明不是参数问题，是解释器太慢。
    //   PCM 上行（16kHz/16bit = 32KB/s）在 WiFi 上毫无压力，且编码开销直接归零。
    //   服务端不认 pcm 就改回 "opus"，一个字符串的事。
    static volatile String UP_FMT = "pcm";

    /** 上行是否裸 PCM（不吃 Opus 编码） */
    public boolean rawPcm() { return "pcm".equals(UP_FMT); }

    public interface Callbacks {
        void onStt(String text);
        void onAnswerText(String text);
        void onAudio(byte[] pcm16);
        void onTtsStop();
        void onClosed(String why);
    }

    private final Callbacks cb;
    private SSLSocket sock;
    private InputStream in;
    private OutputStream out;
    private final SecureRandom rnd = new SecureRandom();
    private volatile boolean open = false;
    private volatile boolean reading = false;
    private String sessionId;
    private int decodeRate = 24000;
    private OpusCodec codec;

    public XzClient(Callbacks cb) {
        this.cb = cb;
    }

    public boolean isOpen() { return open; }
    /**
     * ★ 2026-11-07 20:55 新增：最后一次从服务端收到任何帧的时刻。
     *
     * 用户主诉"过一会儿不叫它，再在远处喊就不灵了"，最大嫌疑是**连接假死**：
     * WiFi 路由器的 NAT 表空闲几分钟就回收，而这条 WebSocket 上我们从不发心跳，
     * 只被动等云端 ping。如果云端也不发，连接会静默死掉 ——
     * 本地 `open` 还是 true，音频发出去进了黑洞，这一句就"像没听见"。
     * 记录下来只为诊断（开口日志里会带"静默 Xs"），不改任何行为。
     */
    public volatile long lastRxAt = 0;
    public int decodeRate() { return decodeRate; }
    public OpusCodec codec() { return codec; }
    public String sessionId() { return sessionId; }

    /** 建连 + 握手。成功返回 true（同步，约 0.3~1s，只在开场或断线重连时发生） */
    public boolean open() {
        try {
            TrustManager[] tm = new TrustManager[]{ new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tm, new SecureRandom());
            SSLSocketFactory f = ctx.getSocketFactory();
            long t0 = System.currentTimeMillis();
            // ★★ 2026-11-08 17:40 连接必须带超时 ★★
            //   原来直接 f.createSocket(HOST, PORT)：默认走内核 TCP 超时，
            //   网络一抖（丢包 / 路由器 NAT 表满）就要等 1~2 分钟才抛异常。
            //   而 ensureClient() 是在主循环线程里被调用（补连兜底），
            //   等于主循环陪着一起等 —— 这段时间喊什么都没反应，
            //   正是用户说的"过一会儿叫它就不灵"。这里压到 8 秒。
            java.net.Socket raw = new java.net.Socket();
            raw.connect(new java.net.InetSocketAddress(HOST, PORT), 8000);
            raw.setTcpNoDelay(true);
            sock = (SSLSocket) f.createSocket(raw, HOST, PORT, true);
            sock.setSoTimeout(12000);
            sock.setTcpNoDelay(true);
            sock.startHandshake();
            in = sock.getInputStream();
            out = sock.getOutputStream();

            Map<String, String> headers = new LinkedHashMap<String, String>();
            headers.put("Authorization", "Bearer " + TOKEN);
            headers.put("Protocol-Version", "1");
            headers.put("Device-Id", DEVICE_ID);
            headers.put("Client-Id", CLIENT_ID);

            String key = b64(randomBytes(16));
            StringBuilder req = new StringBuilder();
            req.append("GET ").append(PATH).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(HOST).append("\r\n");
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            for (Map.Entry<String, String> e : headers.entrySet()) {
                req.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            }
            req.append("\r\n");
            out.write(req.toString().getBytes("UTF-8"));
            out.flush();

            String status = readLine();
            String line;
            while ((line = readLine()) != null && line.length() > 0) { /* 跳过响应头 */ }
            if (status == null || !status.contains("101")) {
                Log.w(TAG, "握手失败: " + status);
                return false;
            }

            sendText("{\"type\":\"hello\",\"version\":1,\"transport\":\"websocket\","
                    + "\"audio_params\":{\"format\":\"" + UP_FMT
                    + "\",\"sample_rate\":16000,\"channels\":1,\"frame_duration\":60}}");

            long end = System.currentTimeMillis() + 10000;
            while (System.currentTimeMillis() < end) {
                Object[] fr = recvFrame();
                if (fr == null) break;
                if ((Integer) fr[0] == 1) {
                    String s = new String((byte[]) fr[1], "UTF-8");
                    if ("hello".equals(str(s, "type"))) {
                        sessionId = str(s, "session_id");
                        int rate = intOf(s, "sample_rate", 24000);
                        if (rate != 16000 && rate != 24000 && rate != 48000 && rate != 8000 && rate != 12000) rate = 16000;
                        decodeRate = rate;
                        break;
                    }
                }
            }
            if (sessionId == null) {
                Log.w(TAG, "没收到服务端 hello");
                close();
                return false;
            }
            if (codec == null || codec.decodeRate() != decodeRate) codec = new OpusCodec(decodeRate);
            try { sock.setSoTimeout(0); } catch (Throwable e) {}   // 之后靠 close() 打断，不靠超时
            try { sock.setSoTimeout(0); } catch (Throwable e) {}   // 之后靠 close() 打断，不靠超时
            open = true;
            Log.i(TAG, "会话建立 session=" + sessionId + " 下行采样率=" + decodeRate
                    + " 用时 " + (System.currentTimeMillis() - t0) + "ms");

            reading = true;
            Thread t = new Thread(new Runnable() {
                @Override public void run() { readLoop(); }
            }, "xz-reader");
            t.setDaemon(true);
            t.start();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "连接失败", e);
            close();
            return false;
        }
    }

    /** 开始这一轮听（manual：由我们发 stop 决定说话结束） */
    public void listenStart() {
        try {
            sendText("{\"session_id\":\"" + sessionId + "\",\"type\":\"listen\",\"state\":\"start\",\"mode\":\"manual\"}");
        } catch (Throwable e) { Log.w(TAG, "listenStart 失败"); }
    }

    /**
     * ★ 2026-11-08：主动发一帧 ping 保活。
     *
     * 为什么必须自己发：家里路由器的 NAT 表空闲几分钟就回收 TCP 映射，
     * 而这条 WebSocket 上我们以前从不发心跳，只被动等云端 ping。
     * 云端要是也不发，连接就静默死掉 —— 本地 open 还是 true，
     * 音频发出去进了黑洞，表现就是"过一夜再喊它，第一句完全没反应"。
     * 40 秒一次足够让 NAT 表项一直新鲜。
     */
    public void sendPing() {
        if (!open) return;
        try { sendFrame(0x9, new byte[0]); } catch (Throwable e) { Log.w(TAG, "心跳发送失败"); }
    }

    public void sendAudio(byte[] opus) {
        if (!open || opus == null) return;
        try { sendBinary(opus); } catch (Throwable e) { Log.w(TAG, "发音频失败"); }
    }

    public void listenStop() {
        if (!open) return;
        try {
            sendText("{\"session_id\":\"" + sessionId + "\",\"type\":\"listen\",\"state\":\"stop\"}");
        } catch (Throwable e) { Log.w(TAG, "listenStop 失败"); }
    }

    /** 纯文字提问（调试/兜底用，发音频不方便时走这条） */
    public void askText(String text) {
        if (!open || text == null) return;
        try {
            sendText("{\"session_id\":\"" + sessionId + "\",\"type\":\"listen\",\"state\":\"detect\","
                    + "\"text\":\"" + esc(text) + "\"}");
        } catch (Throwable e) { Log.w(TAG, "askText 失败"); }
    }

    /** 打断/撤销这一轮回答（喊了不是唤醒词的话时用，别让云端继续说下去） */
    public void abort() {
        if (!open) return;
        try {
            sendText("{\"session_id\":\"" + sessionId + "\",\"type\":\"abort\",\"reason\":\"wake_word_detected\"}");
        } catch (Throwable e) { }
    }

    public void close() {
        open = false;
        reading = false;
        try { if (sock != null) sock.close(); } catch (Throwable e) {}
        sock = null; in = null; out = null; sessionId = null;
    }

    // ───────── 内部 ─────────

    private void readLoop() {
        String why = "连接结束";
        try {
            while (reading && open) {
                Object[] fr = recvFrame();
                if (fr == null) { why = "对端关闭"; break; }
                lastRxAt = System.currentTimeMillis();
                int op = (Integer) fr[0];
                if (op == 8) { why = "收到 close"; break; }
                if (op == 2) {
                    byte[] pcm = codec != null ? codec.decode((byte[]) fr[1]) : null;
                    if (pcm != null && pcm.length > 0) cb.onAudio(pcm);
                    continue;
                }
                if (op == 1) {
                    String s = new String((byte[]) fr[1], "UTF-8");
                    String type = str(s, "type");
                    if ("stt".equals(type)) {
                        String t = str(s, "text");
                        if (t != null && t.length() > 0) cb.onStt(t);
                    } else if ("tts".equals(type)) {
                        String state = str(s, "state");
                        if ("sentence_start".equals(state)) {
                            String t = str(s, "text");
                            if (t != null && t.length() > 0) cb.onAnswerText(t);
                        } else if ("stop".equals(state)) {
                            cb.onTtsStop();
                        }
                    }
                }
            }
        } catch (Throwable e) {
            why = e.toString();
        } finally {
            boolean wasOpen = open;
            open = false;
            if (wasOpen) cb.onClosed(why);
        }
    }

    private void sendText(String s) throws Exception {
        sendFrame(0x1, s.getBytes("UTF-8"));
    }

    private void sendBinary(byte[] b) throws Exception {
        sendFrame(0x2, b);
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws Exception {
        if (out == null) return;
        byte[] mask = randomBytes(4);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0x80 | opcode);
        long len = payload.length;
        if (len < 126) {
            bos.write(0x80 | (int) len);
        } else if (len < 65536) {
            bos.write(0x80 | 126);
            bos.write((int) ((len >> 8) & 0xff));
            bos.write((int) (len & 0xff));
        } else {
            bos.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) bos.write((int) ((len >> (8 * i)) & 0xff));
        }
        bos.write(mask);
        for (int i = 0; i < payload.length; i++) bos.write(payload[i] ^ mask[i & 3]);
        out.write(bos.toByteArray());
        out.flush();
    }

    private Object[] recvFrame() throws Exception {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;
        int opcode = b0 & 0x0f;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7f;
        if (len == 126) {
            len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            for (int i = 0; i < 4; i++) mask[i] = (byte) in.read();
        }
        byte[] payload = new byte[(int) len];
        int off = 0;
        while (off < len) {
            int n = in.read(payload, off, (int) (len - off));
            if (n < 0) break;
            off += n;
        }
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        if (opcode == 9) {          // ping → pong
            ByteArrayOutputStream pb = new ByteArrayOutputStream();
            pb.write(0x8A);
            pb.write(payload.length);
            pb.write(payload);
            synchronized (this) { out.write(pb.toByteArray()); out.flush(); }
            return recvFrame();
        }
        return new Object[]{opcode, payload};
    }

    private String readLine() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) return bos.size() == 0 ? null : new String(bos.toByteArray(), "UTF-8");
            if (prev == '\r' && c == '\n') {
                byte[] b = bos.toByteArray();
                return new String(b, 0, b.length - 1, "UTF-8");
            }
            bos.write(c);
            prev = c;
        }
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    private static String b64(byte[] b) {
        return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
    }

    static String str(String json, String key) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return null;
        int colon = json.indexOf(':', i);
        if (colon < 0) return null;
        int start = colon + 1;
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '"')) start++;
        int end = json.indexOf('"', start);
        if (end < 0) end = json.length();
        return json.substring(start, end);
    }

    static String esc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') sb.append("\\\"");
            else if (c == '\\') sb.append("\\\\");
            else if (c == '\n') sb.append("\\n");
            else sb.append(c);
        }
        return sb.toString();
    }

    static int intOf(String json, String key, int def) {
        String k = "\"" + key + "\"";
        int i = json.indexOf(k);
        if (i < 0) return def;
        int colon = json.indexOf(':', i);
        if (colon < 0) return def;
        int start = colon + 1;
        while (start < json.length() && !Character.isDigit(json.charAt(start))) start++;
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Throwable e) { return def; }
    }
}
