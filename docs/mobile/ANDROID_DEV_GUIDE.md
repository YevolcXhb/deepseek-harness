# DeepSeek Harness Android 移动端开发文档

> **方案核心**: WebView 壳 + proot 沙箱容器 + 完整 dsh `--profile mobile`
> **满血保障**: Host 端 247 个 Cordis 插件 + Client 端 45+ 个 React UI 插件 + HMR + 第三方 dsh-plugin 生态全部保留

---

## 一、Android 架构总览

```
┌───────────────────────────────────────────────────────────────────────┐
│                         Android 设备 (无 Root)                       │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  DshApp (Application)                                           │  │
│  │  ├── DshMainActivity (UI 容器)                                  │  │
│  │  │   └── WebView (加载 http://127.0.0.1:PORT)                   │  │
│  │  │       └── 完整 dsh Web UI (React + 45 个客户端插件)          │  │
│  │  ├── DshKeepAliveService (前台服务)                             │  │
│  │  │   ├── startForeground() — 持续通知栏                         │  │
│  │  │   ├── WakeLock — CPU 保持唤醒                                │  │
│  │  │   └── TCP Socket — 接收 dsh tool-keepalive 指令              │  │
│  │  ├── DshHealthCheckJob (JobScheduler)                           │  │
│  │  │   └── 每 60s 检查 proot 进程存活                             │  │
│  │  ├── DshRestartWorker (WorkManager)                             │  │
│  │  │   └── 容器死亡时自动重启                                     │  │
│  │  └── DshSandboxBridge (原生桥接)                                │  │
│  │      └── ProcessBuilder → proot → node → dsh                   │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  proot 进程 (独立进程组, detached)                              │  │
│  │  └── node /usr/lib/dsh/apps/cli/lib/bin.js                      │  │
│  │      --profile mobile --no-open --port 0                        │  │
│  │                                                                  │  │
│  │  Host 插件树 (Cordis):                                           │  │
│  │    ✅ agent-loop, tools, session, system-prompt                │  │
│  │    ✅ llm-deepseek (DeepSeek API + SSE 流式)                    │  │
│  │    ✅ sandbox-proot (proot SandboxProvider)                     │  │
│  │    ✅ tool-bash, bash-local, subprocess-local                   │  │
│  │    ✅ fs-local, tool-fs, tool-str-replace-editor                │  │
│  │    ✅ session-persistence-sqlite                                │  │
│  │    ✅ tool-keepalive (AI 可调用保活)                            │  │
│  │    ✅ webserver (node:http /api + /api/remote.mux WS)          │  │
│  │    ✅ client-modules (扫描 dsh.client → __DSH_BOOT__ 注入)      │  │
│  │    ✅ client-hmr (bundle stat-poll → SSE 推送)                 │  │
│  │    ✅ frontend-static (React dist 服务)                         │  │
│  │    ✅ ... 全部 247 个 Host 插件                                 │  │
│  │                                                                  │  │
│  │  Client 插件树 (浏览器侧, 在 WebView 内运行):                   │  │
│  │    ✅ ui-chat, ui-conversation, ui-session                      │  │
│  │    ✅ ui-settings, ui-settings-plugins (插件管理)               │  │
│  │    ✅ ui-approval (工具审批)                                    │  │
│  │    ✅ ui-sidebar, ui-layout, ui-theme                           │  │
│  │    ✅ ui-renderer (React createRoot + SlotRegistry)             │  │
│  │    ✅ client-modules (ClientModuleSystem 浏览器端)              │  │
│  │    ✅ client-hmr (浏览器端热重载)                               │  │
│  │    ✅ ... 全部 45+ 个 Client 插件                               │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  文件系统:                                                            │
│  /data/data/com.dsh.mobile/files/                                    │
│  ├── proot                    — proot 二进制                         │
│  ├── rootfs/                  — proot 根文件系统                     │
│  │   ├── bin/busybox                                               │
│  │   ├── usr/bin/node                                             │
│  │   ├── usr/lib/dsh/  (完整构建产物 + node_modules)               │
│  │   ├── home/.dsh/    (DSH_HOME, profiles, credentials)           │
│  │   └── workspace/    (AI 工作区, sandbox workspace-write)       │
│  ├── keepalive.port          — TCP 端口文件                        │
│  └── workspace/               — 用户文件                             │
└───────────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼ HTTPS
                    ┌──────────────────────────────┐
                    │  DeepSeek LLM API (云端)      │
                    │  api.deepseek.com/v1/chat/   │
                    │  completions                 │
                    └──────────────────────────────┘
```

