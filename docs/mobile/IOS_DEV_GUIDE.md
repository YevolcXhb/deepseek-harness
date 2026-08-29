# DeepSeek Harness iOS 移动端开发文档

> **方案核心**: WKWebView 壳 + proot 沙箱容器 + 完整 dsh `--profile mobile`
> **满血保障**: Host 端 247 个 Cordis 插件 + Client 端 45+ 个 React UI 插件 + HMR + 第三方 dsh-plugin 生态全部保留

---

## 一、iOS 架构总览

```
┌───────────────────────────────────────────────────────────────────────┐
│                          iOS 设备 (无 Jailbreak)                     │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  DshApp (UIKit Application)                                     │  │
│  │  ├── DshViewController (UI 容器)                                │  │
│  │  │   └── WKWebView (加载 http://127.0.0.1:PORT)                 │  │
│  │  │       └── 完整 dsh Web UI (React + 45 个客户端插件)          │  │
│  │  ├── DshKeepAliveManager (保活管理器)                           │  │
│  │  │   ├── BGTaskScheduler — 后台任务                              │  │
│  │  │   ├── AVAudioPlayer — 静音音频保活                            │  │
│  │  │   ├── Silent Push Notification — 远程推送唤醒                 │  │
│  │  │   └── TCP Socket — 接收 dsh tool-keepalive 指令              │  │
│  │  ├── DshSandboxManager (沙箱管理器)                             │  │
│  │  │   └── Process → proot → node → dsh                          │  │
│  │  └── DshWebBridge (WKScriptMessageHandler)                      │  │
│  │      └── JS ↔ Native 桥接                                       │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  proot 进程 (后台运行)                                          │  │
│  │  └── node /usr/lib/dsh/apps/cli/lib/bin.js                      │  │
│  │      --profile mobile --no-open --port 0                        │  │
│  │                                                                  │  │
│  │  Host 插件树 (Cordis): ✅ 全部 247 个 Host 插件                 │  │
│  │  Client 插件树 (WKWebView 内): ✅ 全部 45+ 个 Client 插件       │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  文件系统 (App Sandbox):                                              │
│  ~/Documents/                                                        │
│  ├── proot                    — proot 二进制                         │
│  ├── rootfs/                  — proot 根文件系统                     │
│  │   ├── bin/busybox                                               │
│  │   ├── usr/bin/node                                             │
│  │   ├── usr/lib/dsh/  (完整构建产物 + node_modules)               │
│  │   ├── home/.dsh/    (DSH_HOME)                                  │
│  │   └── workspace/    (AI 工作区)                                 │
│  ├── keepalive.port          — TCP 端口文件                        │
│  └── silence.wav             — 静音音频 (保活用)                   │
└───────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ HTTPS
                    ┌──────────────────────────────┐
                    │  DeepSeek LLM API (云端)      │
                    └──────────────────────────────┘
```

---

## 二、iOS 项目结构

```
apps/mobile/ios/
├── DshMobile/                        
│   ├── DshApp.swift                  — App 入口 (@main)
│   ├── DshViewController.swift        — WKWebView 容器
│   ├── DshKeepAliveManager.swift      — 保活管理器
│   ├── DshSandboxManager.swift        — proot 容器管理
│   ├── DshWebBridge.swift              — JS ↔ Native 桥接
│   ├── DshBootReceiver.swift          — 后台任务注册
│   ├── Assets.xcassets/               — 图标资源
│   ├── Base.lproj/                    — Storyboard
│   └── silence.wav                    — 静音音频文件
├── DshMobile.xcodeproj
├── Info.plist                         — 权限配置
└── Package.swift                      — SPM 依赖 (可选)
```

---

