// mirrord —— Mac 端屏幕发射器（T70L 扩展屏的源端）
// =====================================================
// 为什么自己写：这台 Mac 是 macOS 27，老的 CGDisplayStream / screencapture 轮询都太慢，
// 只有 ScreenCaptureKit 能拿到实时帧。实测 28fps、单帧 JPEG 编码 9.5ms，够用。
//
// 协议（极简，T70L 端 Java 十行就能解）：
//   [4 字节小端长度][N 字节 JPEG]  循环
//
// 设计原则：
//   ① 只保留「最新一帧」——网络慢就丢旧帧，宁可少帧也不能延迟越积越大
//   ② 限帧 15fps、质量 0.45，单帧约 60~100KB，WiFi 占用约 1MB/s
//   ③ 单客户端，重连即接管
//
// 编译：./build.sh
// 运行：./mirrord

import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo
import CoreImage
import ImageIO
import Darwin

let PORT: UInt16 = 7200
let OUT_W = 1280          // 与 T70L 屏幕同宽，像素级对得上
let OUT_H = 720           // 16:9 不变形，平板上下各留 40px 黑边
let FPS = 15
let QUALITY = 0.45

// ───────────────────────── 帧中转 ─────────────────────────

/// 只保存最新一帧。发送端慢的时候直接覆盖，保证「实时」优先于「完整」。
final class Hub {
    private let lock = NSLock()
    private var frame: Data?
    private let sem = DispatchSemaphore(value: 0)

    func push(_ d: Data) {
        lock.lock()
        frame = d
        lock.unlock()
        sem.signal()
    }

    func take() -> Data? {
        sem.wait()
        lock.lock()
        defer { lock.unlock() }
        let d = frame
        frame = nil
        return d
    }
}

// ───────────────────────── 采集 ─────────────────────────

final class Capture: NSObject, SCStreamOutput {
    private let ctx = CIContext(options: [.useSoftwareRenderer: false])
    private let hub: Hub
    private var encoded = 0

    init(hub: Hub) {
        self.hub = hub
    }

    func stream(_ stream: SCStream,
                didOutputSampleBuffer sb: CMSampleBuffer,
                of type: SCStreamOutputType) {
        guard type == .screen, let pb = CMSampleBufferGetImageBuffer(sb) else { return }

        let ci = CIImage(cvPixelBuffer: pb)
        guard let cg = ctx.createCGImage(ci, from: ci.extent) else { return }

        let data = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil) else { return }
        CGImageDestinationAddImage(dest, cg, [
            kCGImageDestinationLossyCompressionQuality: QUALITY
        ] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return }

        encoded += 1
        hub.push(data as Data)
    }
}

// ───────────────────────── 网络 ─────────────────────────

func writeAll(_ fd: Int32, _ p: UnsafeRawPointer, _ n: Int) -> Bool {
    var off = 0
    while off < n {
        let w = Darwin.send(fd, p.advanced(by: off), n - off, 0)
        if w <= 0 { return false }
        off += w
    }
    return true
}

func sendLoop(_ fd: Int32, _ hub: Hub) {
    var frames = 0
    var bytes = 0
    let t0 = Date()

    while true {
        guard let d = hub.take() else { continue }

        var n = UInt32(d.count).littleEndian
        var ok = withUnsafeBytes(of: &n) { raw -> Bool in
            writeAll(fd, raw.baseAddress!, raw.count)
        }
        if ok {
            ok = d.withUnsafeBytes { raw -> Bool in
                writeAll(fd, raw.baseAddress!, raw.count)
            }
        }
        if !ok { break }

        frames += 1
        bytes += d.count
        if frames % 60 == 0 {
            let el = max(Date().timeIntervalSince(t0), 0.001)
            print("  已发 \(frames) 帧 · \(String(format: "%.1f", Double(frames) / el)) fps · 平均 \(bytes / frames / 1024) KB/帧")
        }
    }

    close(fd)
    print("客户端断开，等待重连…")
}