---

## 二、Android 项目结构

```
apps/mobile/android/
├── app/
│   ├── src/main/
│   │   ├── java/com/dsh/mobile/
│   │   │   ├── DshApplication.kt           — Application 入口
│   │   │   ├── DshMainActivity.kt          — WebView 容器 Activity
│   │   │   ├── DshKeepAliveService.kt       — 前台服务 (保活核心)
│   │   │   ├── DshHealthCheckJob.kt        — 健康检查 JobScheduler
│   │   │   ├── DshRestartWorker.kt        — 自动重启 WorkManager
│   │   │   ├── DshSandboxBridge.kt        — proot 容器启动/停止
│   │   │   ├── DshKeepAliveBridge.kt      — 状态共享单例
│   │   │   ├── DshWebViewClient.kt        — WebView 配置
│   │   │   ├── DshWebInterface.kt         — JS ↔ Native 桥接
│   │   │   ├── DshBootReceiver.kt        — 开机自启
│   │   │   └── DshRomAdapter.kt          — 国产 ROM 适配
│   │   ├── res/
│   │   │   ├── layout/activity_main.xml
│   │   │   ├── drawable/
│   │   │   │   ├── ic_dsh_notification.xml
│   │   │   │   └── ic_dsh_logo.xml
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       ├── colors.xml
│   │   │       └── themes.xml
│   │   ├── assets/
│   │   │   ├── proot-arm64               — proot 二进制 (ARM64)
│   │   │   ├── proot-x86_64              — proot 二进制 (x86_64)
│   │   │   └── rootfs.tar.gz             — 预打包 rootfs
│   │   └── AndroidManifest.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 三、AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="com.dsh.mobile">

    <!-- 网络 -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- 前台服务 (保活核心) -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- WakeLock (CPU 保持唤醒) -->
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- 电池优化豁免 -->
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <!-- 开机自启 -->
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application
        android:name=".DshApplication"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@style/Theme.DshMobile"
        android:largeHeap="true">

        <activity
            android:name=".DshMainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|uiMode"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".DshKeepAliveService"
            android:foregroundServiceType="dataSync"
            android:exported="false"
            android:stopWithTask="false" />

        <receiver
            android:name=".DshBootReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
    </application>
</manifest>
```

---

## 四、核心代码实现

### 4.1 DshApplication.kt