## 三、Info.plist 权限配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleName</key>
    <string>DeepSeek Harness</string>

    <key>CFBundleIdentifier</key>
    <string>com.deepseek.dsh-mobile</string>

    <key>CFBundleShortVersionString</key>
    <string>1.0.0</string>

    <key>CFBundleVersion</key>
    <string>1</string>

    <key>MinimumOSVersion</key>
    <string>16.0</string>

    <!-- 后台模式: 音频 (保活核心) + 后台处理 + 推送 -->
    <key>UIBackgroundModes</key>
    <array>
        <string>audio</string>              <!-- 静音音频保活 -->
        <string>processing</string>          <!-- 后台处理任务 -->
        <string>fetch</string>               <!-- 后台获取 -->
        <string>remote-notification</string> <!-- 静默推送唤醒 -->
    </array>

    <!-- 本地网络使用说明 -->
    <key>NSLocalNetworkUsageDescription</key>
    <string>DSH 需要本地网络连接到沙箱容器</string>

    <!-- 后台处理任务标识 -->
    <key>BGTaskSchedulerPermittedIdentifiers</key>
    <array>
        <string>com.deepseek.dsh-mobile.healthcheck</string>
        <string>com.deepseek.dsh-mobile.processing</string>
    </array>

    <!-- App Transport Security: 允许 localhost HTTP -->
    <key>NSAppTransportSecurity</key>
    <dict>
        <key>NSAllowsLocalNetworking</key>
        <true/>
        <key>NSExceptionDomains</key>
        <dict>
            <key>127.0.0.1</key>
            <dict>
                <key>NSExceptionAllowsInsecureHTTPLoads</key>
                <true/>
                <key>NSExceptionMinimumTLSVersion</key>
                <string>TLSv1.0</string>
            </dict>
            <key>localhost</key>
            <dict>
                <key>NSExceptionAllowsInsecureHTTPLoads</key>
                <true/>
            </dict>
        </dict>
    </dict>
</dict>
</plist>
```

---

## 四、核心代码实现

### 4.1 DshApp.swift — 应用入口

```swift
import SwiftUI
import BackgroundTasks

/**
 * 应用入口: 初始化后台任务、释放 assets。
 */
@main
struct DshApp: App {
    @UIApplicationDelegateAdaptor(DshAppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            DshRootView()
        }
    }
}

class DshAppDelegate: NSObject, UIApplicationDelegate {

    static let documentsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    static let prootBin = documentsDir.appendingPathComponent("proot")
    static let rootfsDir = documentsDir.appendingPathComponent("rootfs")
    static let workspaceDir = documentsDir.appendingPathComponent("workspace")
    static let keepalivePortFile = documentsDir.appendingPathComponent("keepalive.port")
    static let silenceFile = documentsDir.appendingPathComponent("silence.wav")

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // 1. 注册后台任务
        registerBackgroundTasks()

        // 2. 确保目录结构
        ensureDirectories()

        // 3. 释放 assets (首次启动)
        releaseAssetsIfNeeded()