func localWiFiIP() -> String {
    var addrs: UnsafeMutablePointer<ifaddrs>?
    guard getifaddrs(&addrs) == 0, let first = addrs else { return "?" }
    defer { freeifaddrs(addrs) }

    var fallback = "?"
    var p: UnsafeMutablePointer<ifaddrs>? = first
    while let cur = p {
        let a = cur.pointee
        if let sa = a.ifa_addr, sa.pointee.sa_family == UInt8(AF_INET) {
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(sa, socklen_t(sa.pointee.sa_len), &host,
                           socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: host)
                let name = String(cString: a.ifa_name)
                if ip != "127.0.0.1" && name.hasPrefix("en") { return ip }
                if ip != "127.0.0.1" && fallback == "?" { fallback = ip }
            }
        }
        p = a.ifa_next
    }
    return fallback
}

func acceptLoop(_ hub: Hub) {
    let fd = socket(AF_INET, SOCK_STREAM, 0)
    guard fd >= 0 else {
        print("socket 创建失败")
        return
    }

    var yes: Int32 = 1
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &yes, socklen_t(MemoryLayout<Int32>.size))

    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_port = PORT.bigEndian
    addr.sin_addr.s_addr = INADDR_ANY

    let br = withUnsafePointer(to: &addr) { p -> Int32 in
        p.withMemoryRebound(to: sockaddr.self, capacity: 1) {
            bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
        }
    }
    guard br == 0 else {
        print("端口 \(PORT) 绑定失败，errno=\(errno)")
        return
    }
    listen(fd, 4)

    print("已监听 0.0.0.0:\(PORT)")
    print("平板端填这个地址： \(localWiFiIP())")
    print("等待平板连接…")

    while true {
        var caddr = sockaddr()
        var len = socklen_t(MemoryLayout<sockaddr>.size)
        let cfd = accept(fd, &caddr, &len)
        if cfd < 0 { continue }
        print("平板已连接，开始推流")
        DispatchQueue.global().async {
            sendLoop(cfd, hub)
        }
    }
}

// ───────────────────────── 入口 ─────────────────────────

@main
struct Mirror {
    // SCStream 对 output 只持弱引用，Capture 和 stream 自己在作用域里活不过函数，
    // 一旦被释放采集回调就永远不再触发（表现是连上了但一帧都没有）。必须自己攥住。
    nonisolated(unsafe) static var keepAlive: [AnyObject] = []

    static func main() async {
        // 客户端异常断开时不要把整个进程带走
        signal(SIGPIPE, SIG_IGN)

        let hub = Hub()

        do {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: true)
            guard let d = content.displays.first else {
                print("找不到显示器")
                return
            }
            print("源显示器 \(d.width)x\(d.height) → 输出 \(OUT_W)x\(OUT_H) @\(FPS)fps 质量 \(QUALITY)")

            let filter = SCContentFilter(display: d, excludingWindows: [])
            let cfg = SCStreamConfiguration()
            cfg.width = OUT_W
            cfg.height = OUT_H
            cfg.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(FPS))
            cfg.pixelFormat = kCVPixelFormatType_32BGRA
            cfg.queueDepth = 3
            cfg.showsCursor = true

            let cap = Capture(hub: hub)
            let stream = SCStream(filter: filter, configuration: cfg, delegate: nil)
            try stream.addStreamOutput(cap, type: .screen, sampleHandlerQueue: DispatchQueue(label: "cap"))
            try await stream.startCapture()
            keepAlive.append(cap)
            keepAlive.append(stream)
            print("采集已启动")
        } catch {
            print("启动采集失败：\(error)")
            print("提示：需要在「系统设置 → 隐私与安全性 → 屏幕录制」里允许本程序")
            return
        }

        // 阻塞在 accept 上；采集回调在独立队列，不受影响
        acceptLoop(hub)
    }
}