```kotlin
package com.dsh.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

/**
 * Application 入口: 初始化通知渠道、目录结构、释放 assets。
 *
 * 文件系统布局 (/data/data/com.dsh.mobile/files/):
 *   proot           — proot 二进制
 *   rootfs/         — proot 根文件系统 (含 node + dsh + rootfs)
 *   workspace/      — AI 工作区
 *   keepalive.port  — 保活服务 TCP 端口号
 */
class DshApplication : Application() {

    companion object {
        const val CHANNEL_ID_KEEPALIVE = "dsh_keepalive"
        const val CHANNEL_ID_HEALTH = "dsh_health"
        const val FILES_DIR = "/data/data/com.dsh.mobile/files"
        const val PROOT_BIN = "$FILES_DIR/proot"
        const val ROOTFS_DIR = "$FILES_DIR/rootfs"
        const val WORKSPACE_DIR = "$FILES_DIR/workspace"
        const val KEEPALIVE_PORT_FILE = "$FILES_DIR/keepalive.port"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        ensureDirectories()
        releaseAssetsIfNeeded()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_KEEPALIVE, "DSH 运行状态",
                    NotificationManager.IMPORTANCE_LOW).apply {
                    description = "DeepSeek Harness 容器运行状态通知"
                    setShowBadge(false)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID_HEALTH, "DSH 健康告警",
                    NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "容器异常时的高优先级告警"
                }
            )
        }
    }

    private fun ensureDirectories() {
        listOf(FILES_DIR, ROOTFS_DIR, WORKSPACE_DIR, "$ROOTFS_DIR/home/.dsh").forEach { dir ->
            java.io.File(dir).mkdirs()
        }
    }

    /**
     * 首次启动时从 assets 释放 proot 二进制和 rootfs。
     * 后续启动检测到文件已存在则跳过。
     */
    private fun releaseAssetsIfNeeded() {
        // 释放 proot 二进制 (按 ABI 选择)
        val prootFile = java.io.File(PROOT_BIN)
        if (!prootFile.exists()) {
            val abi = Build.SUPPORTED_ABIS[0]
            val assetName = when {
                abi.contains("arm64") -> "proot-arm64"
                abi.contains("x86_64") -> "proot-x86_64"
                abi.contains("armeabi") -> "proot-arm"
                else -> "proot-x86"
            }
            assets.open(assetName).use { input ->
                prootFile.outputStream().use { output -> input.copyTo(output) }
            }
            prootFile.setExecutable(true)
        }

        // 释放 rootfs (首次启动, 解压 tar.gz)
        val nodeFile = java.io.File("$ROOTFS_DIR/usr/bin/node")
        if (!nodeFile.exists()) {
            val tarball = java.io.File("$FILES_DIR/rootfs.tar.gz")
            assets.open("rootfs.tar.gz").use { input ->
                tarball.outputStream().use { output -> input.copyTo(output) }
            }
            val process = ProcessBuilder("tar", "-xzf", tarball.absolutePath, "-C", ROOTFS_DIR)
                .redirectErrorStream(true).start()
            process.waitFor()
            tarball.delete()
        }
    }
}
```

### 4.2 DshSandboxBridge.kt — proot 容器启动器

```kotlin
package com.dsh.mobile

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * proot 容器启动器: 在无 Root 设备上以 proot 隔离方式启动 dsh Host。
 *
 * 进程树:
 *   proot → node (dsh --profile mobile --no-open)
 *          → Agent Loop (Cordis 插件树, 247 个包)
 *          → HTTP /api (RPC)
 *          → WS /api/remote.mux (事件流)
 *          → Tool 执行 (bash/python 在容器内)
 *
 * proot 参数:
 *   -r <rootfs>          设置根文件系统
 *   -b <host:container>  绑定挂载
 *   -w <dir>             设置工作目录
 *   -i <uid:gid>         用户身份模拟
 *   -e KEY=VALUE         环境变量注入
 */
class DshSandboxBridge {

    companion object {
        private const val TAG = "DshSandbox"
    }

    private var prootProcess: Process? = null
    private var dshPort: Int = 0
    private var dshPid: Int = 0

    /**
     * 启动 proot 沙箱内的 dsh Host。
     *
     * @param apiKey DeepSeek API Key
     * @return 启动结果 (pid + port)
     */
    fun launch(apiKey: String): LaunchResult {
        val rootfs = DshApplication.ROOTFS_DIR
        val workspace = DshApplication.WORKSPACE_DIR
        val prootBin = DshApplication.PROOT_BIN

        val cmd = mutableListOf<String>().apply {
            add(prootBin)
            add("-r"); add(rootfs)
            add("-b"); add("/dev")
            add("-b"); add("/proc")
            add("-b"); add("/sys")
            add("-b"); add("$workspace:/workspace")
            add("-w"); add("/workspace")
            add("-i"); add("0:0")
            add("-e"); add("HOME=/home")
            add("-e"); add("DSH_HOME=/home/.dsh")
            add("-e"); add("DSH_PLATFORM=android")
            add("-e"); add("DSH_PROOT=1")
            add("-e"); add("DEEPSEEK_API_KEY=$apiKey")
            add("-e"); add("PATH=/usr/bin:/bin:/usr/sbin:/sbin")
            add("-e"); add("NODE_OPTIONS=--max-old-space-size=256")
            add("--")
            add("/usr/bin/node")
            add("/usr/lib/dsh/apps/cli/lib/bin.js")
            add("--profile"); add("mobile")
            add("--no-open")
            add("--port"); add("0")
        }

        Log.i(TAG, "Launching proot: ${cmd.joinToString(" ")}")

        val pb = ProcessBuilder(cmd).apply {
            redirectErrorStream(true)
            environment()["HOME"] = "/home"
            environment()["DSH_HOME"] = "/home/.dsh"
            environment()["DSH_PLATFORM"] = "android"
            environment()["DEEPSEEK_API_KEY"] = apiKey
        }

        prootProcess = pb.start()

        // 读取 stdout 解析端口
        val reader = BufferedReader(InputStreamReader(prootProcess!!.inputStream))
        var port = 0
        var timeout = 0
        while (timeout < 60) {
            val line = reader.readLine() ?: break
            Log.i(TAG, "[dsh] $line")
            val match = Regex("""http://[\d.]+:(\d+)""").find(line)
            if (match != null) {
                port = match.groupValues[1].toInt()
                break
            }
            timeout++
        }

        if (port == 0) {
            throw RuntimeException("dsh failed to start within 60 seconds")
        }

        dshPort = port
        dshPid = prootProcess!!.pid()
        Log.i(TAG, "dsh started: pid=$dshPid, port=$dshPort")
        return LaunchResult(pid = dshPid, port = dshPort)
    }

    fun isAlive(): Boolean = prootProcess?.isAlive == true

    fun stop() {
        prootProcess?.let { p ->
            p.destroy()
            Thread.sleep(2000)
            if (p.isAlive) p.destroyForcibly()
        }
        prootProcess = null
        dshPort = 0
        dshPid = 0
    }

    data class LaunchResult(val pid: Int, val port: Int)
}
```