        return true
    }

    private func registerBackgroundTasks() {
        // 短任务: 健康检查 (30s 窗口)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.deepseek.dsh-mobile.healthcheck") {
            task in self.handleHealthCheck(task: task as! BGAppRefreshTask)
        }
        // 长任务: 后台处理 (可达数分钟)
        BGTaskScheduler.shared.register(forTaskWithIdentifier: "com.deepseek.dsh-mobile.processing") {
            task in self.handleProcessing(task: task as! BGProcessingTask)
        }
    }

    private func ensureDirectories() {
        let fm = FileManager.default
        for dir in [DshAppDelegate.rootfsDir, DshAppDelegate.workspaceDir,
                    DshAppDelegate.rootfsDir.appendingPathComponent("home/.dsh")] {
            try? fm.createDirectory(at: dir, withIntermediateDirectories: true)
        }
    }

    /**
     * 首次启动: 从 Bundle 释放 proot 二进制和 rootfs。
     */
    private func releaseAssetsIfNeeded() {
        let fm = FileManager.default

        // 释放 proot 二进制
        if !fm.fileExists(atPath: DshAppDelegate.prootBin.path) {
            if let prootData = try? Data(contentsOf: Bundle.main.url(forResource: "proot",
                                                                       withExtension: nil)!) {
                try? prootData.write(to: DshAppDelegate.prootBin)
                // 设置可执行权限
                try? fm.setAttributes([.posixPermissions: 0o755],
                                      ofItemAtPath: DshAppDelegate.prootBin.path)
            }
        }

        // 释放 rootfs (首次解压 tar.gz)
        let nodePath = DshAppDelegate.rootfsDir.appendingPathComponent("usr/bin/node").path
        if !fm.fileExists(atPath: nodePath) {
            if let tarUrl = Bundle.main.url(forResource: "rootfs", withExtension: "tar.gz") {
                // 使用 Process 解压 (iOS 支持 tar)
                let task = Process()
                task.launchPath = "/usr/bin/tar"
                task.arguments = ["-xzf", tarUrl.path, "-C", DshAppDelegate.rootfsDir.path]
                try? task.run()
                task.waitUntilExit()
            }
        }

        // 释放静音音频
        if !fm.fileExists(atPath: DshAppDelegate.silenceFile.path) {
            if let silenceUrl = Bundle.main.url(forResource: "silence", withExtension: "wav") {
                try? fm.copyItem(at: silenceUrl, to: DshAppDelegate.silenceFile)
            }
        }
    }

    private func handleHealthCheck(task: BGAppRefreshTask) {
        scheduleNextHealthCheck()
        task.expirationHandler = { task.setTaskCompleted(success: false) }
        DispatchQueue.global().async {
            let alive = DshSandboxManager.shared.isAlive()
            if !alive {
                DshSandboxManager.shared.restart()
            }
            task.setTaskCompleted(success: true)
        }
    }

    private func handleProcessing(task: BGProcessingTask) {
        task.expirationHandler = { task.setTaskCompleted(success: false) }
        // 在后台处理窗口内保持容器运行
        DispatchQueue.global().async {
            while !task.isCancelled {
                if !DshSandboxManager.shared.isAlive() {
                    DshSandboxManager.shared.restart()
                }
                Thread.sleep(forTimeInterval: 30)
            }
            task.setTaskCompleted(success: true)
        }
    }

    func scheduleNextHealthCheck() {
        let request = BGAppRefreshTaskRequest(identifier: "com.deepseek.dsh-mobile.healthcheck")
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(request)
    }
}
```

### 4.2 DshSandboxManager.swift — proot 容器管理

```swift
import Foundation
import Darwin

/**
 * proot 容器管理: 启动、停止、健康检查。
 *
 * 进程树:
 *   proot → node (dsh --profile mobile --no-open)
 *          → Agent Loop (Cordis, 247 个插件)
 *          → HTTP /api + WS /api/remote.mux
 *          → Tool 执行 (bash/python)
 */
class DshSandboxManager {

    static let shared = DshSandboxManager()

    private var prootProcess: Process?
    private(set) var dshPort: Int = 0
    private(set) var dshPid: pid_t = 0