### 4.3 DshKeepAliveService.kt — 前台服务保活

```kotlin
package com.dsh.mobile

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * 保活前台服务: 防止 Android 系统杀死 proot 容器进程。
 *
 * 保活层级:
 *   L1: startForeground() — 系统级前台服务, 最低优先级被杀
 *   L2: WakeLock — CPU 保持唤醒, 防止休眠时进程暂停
 *   L3: JobScheduler 健康检查 — 定期巡检, 发现死亡自动重启
 *   L4: WorkManager 兜底重启 — 进程死亡后延迟自动重启
 *   L5: TCP Socket 监听 — 接收 dsh tool-keepalive 工具的指令
 *
 * AI 可通过 dsh 的 tool-keepalive 工具调用:
 *   keepalive(action="activate", level="high", durationSeconds=600)
 *   → dsh 通过 Socket 发送指令到此服务
 *   → 服务激活 WakeLock + 前台通知
 */
class DshKeepAliveService : Service() {

    companion object {
        private const val TAG = "DshKeepAlive"
        private const val NOTIFICATION_ID = 0xD5F1
        private const val ACTION_ACTIVATE = "com.dsh.mobile.ACTIVATE"
        private const val ACTION_RELEASE = "com.dsh.mobile.RELEASE"
        private const val EXTRA_LEVEL = "level"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_PID = "pid"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var socketThread: Thread? = null
    private var socketServer: ServerSocket? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACTIVATE -> {
                val level = intent.getStringExtra(EXTRA_LEVEL) ?: "medium"
                val duration = intent.getLongExtra(EXTRA_DURATION, 300_000L)
                val pid = intent.getIntExtra(EXTRA_PID, 0)
                activateKeepAlive(level, duration, pid)
            }
            ACTION_RELEASE -> releaseKeepAlive()
            else -> startDefaultForeground()
        }
        return START_STICKY
    }

    private fun startDefaultForeground() {
        val notification = createNotification("DSH 容器运行中", "medium")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        startSocketListener()
    }

    private fun activateKeepAlive(level: String, duration: Long, pid: Int) {
        DshKeepAliveBridge.setMonitoredPid(pid)

        val notification = createNotification("DSH AI Agent 运行中 ($level)", level)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock?.release()

        val wakeLockFlags = when (level) {
            "low" -> PowerManager.PARTIAL_WAKE_LOCK
            "medium" -> PowerManager.PARTIAL_WAKE_LOCK
            "high" -> PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE
            "critical" -> PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE or
                           PowerManager.SCREEN_BRIGHT_WAKE_LOCK
            else -> PowerManager.PARTIAL_WAKE_LOCK
        }

        wakeLock = powerManager.newWakeLock(wakeLockFlags, "DSH::KeepAlive::$level").apply {
            setReferenceCounted(false)
            acquire(duration)
        }

        if (level == "high" || level == "critical") {
            requestBatteryOptimizationExemption()
        }

        Log.i(TAG, "Keep-alive activated: level=$level, duration=${duration}ms, pid=$pid")
    }

    private fun releaseKeepAlive() {
        wakeLock?.release()
        wakeLock = null
        startDefaultForeground()
        Log.i(TAG, "Keep-alive released, downgraded to default monitoring")
    }

    private fun createNotification(title: String, level: String): Notification {
        return NotificationCompat.Builder(this, DshApplication.CHANNEL_ID_KEEPALIVE)
            .setContentTitle(title)
            .setContentText("点击打开 DeepSeek Harness")
            .setSmallIcon(R.drawable.ic_dsh_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun requestBatteryOptimizationExemption() {
        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = android.Uri.parse("package:$packageName")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /**
     * 启动 TCP Socket 监听 (接收 dsh tool-keepalive 指令)。
     * 端口号写入文件供 dsh 容器内读取。
     */
    private fun startSocketListener() {
        socketThread = Thread {
            try {
                socketServer = ServerSocket(0)
                java.io.File(DshApplication.KEEPALIVE_PORT_FILE)
                    .writeText(socketServer!!.localPort.toString())

                Log.i(TAG, "Socket listener on port ${socketServer!!.localPort}")

                while (!Thread.currentThread().isInterrupted) {
                    val client = socketServer!!.accept()
                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    val line = reader.readLine()
                    if (line != null) handleSocketCommand(line)
                    client.close()
                }
            } catch (e: Exception) {
                if (!Thread.currentThread().isInterrupted) {
                    Log.e(TAG, "Socket listener error", e)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun handleSocketCommand(json: String) {
        try {
            val cmd = org.json.JSONObject(json)
            val action = cmd.getString("action")
            val level = cmd.optString("level", "medium")
            val duration = cmd.optLong("duration", 300_000L)
            val pid = cmd.optInt("pid", DshKeepAliveBridge.getMonitoredPid())

            when (action) {
                "activate" -> activateKeepAlive(level, duration, pid)
                "release" -> releaseKeepAlive()
                "extend" -> wakeLock?.acquire(duration)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling socket command", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.release()
        socketThread?.interrupt()
        socketServer?.close()
        DshRestartWorker.enqueue(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

### 4.4 DshHealthCheckJob.kt

```kotlin
package com.dsh.mobile