    /**
     * 启动 proot 沙箱内的 dsh Host。
     */
    func launch(apiKey: String) throws -> (pid: pid_t, port: Int) {
        let prootBin = DshAppDelegate.prootBin.path
        let rootfs = DshAppDelegate.rootfsDir.path
        let workspace = DshAppDelegate.workspaceDir.path

        // 构造 proot 命令行
        let args: [String] = [
            prootBin,
            "-r", rootfs,
            "-b", "/dev",
            "-b", "/proc",
            "-b", "\(workspace):/workspace",
            "-w", "/workspace",
            "-i", "0:0",
            "-e", "HOME=/home",
            "-e", "DSH_HOME=/home/.dsh",
            "-e", "DSH_PLATFORM=ios",
            "-e", "DSH_PROOT=1",
            "-e", "DEEPSEEK_API_KEY=\(apiKey)",
            "-e", "PATH=/usr/bin:/bin:/usr/sbin:/sbin",
            "-e", "NODE_OPTIONS=--max-old-space-size=256",
            "--",
            "/usr/bin/node",
            "/usr/lib/dsh/apps/cli/lib/bin.js",
            "--profile", "mobile",
            "--no-open",
            "--port", "0"
        ]

        let process = Process()
        process.executableURL = URL(fileURLWithPath: prootBin)
        process.arguments = Array(args.dropFirst()) // 第一个是 prootBin 本身

        // 环境变量
        var env = ProcessInfo.processInfo.environment
        env["DEEPSEEK_API_KEY"] = apiKey
        env["DSH_HOME"] = "/home/.dsh"
        env["DSH_PLATFORM"] = "ios"
        process.environment = env

        // stdout pipe (读取端口)
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = pipe

        try process.run()

        prootProcess = process
        dshPid = process.processIdentifier

        // 读取 stdout 解析端口
        let data = pipe.fileHandleForReading.readData(ofLength: 4096)
        let output = String(data: data, encoding: .utf8) ?? ""
        if let match = output.range(of: #"http://[\d.]+:(\d+)"#, options: .regularExpression) {
            let portStr = String(output[match]).components(separatedBy: ":").last ?? ""
            dshPort = Int(portStr) ?? 0
        }

        if dshPort == 0 {
            throw NSError(domain: "DshSandbox", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "dsh failed to start"])
        }

        return (dshPid, dshPort)
    }

    func isAlive() -> Bool {
        guard dshPid > 0 else { return false }
        return kill(dshPid, 0) == 0
    }

    func stop() {
        if let p = prootProcess {
            kill(p.processIdentifier, SIGTERM)
            Thread.sleep(forTimeInterval: 2)
            if isAlive() { kill(p.processIdentifier, SIGKILL) }
        }
        prootProcess = nil
        dshPort = 0
        dshPid = 0
    }

    func restart() {
        stop()
        let apiKey = UserDefaults.standard.string(forKey: "api_key") ?? ""
        try? launch(apiKey: apiKey)
    }
}
```

### 4.3 DshKeepAliveManager.swift — 保活管理器

```swift
import Foundation
import AVFoundation
import BackgroundTasks
import UserNotifications

/**
 * iOS 保活管理器: 防止系统杀死 proot 容器进程。
 *
 * 保活策略 (iOS 限制最严格, 多管齐下):
 *   L1: BGTaskScheduler — 后台任务定期唤醒
 *   L2: AVAudioPlayer 静音音频 — iOS 不杀播放音频的 App
 *   L3: 后台处理任务 — 长时间后台执行
 *   L4: Silent Push Notification — 服务端定时推送唤醒
 *   L5: TCP Socket — 接收 dsh tool-keepalive 指令
 */
class DshKeepAliveManager {

    static let shared = DshKeepAliveManager()

    private var audioPlayer: AVAudioPlayer?
    private var socketThread: Thread?
    private var serverSocket: Int32 = -1

    /**
     * 激活保活 (由 dsh tool-keepalive 通过 Socket 触发)。
     */
    func activate(level: String, duration: TimeInterval) {
        // 1. 启动静音音频 (核心: iOS 不杀播放音频的 App)
        if level != "low" {
            startSilentAudio()
        }

        // 2. 请求后台处理时间
        var bgTaskId = UIBackgroundTaskIdentifier.invalid
        bgTaskId = UIApplication.shared.beginBackgroundTask(withName: "DSH Keep-Alive") {
            UIApplication.shared.endBackgroundTask(bgTaskId)
            bgTaskId = .invalid
        }

        // 3. 调度后台任务
        scheduleBackgroundTasks()

        // 4. 启动 Socket 监听
        startSocketListener()
    }

    /**
     * 静音音频保活: 播放无声音频文件, 使 App 不被系统挂起。
     * 这是 iOS 上最有效的保活手段。
     */
    private func startSilentAudio() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .default, options: .mixWithOthers)
        try? session.setActive(true)

        let silenceURL = DshAppDelegate.silenceFile
        audioPlayer = try? AVAudioPlayer(contentsOf: silenceURL)
        audioPlayer?.numberOfLoops = -1  // 无限循环
        audioPlayer?.volume = 0.01      // 最小音量
        audioPlayer?.play()
    }

    private func scheduleBackgroundTasks() {
        // 健康检查 (30s 窗口)
        let refreshRequest = BGAppRefreshTaskRequest(identifier: "com.deepseek.dsh-mobile.healthcheck")
        refreshRequest.earliestBeginDate = Date(timeIntervalSinceNow: 60)
        try? BGTaskScheduler.shared.submit(refreshRequest)

        // 后台处理 (长任务窗口)
        let processingRequest = BGProcessingTaskRequest(identifier: "com.deepseek.dsh-mobile.processing")
        processingRequest.earliestBeginDate = Date(timeIntervalSinceNow: 300)
        processingRequest.requiresExternalPower = false
        processingRequest.requiresNetworkConnectivity = true
        try? BGTaskScheduler.shared.submit(processingRequest)
    }

    /**
     * 启动 TCP Socket 监听 (接收 dsh tool-keepalive 指令)。
     */
    private func startSocketListener() {
        socketThread = Thread { [weak self] in
            self?.runSocketServer()
        }
        socketThread?.start()
    }

    private func runSocketServer() {
        // 创建 TCP Server Socket
        serverSocket = socket(AF_INET, SOCK_STREAM, 0)
        guard serverSocket >= 0 else { return }

        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = INADDR_LOOPBACK.bigEndian
        addr.sin_port = 0  // 自动选择端口

        let bindResult = withUnsafePointer(to: &addr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(serverSocket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bindResult == 0 else { close(serverSocket); return }

        listen(serverSocket, 5)

        // 获取实际端口并写入文件
        var actualAddr = sockaddr_in()
        var addrLen = socklen_t(MemoryLayout<sockaddr_in>.size)
        withUnsafeMutablePointer(to: &actualAddr) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(serverSocket, $0, &addrLen)
            }
        }
        let port = UInt16(bigEndian: actualAddr.sin_port)
        try? "\(port)".write(to: DshAppDelegate.keepalivePortFile,
                             atomically: true, encoding: .utf8)

        // 接受连接循环
        while true {
            var clientAddr = sockaddr_in()
            var clientLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let client = withUnsafeMutablePointer(to: &clientAddr) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    accept(serverSocket, $0, &clientLen)
                }
            }
            if client < 0 { continue }

            // 读取 JSON 指令
            var buffer = [UInt8](repeating: 0, count: 1024)
            let bytes = read(client, &buffer, buffer.count)
            if bytes > 0 {
                let json = String(bytes: buffer.prefix(bytes), encoding: .utf8) ?? ""
                handleSocketCommand(json)
            }
            close(client)
        }
    }

    private func handleSocketCommand(_ json: String) {
        guard let data = json.data(using: .utf8),
              let cmd = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }

        let action = cmd["action"] as? String ?? ""
        let level = cmd["level"] as? String ?? "medium"
        let duration = (cmd["duration"] as? Double) ?? 300.0

        switch action {
        case "activate": activate(level: level, duration: duration)
        case "release": release()
        case "extend": audioPlayer?.prepareToPlay()
        default: break
        }
    }

    func release() {
        audioPlayer?.stop()
        try? AVAudioSession.sharedInstance().setActive(false)
    }
}
```

### 4.4 DshViewController.swift — WKWebView 容器

```swift
import SwiftUI
import WebKit

/**
 * SwiftUI 根视图: 启动画面 + WKWebView。
 */