import android.app.job.*
import android.content.*
import android.util.Log

/**
 * 定期健康检查: 每 60 秒检查 proot 进程是否存活。
 * 进程死亡时自动重启容器。
 */
class DshHealthCheckJob : JobService() {

    companion object {
        private const val JOB_ID = 1001
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
            scheduler.schedule(JobInfo.Builder(JOB_ID,
                ComponentName(context, DshHealthCheckJob::class.java)).apply {
                setPeriodic(60_000L)
                setPersisted(true)
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            }.build())
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val pid = DshKeepAliveBridge.getMonitoredPid()
                if (pid > 0) {
                    val process = Runtime.getRuntime().exec("kill -0 $pid")
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        Log.w(TAG, "Container process $pid died, restarting...")
                        val intent = Intent(this, DshKeepAliveService::class.java)
                            .apply { action = "com.dsh.mobile.RESTART" }
                        startService(intent)
                    }
                }
            } catch (e: Exception) { Log.e(TAG, "Health check failed", e) }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters) = true
}
```

### 4.5 DshRestartWorker.kt

```kotlin
package com.dsh.mobile

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.concurrent.TimeUnit

/**
 * WorkManager 兜底重启: Service 被杀后自动触发容器重启。
 */
class DshRestartWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<DshRestartWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .build()
            )
        }
    }

    override suspend fun doWork(): Result = try {
        DshHealthCheckJob.schedule(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }
}
```

### 4.6 DshWebViewClient.kt

```kotlin
package com.dsh.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.*