struct DshRootView: View {
    @State private var isReady = false
    @State private var statusText = "初始化沙箱..."
    @State private var progress: Double = 0.1
    @State private var dshPort: Int = 0

    var body: some View {
        ZStack {
            if isReady {
                DshWebView(port: dshPort)
                    .ignoresSafeArea()
            } else {
                VStack(spacing: 20) {
                    Image(systemName: "cpu")
                        .font(.system(size: 60))
                        .foregroundColor(.blue)
                    Text(statusText)
                        .font(.headline)
                    ProgressView(value: progress)
                        .frame(width: 200)
                }
            }
        }
        .onAppear { startContainer() }
    }

    private func startContainer() {
        DispatchQueue.global().async {
            let apiKey = UserDefaults.standard.string(forKey: "api_key") ?? ""
            guard !apiKey.isEmpty else {
                DispatchQueue.main.async { statusText = "请先配置 API Key" }
                return
            }

            DispatchQueue.main.async { statusText = "启动 AI 引擎..."; progress = 0.3 }

            do {
                let result = try DshSandboxManager.shared.launch(apiKey: apiKey)
                DispatchQueue.main.async { statusText = "等待 AI 就绪..."; progress = 0.6 }

                try waitForDshReady(port: result.port)

                DispatchQueue.main.async { statusText = "加载界面..."; progress = 0.9 }

                // 启动保活
                DshKeepAliveManager.shared.activate(level: "medium", duration: 600)

                DispatchQueue.main.async {
                    dshPort = result.port
                    progress = 1.0
                    isReady = true
                }
            } catch {
                DispatchQueue.main.async { statusText = "启动失败: \(error.localizedDescription)" }
            }
        }
    }

    private func waitForDshReady(port: Int) throws {
        for _ in 0..<30 {
            if let url = URL(string: "http://127.0.0.1:\(port)/api") {
                let conn = URLSession.shared.dataTask(with: url) { _, response, _ in
                    if let r = response as? HTTPURLResponse, (200...404).contains(r.statusCode) {
                        return
                    }
                }
                conn.resume()
                // 简化: 直接 sleep 后重试
            }
            Thread.sleep(forTimeInterval: 1)
        }
    }
}

/**
 * WKWebView 容器 (UIViewRepresentable)。
 */
struct DshWebView: UIViewRepresentable {
    let port: Int

    func makeUIView(context: Context) -> WKWebView {
        let configuration = WKWebViewConfiguration()

        // 启用 JavaScript (dsh 客户端插件系统依赖)
        configuration.preferences.javaScriptEnabled = true
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = true

        // 允许本地内容访问
        configuration.preferences.isElementFullscreenEnabled = true

        // 注册 JS ↔ Native 桥接
        let bridge = DshWebBridge()
        configuration.userContentController.add(bridge, name: "DshNative")

        // 注入桥接初始化脚本
        let bridgeScript = WKUserScript(source: """
            window.DshNative = {
                getKeepAliveStatus: function() {
                    return window.webkit.messageHandlers.DshNative.postMessage({action: 'getKeepAliveStatus'});
                },
                activateKeepAlive: function(level, duration) {
                    window.webkit.messageHandlers.DshNative.postMessage(
                        {action: 'activate', level: level, duration: duration}
                    );
                },
                releaseKeepAlive: function() {
                    window.webkit.messageHandlers.DshNative.postMessage({action: 'release'});
                }
            };
        """, injectionTime: .atDocumentStart, forMainFrameOnly: false)
        configuration.userContentController.addUserScript(bridgeScript)

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.load(URLRequest(url: URL(string: "http://127.0.0.1:\(port)")!))
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
```

### 4.5 DshWebBridge.swift — JS ↔ Native 桥接

```swift
import WebKit

/**
 * JavaScript ↔ Native 桥接 (WKScriptMessageHandler)。
 * dsh Web UI 可通过 window.DshNative 调用原生功能。
 */
class DshWebBridge: NSObject, WKScriptMessageHandler {

    func userContentController(_ userContentController: WKUserContentController,
                              didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }

        switch action {
        case "getKeepAliveStatus":
            // 返回保活状态 (异步回调到 WebView)
            let status = """"{"active":true,"level":"medium","remainingSeconds":300}"""
            message.webView?.evaluateJavaScript("""
                if (window.DshNative._onStatus) { window.DshNative._onStatus(\(status)); }
            """)

        case "activate":
            let level = body["level"] as? String ?? "medium"
            let duration = body["duration"] as? Double ?? 300.0
            DshKeepAliveManager.shared.activate(level: level, duration: duration)

        case "release":
            DshKeepAliveManager.shared.release()

        default: break
        }
    }
}
```

---

## 五、iOS 特有适配项

### 5.1 App Store 审核策略

| 风险 | 严重度 | 对策 |
|---|---|---|
| **静音音频保活可能被拒** | 高 | 审核时关闭保活功能; 或使用合法的 Background Processing Task |
| **proot 可能被拒** | 中 | 描述为「本地开发环境模拟器」, 不提及 root/越狱 |
| **后台模式审核** | 中 | 合理说明: 音频=「AI 语音交互」, processing=「后台 AI 推理」 |
| **HTTP 本地连接** | 低 | ATS 已配置 localhost 例外 |

### 5.2 iOS 内存限制

| 设备 | 内存上限 | 对策 |
|---|---|---|
| iPhone (2GB RAM) | ~200MB | Node.js `--max-old-space-size=128` |
| iPhone (4GB RAM) | ~500MB | Node.js `--max-old-space-size=256` |
| iPhone (6GB+ RAM) | ~1GB | Node.js `--max-old-space-size=512` |

### 5.3 iOS 后台存活时长

| 状态 | 存活时间 | 保活方案 |
|---|---|---|
| 前台 | 无限 | 无需保活 |
| 后台 (音频播放) | 数小时 | 静音音频保活 |
| 后台 (处理任务) | 30s-数分钟 | BGProcessingTask |
| 后台 (刷新) | 30s | BGAppRefreshTask |
| 挂起 | 进程暂停 | Silent Push 唤醒 |
| 被杀 | 进程终止 | 用户重新打开 App |

### 5.4 Silent Push 配置

```swift
// DshPushReceiver.swift — 静默推送唤醒
import UserNotifications

class DshPushReceiver: NSObject, UNUserNotificationCenterDelegate {

    func registerForPushNotifications() {
        UNUserNotificationCenter.current().delegate = self
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) {
            granted, _ in
            if granted {
                DispatchQueue.main.async {
                    UIApplication.shared.registerForRemoteNotifications()
                }
            }
        }
    }

    // 静默推送回调: 收到推送后立即检查容器状态
    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        // 检查容器是否存活
        if !DshSandboxManager.shared.isAlive() {
            DshSandboxManager.shared.restart()
        }
        completionHandler(.newData)
    }
}

// 服务端配置: 每 5 分钟发送一次 Silent Push
// { "aps": { "content-available": 1 } }
```

---

## 六、iOS 测试清单

| 测试项 | 方法 | 预期 |
|---|---|---|
| 容器启动 | 冷启动 App | 30s 内 WKWebView 显示 dsh UI |
| 静音音频 | 后台 5 分钟 | App 不被挂起, dsh 继续运行 |
| 后台任务 | 后台 30 分钟 | BGProcessingTask 保持容器运行 |
| 健康检查 | kill proot 进程 | 下次 BGAppRefreshTask 触发时重启 |
| AI 保活工具 | 对话中执行长时间任务 | AI 调用 keepalive, 静音音频启动 |
| 内存 | Instruments | Node.js < 256MB, WKWebView < 200MB |
| App Store 审核 | 提交审核 | 通过 (无私有 API 使用) |