/**
 * WebView 配置: 加载 dsh 完整 Web UI。
 *
 * WebView 加载 http://127.0.0.1:PORT, 获取:
 *   ✅ index.html (注入 __DSH_BOOT__ 引导图)
 *   ✅ React + Vite 构建的静态资源
 *   ✅ 45+ 个客户端 UI 插件
 *   ✅ SlotMap 插槽系统
 *   ✅ HMR 热重载 (SSE)
 *   ✅ 第三方 dsh-plugin
 */
@SuppressLint("SetJavaScriptEnabled")
class DshWebViewClient(private val context: Context) {

    fun configureWebView(webView: WebView, port: Int) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host != "127.0.0.1" && url.host != "localhost") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, url))
                    return true
                }
                return false
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(DshWebInterface(context), "DshNative")
        webView.loadUrl("http://127.0.0.1:$port")
    }
}
```

### 4.7 DshWebInterface.kt

```kotlin
package com.dsh.mobile

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface

/**
 * JavaScript ↔ Native 桥接。
 * dsh Web UI 可通过 window.DshNative 调用原生功能。
 */
class DshWebInterface(private val context: Context) {

    @JavascriptInterface
    fun getKeepAliveStatus(): String {
        return """{"active":${DshKeepAliveBridge.isActive()},"level":"${DshKeepAliveBridge.getLevel()}","remainingSeconds":${DshKeepAliveBridge.getRemainingSeconds()}}"""
    }

    @JavascriptInterface
    fun activateKeepAlive(level: String, durationSeconds: Int) {
        Intent(context, DshKeepAliveService::class.java).apply {
            action = "com.dsh.mobile.ACTIVATE"
            putExtra("level", level)
            putExtra("duration", durationSeconds * 1000L)
            putExtra("pid", DshKeepAliveBridge.getMonitoredPid())
        }.also { context.startService(it) }
    }

    @JavascriptInterface
    fun releaseKeepAlive() {
        Intent(context, DshKeepAliveService::class.java).apply {
            action = "com.dsh.mobile.RELEASE"
        }.also { context.startService(it) }
    }

    @JavascriptInterface
    fun getContainerStatus(): String {
        return """{"alive":${DshSandboxBridge().isAlive()},"port":${DshKeepAliveBridge.getPort()},"pid":${DshKeepAliveBridge.getMonitoredPid()}}"""
    }
}
```

### 4.8 DshKeepAliveBridge.kt

```kotlin
package com.dsh.mobile

/**
 * 保活状态共享: 跨组件传递保活状态信息。
 */
object DshKeepAliveBridge {
    @Volatile private var monitoredPid: Int = 0
    @Volatile private var port: Int = 0
    @Volatile private var active: Boolean = false
    @Volatile private var level: String = "medium"
    @Volatile private var remainingSeconds: Long = 0

    fun setMonitoredPid(pid: Int) { monitoredPid = pid }
    fun getMonitoredPid(): Int = monitoredPid
    fun setPort(p: Int) { port = p }
    fun getPort(): Int = port
    fun isActive(): Boolean = active
    fun getLevel(): String = level
    fun getRemainingSeconds(): Long = remainingSeconds
}
```

### 4.9 DshMainActivity.kt

```kotlin
package com.dsh.mobile

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 主 Activity: WebView 容器 + 启动流程。
 *
 * 启动流程:
 *   1. 显示启动画面
 *   2. 启动 proot 容器
 *   3. 等待 dsh HTTP 就绪
 *   4. 启动保活前台服务
 *   5. WebView 加载 http://127.0.0.1:PORT
 *   6. 隐藏启动画面
 */
class DshMainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashView: View
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar
    private val sandboxBridge = DshSandboxBridge()
    private val webViewConfig = DshWebViewClient(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        splashView = findViewById(R.id.splashView)
        statusText = findViewById(R.id.statusText)
        progressBar = findViewById(R.id.progressBar)
        startContainer()
    }

    private fun startContainer() {
        Thread {
            try {
                runOnUiThread { statusText.text = "初始化沙箱..."; progressBar.progress = 10 }
                val apiKey = getSharedPreferences("dsh", MODE_PRIVATE).getString("api_key", "") ?: ""
                if (apiKey.isEmpty()) {
                    runOnUiThread { statusText.text = "请先配置 API Key" }
                    return@Thread
                }
                runOnUiThread { statusText.text = "启动 AI 引擎..."; progressBar.progress = 30 }
                val result = sandboxBridge.launch(apiKey)
                runOnUiThread { statusText.text = "等待 AI 就绪..."; progressBar.progress = 60 }
                waitForDshReady(result.port)
                runOnUiThread { statusText.text = "加载界面..."; progressBar.progress = 90 }
                startForegroundService(Intent(this, DshKeepAliveService::class.java).apply {
                    putExtra("pid", result.pid)
                })
                DshHealthCheckJob.schedule(this)
                DshKeepAliveBridge.setMonitoredPid(result.pid)
                DshKeepAliveBridge.setPort(result.port)
                runOnUiThread {
                    progressBar.progress = 100
                    webViewConfig.configureWebView(webView, result.port)
                    webView.postDelayed({ splashView.visibility = View.GONE }, 2000)
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "启动失败: ${e.message}" }
            }
        }.start()
    }

    private fun waitForDshReady(port: Int) {
        for (i in 0 until 30) {
            try {
                val conn = java.net.URL("http://127.0.0.1:$port/api").openConnection()
                        as java.net.HttpURLConnection
                conn.connectTimeout = 2000; conn.readTimeout = 2000; conn.requestMethod = "GET"
                val code = conn.responseCode; conn.disconnect()
                if (code in 200..404) return
            } catch (e: Exception) { Thread.sleep(1000) }
        }
        throw RuntimeException("dsh failed to start within 30 seconds")
    }

    override fun onBackPressed() { moveTaskToBack(true) }
}
```

### 4.10 build.gradle.kts

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.dsh.mobile"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.dsh.mobile"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    androidResources { noCompress.addAll(listOf("gz", "tar.gz")) }
}
dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
}
```

---

## 五、国产 ROM 保活适配

| 厂商 | 适配方法 |
|---|---|
| **华为 (EMUI)** | 引导在「电池」→「启动管理」开启自启动 |
| **小米 (MIUI)** | 引导关闭「省电模式」, 在安全中心加入自启动白名单 |
| **OPPO (ColorOS)** | 引导在安全中心加入白名单 |
| **vivo (OriginOS)** | 引导在设置-电池加入白名单 |
| **三星 (One UI)** | 引导取消「将应用置于休眠」 |

```kotlin
object DshRomAdapter {
    fun requestXiaomiAutoStart(context: Context) {
        try {
            context.startActivity(Intent().apply {
                component = ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {}
    }
    fun requestHuaweiBattery(context: Context) {
        try {
            context.startActivity(Intent().apply {
                component = ComponentName("com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {}
    }
    fun requestOppoAutoStart(context: Context) {
        try {
            context.startActivity(Intent().apply {
                component = ComponentName("com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {}
    }
}
```

---

## 六、Android 测试清单

| 测试项 | 方法 | 预期 |
|---|---|---|
| 容器启动 | 冷启动 App | 30s 内 WebView 显示 dsh UI |
| 前台服务 | 切到后台 5 分钟 | 通知栏显示「DSH 运行中」, 进程不被杀 |
| WakeLock | 关屏 10 分钟 | dsh 继续运行, AI 任务不中断 |
| 健康检查 | 手动 kill proot 进程 | 60s 内自动重启 |
| AI 保活工具 | 对话中执行长时间任务 | AI 自动调用 keepalive, 通知栏出现 |
| 内存 | Android Profiler | Node.js < 300MB, WebView < 200MB |
| 国产 ROM | 小米/华为/OPPO 设备 | 引导白名单后保活生效 |
| 第三方插件 | dsh plugin add xxx | 安装后 HMR 热重载, UI 正常 |
