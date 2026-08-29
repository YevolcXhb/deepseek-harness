# DeepSeek Harness 鸿蒙 (HarmonyOS) 移动端开发文档

> **方案核心**: Web 组件壳 + proot 沙箱容器 + 完整 dsh `--profile mobile`
> **满血保障**: Host 端 247 个 Cordis 插件 + Client 端 45+ 个 React UI 插件 + HMR + 第三方 dsh-plugin 生态全部保留

---

## 一、鸿蒙架构总览

```
┌───────────────────────────────────────────────────────────────────────┐
│                      HarmonyOS 设备 (无 Root)                       │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  DshApp (AbilityStage)                                          │  │
│  │  ├── DshMainAbility (UI 容器, UIAbility)                         │  │
│  │  │   └── Web 组件 (加载 http://127.0.0.1:PORT)                  │  │
│  │  │       └── 完整 dsh Web UI (React + 45 个客户端插件)          │  │
│  │  ├── DshKeepAliveAbility (后台服务, 后台模式)                    │  │
│  │  │   ├── ContinuousTask — 长时间后台任务                         │  │
│  │  │   ├── WorkScheduler — 定期健康检查                            │  │
│  │  │   └── TCP Socket — 接收 dsh tool-keepalive 指令              │  │
│  │  └── DshSandboxManager (沙箱管理)                               │  │
│  │      └── ChildProcess → proot → node → dsh                    │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────────┐  │
│  │  proot 进程 (后台运行)                                          │  │
│  │  └── node /usr/lib/dsh/apps/cli/lib/bin.js                      │  │
│  │      --profile mobile --no-open --port 0                        │  │
│  │                                                                  │  │
│  │  Host 插件树 (Cordis): ✅ 全部 247 个 Host 插件                 │  │
│  │  Client 插件树 (Web 组件内): ✅ 全部 45+ 个 Client 插件         │  │
│  └─────────────────────────────────────────────────────────────────┘  │
│                                                                       │
│  文件系统 (应用沙箱):                                                │
│  /data/storage/el2/base/haps/entry/files/                           │
│  ├── proot                    — proot 二进制                         │
│  ├── rootfs/                  — proot 根文件系统                     │
│  │   ├── bin/busybox                                               │
│  │   ├── usr/bin/node                                             │
│  │   ├── usr/lib/dsh/  (完整构建产物)                              │
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

## 二、鸿蒙项目结构

```
apps/mobile/harmony/
├── entry/                           — 主模块
│   ├── src/main/
│   │   ├── ets/
│   │   │   ├── entryability/
│   │   │   │   └── DshEntryAbility.ets   — UIAbility 入口
│   │   │   ├── pages/
│   │   │   │   ├── Index.ets             — 启动页
│   │   │   │   └── DshWebPage.ets        — Web 组件页
│   │   │   ├── service/
│   │   │   │   ├── DshSandboxManager.ets — proot 容器管理
│   │   │   │   ├── DshKeepAliveManager.ets — 保活管理
││   │   │   │   └── DshWebBridge.ets      — JS ↔ Native 桥接
│   │   │   └── model/
│   │   │       └── DshConstants.ets      — 常量定义
│   │   ├── resources/
│   │   │   ├── base/media/              — 图标
│   │   │   └── base/profile/            — 配置
│   │   └── module.json5                  — 模块配置
│   ├── build-profile.json5
│   └── oh-package.json5
├── build-profile.json5
└── oh-package.json5
```

---

## 三、module.json5 模块配置

```json5
{
  "module": {
    "name": "entry",
    "type": "entry",
    "description": "$string:module_desc",
    "mainElement": "DshEntryAbility",
    "deviceTypes": ["phone", "tablet"],
    "deliveryWithInstall": true,
    "installationFree": false,

    "abilities": [
      {
        "name": "DshEntryAbility",
        "srcEntry": "./ets/entryability/DshEntryAbility.ets",
        "description": "$string:DshEntryAbility_desc",
        "icon": "$media:icon",
        "label": "$string:DshEntryAbility_label",
        "startWindowIcon": "$media:icon",
        "startWindowBackground": "$color:start_window_background",
        "exported": true,
        "skills": [
          {
            "actions": ["action.system.home"],
            "entities": ["entity.system.home"],
            "uris": []
          }
        ]
      }
    ],

    "extensionAbilities": [
      {
        "name": "DshKeepAliveAbility",
        "srcEntry": "./ets/service/DshKeepAliveManager.ets",
        "description": "$string:DshKeepAliveAbility_desc",
        "icon": "$media:icon",
        "label": "$string:DshKeepAliveAbility_label",
        "type": "backgroundProcessing",
        "exported": false
      }
    ],

    "requestPermissions": [
      { "name": "ohos.permission.INTERNET" },
      { "name": "ohos.permission.KEEP_BACKGROUND_RUNNING" },
      { "name": "ohos.permission.RUNNING_LOCK" }
    ]
  }
}
```

---

## 四、核心代码实现

### 4.1 DshConstants.ets — 常量定义

```typescript
/**
 * 全局常量定义。
 */
export class DshConstants {
  // 应用文件目录
  static readonly FILES_DIR: string = '/data/storage/el2/base/haps/entry/files'
  static readonly PROOT_BIN: string = `${DshConstants.FILES_DIR}/proot`
  static readonly ROOTFS_DIR: string = `${DshConstants.FILES_DIR}/rootfs`
  static readonly WORKSPACE_DIR: string = `${DshConstants.FILES_DIR}/workspace`
  static readonly KEEPALIVE_PORT_FILE: string = `${DshConstants.FILES_DIR}/keepalive.port`
  static readonly SILENCE_FILE: string = `${DshConstants.FILES_DIR}/silence.wav`
}
```

### 4.2 DshSandboxManager.ets — proot 容器管理

```typescript
import process from '@ohos.process';
import fs from '@ohos.file.fs';
import { DshConstants } from '../model/DshConstants';

/**
 * proot 容器管理: 启动、停止、健康检查。
 *
 * 进程树:
 *   proot → node (dsh --profile mobile --no-open)
 *          → Agent Loop (Cordis, 247 个插件)
 *          → HTTP /api + WS /api/remote.mux
 *          → Tool 执行 (bash/python)
 */
export class DshSandboxManager {
  private static instance: DshSandboxManager
  private childProcess: process.Process | null = null
  private dshPort: number = 0
  private dshPid: number = 0

  public static getInstance(): DshSandboxManager {
    if (!DshSandboxManager.instance) {
      DshSandboxManager.instance = new DshSandboxManager()
    }
    return DshSandboxManager.instance
  }

  /**
   * 启动 proot 沙箱内的 dsh Host。
   */
  public async launch(apiKey: string): Promise<{ pid: number, port: number }> {
    const rootfs = DshConstants.ROOTFS_DIR
    const workspace = DshConstants.WORKSPACE_DIR
    const prootBin = DshConstants.PROOT_BIN

    // 构造 proot 命令行参数
    const args: string[] = [
      '-r', rootfs,
      '-b', '/dev',
      '-b', '/proc',
      '-b', '/sys',
      '-b', `${workspace}:/workspace`,
      '-w', '/workspace',
      '-i', '0:0',
      '-e', 'HOME=/home',
      '-e', 'DSH_HOME=/home/.dsh',
      '-e', 'DSH_PLATFORM=harmony',
      '-e', 'DSH_PROOT=1',
      '-e', `DEEPSEEK_API_KEY=${apiKey}`,
      '-e', 'PATH=/usr/bin:/bin:/usr/sbin:/sbin',
      '-e', 'NODE_OPTIONS=--max-old-space-size=256',
      '--',
      '/usr/bin/node',
      '/usr/lib/dsh/apps/cli/lib/bin.js',
      '--profile', 'mobile',
      '--no-open',
      '--port', '0'
    ]

    // 启动子进程
    const options: process.SpawnOptions = {
      env: {
        HOME: '/home',
        DSH_HOME: '/home/.dsh',
        DSH_PLATFORM: 'harmony',
        DEEPSEEK_API_KEY: apiKey,
        PATH: '/usr/bin:/bin:/usr/sbin:/sbin',
      }
    }

    try {
      this.childProcess = process.spawn(prootBin, args, options)

      // 监听 stdout 解析端口
      this.childProcess.stdout.on('data', (data: string) => {
        console.info(`[dsh] ${data}`)
        const match = data.match(/http:\/\/[\d.]+:(\d+)/)
        if (match) {
          this.dshPort = parseInt(match[1], 10)
        }
      })

      this.dshPid = this.childProcess.pid ?? 0

      // 等待端口解析 (最多 60 秒)
      for (let i = 0; i < 60; i++) {
        if (this.dshPort > 0) break
        await this.sleep(1000)
      }

      if (this.dshPort === 0) {
        throw new Error('dsh failed to start within 60 seconds')
      }

      console.info(`dsh started: pid=${this.dshPid}, port=${this.dshPort}`)
      return { pid: this.dshPid, port: this.dshPort }
    } catch (e) {
      throw new Error(`Failed to launch proot: ${e}`)
    }
  }

  /**
   * 检查 proot 进程是否存活。
   */
  public isAlive(): boolean {
    return this.childProcess !== null && !this.childProcess.killed
  }

  /**
   * 停止容器。
   */
  public async stop(): Promise<void> {
    if (this.childProcess) {
      try {
        this.childProcess.kill('SIGTERM')
        await this.sleep(2000)
        if (this.isAlive()) {
          this.childProcess.kill('SIGKILL')
        }
      } catch (e) {
        console.error(`Error stopping proot: ${e}`)
      }
      this.childProcess = null
      this.dshPort = 0
      this.dshPid = 0
    }
  }

  /**
   * 重启容器。
   */
  public async restart(): Promise<void> {
    await this.stop()
    const apiKey = await this.getApiKey()
    await this.launch(apiKey)
  }

  private async getApiKey(): Promise<string> {
    // 从 Preferences 读取 API Key
    return '' // 实现省略
  }

  private sleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms))
  }
}
```

### 4.3 DshKeepAliveManager.ets — 保活管理器

```typescript
import backgroundTaskManager from '@ohos.backgroundTaskManager';
import notificationManager from '@ohos.notificationManager';
import workScheduler from '@ohos.workScheduler';
import { DshConstants } from '../model/DshConstants';

/**
 * 鸿蒙保活管理器: 防止系统杀死 proot 容器进程。
 *
 * 保活策略:
 *   L1: ContinuousTask — 持续后台任务 (长任务, 需用户可见通知)
 *   L2: WorkScheduler — 定期健康检查
 *   L3: TCP Socket — 接收 dsh tool-keepalive 指令
 *   L4: RunningLock — CPU 保持唤醒
 */
export class DshKeepAliveManager {
  private static instance: DshKeepAliveManager
  private runningLock: RunningLock | null = null
  private socketThread: Thread | null = null

  public static getInstance(): DshKeepAliveManager {
    if (!DshKeepAliveManager.instance) {
      DshKeepAliveManager.instance = new DshKeepAliveManager()
    }
    return DshKeepAliveManager.instance
  }

  /**
   * 激活保活 (由 dsh tool-keepalive 通过 Socket 触发)。
   */
  public async activate(level: string, duration: number): Promise<void> {
    // 1. 启动持续任务 (后台长时任务)
    await this.startContinuousTask(level)

    // 2. 调度周期性健康检查
    this.scheduleHealthCheck()

    // 3. 获取 RunningLock (CPU 保持唤醒)
    if (level === 'high' || level === 'critical') {
      this.acquireRunningLock(duration)
    }

    // 4. 启动 Socket 监听
    this.startSocketListener()
  }

  /**
   * 启动持续后台任务 (需用户可见通知)。
   */
  private async startContinuousTask(level: string): Promise<void> {
    // 创建通知
    const notificationId = 1
    const notification: notificationManager.NotificationRequest = {
      id: notificationId,
      content: {
        contentType: notificationManager.ContentType.NOTIFICATION_CONTENT_BASIC_TEXT,
        normalContent: {
          title: 'DeepSeek Harness',
          text: `AI Agent 运行中 (${level})`,
          additionalText: '',
        }
      },
      notificationSlotType: notificationManager.SlotType.CONTENT_TYPE_OTHER,
    }

    try {
      // 申请持续任务 (后台长时任务)
      await backgroundTaskManager.startBackgroundRunning(
        globalThis.abilityContext,
        backgroundTaskManager.BackgroundMode.CONTINUOUS_TASK,
        notification
      )
      console.info('DSH: Continuous task started')
    } catch (e) {
      console.error(`DSH: Failed to start continuous task: ${e}`)
    }
  }

  /**
   * 调度周期性健康检查 (WorkScheduler)。
   */
  private scheduleHealthCheck(): void {
    const workInfo: workScheduler.WorkInfo = {
      workId: 1,
      triggerType: workScheduler.TriggerType.TIMESTAMP_PERIODIC,
      timeInterval: 60,
      isPersisted: true,
    }
    workScheduler.startWork(workInfo)
    console.info('DSH: Health check scheduled')
  }

  /**
   * 获取 RunningLock (CPU 保持唤醒)。
   */
  private async acquireRunningLock(duration: number): Promise<void> {
    try {
      this.runningLock = new RunningLock('DSH::KeepAlive',
        RunningLockType.BACKGROUND)
      await this.runningLock.hold(duration)
      console.info('DSH: RunningLock acquired')
    } catch (e) {
      console.error(`DSH: Failed to acquire RunningLock: ${e}`)
    }
  }

  /**
   * 释放保活。
   */
  public async release(): Promise<void> {
    try {
      await backgroundTaskManager.stopBackgroundRunning(globalThis.abilityContext)
      await workScheduler.stopWork(1)
      this.runningLock?.unhold()
      this.runningLock = null
      this.stopSocketListener()
      console.info('DSH: Keep-alive released')
    } catch (e) {
      console.error(`DSH: Error releasing keep-alive: ${e}`)
    }
  }

  /**
   * 启动 TCP Socket 监听 (接收 dsh tool-keepalive 指令)。
   */
  private startSocketListener(): void {
    // 鸿蒙 Socket API 实现省略 (逻辑与 Android/iOS 类似)
    // 1. 创建 ServerSocket, 绑定 127.0.0.1:0
    // 2. 写入端口号到 KEEPALIVE_PORT_FILE
    // 3. accept 循环接收 JSON 指令
    // 4. 解析 action/level/duration 并执行
  }

  private stopSocketListener(): void {
    this.socketThread?.cancel()
    this.socketThread = null
  }
}
```

### 4.4 DshEntryAbility.ets — UIAbility 入口

```typescript
import { UIAbility, AbilityConstant, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { DshSandboxManager } from '../service/DshSandboxManager';
import { DshKeepAliveManager } from '../service/DshKeepAliveManager';

/**
 * 应用入口 Ability: 初始化沙箱、保活, 加载 Web 组件。
 */
export default class DshEntryAbility extends UIAbility {
  private sandboxManager = DshSandboxManager.getInstance()
  private keepAliveManager = DshKeepAliveManager.getInstance()

  async onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): Promise<void> {
    // 1. 释放 assets (首次启动)
    await this.releaseAssetsIfNeeded()
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    // 加载启动页
    windowStage.loadContent('pages/Index', (err) => {
      if (err) {
        console.error(`Failed to load content: ${err}`)
      }
    })

    // 异步启动容器
    this.startContainer()
  }

  private async startContainer(): Promise<void> {
    try {
      const apiKey = await this.getApiKey()
      if (!apiKey) {
        AppStorage.set('statusText', '请先配置 API Key')
        return
      }

      AppStorage.set('statusText', '启动 AI 引擎...')
      AppStorage.set('progress', 30)

      const result = await this.sandboxManager.launch(apiKey)

      AppStorage.set('statusText', '等待 AI 就绪...')
      AppStorage.set('progress', 60)

      await this.waitForDshReady(result.port)

      AppStorage.set('statusText', '加载界面...')
      AppStorage.set('progress', 90)

      // 启动保活
      await this.keepAliveManager.activate('medium', 600)

      AppStorage.set('dshPort', result.port)
      AppStorage.set('progress', 100)
      AppStorage.set('isReady', true)
    } catch (e) {
      AppStorage.set('statusText', `启动失败: ${e}`)
    }
  }

  private async waitForDshReady(port: number): Promise<void> {
    for (let i = 0; i < 30; i++) {
      try {
        // 使用 HTTP 请求检查服务是否就绪
        const response = await fetch(`http://127.0.0.1:${port}/api`)
        if (response.status >= 200 && response.status <= 404) return
      } catch (e) {
        await new Promise(resolve => setTimeout(resolve, 1000))
      }
    }
    throw new Error('dsh failed to start within 30 seconds')
  }

  private async releaseAssetsIfNeeded(): Promise<void> {
    // 从 rawfile 释放 proot 二进制和 rootfs (首次启动)
    // 逻辑与 Android/iOS 类似
  }

  private async getApiKey(): Promise<string> {
    // 从 Preferences 读取
    return ''
  }

  onWindowStageDestroy(): void {
    // 不停止容器 (保活服务管理生命周期)
  }
}
```

### 4.5 Index.ets — 启动页 + Web 组件

```typescript
import { webview } from '@kit.ArkWeb';
import { DshWebBridge } from '../service/DshWebBridge';

/**
 * 启动页: 进度条 + Web 组件切换。
 */
@Entry
@Component
struct Index {
  @StorageLink('isReady') private isReady: boolean = false
  @StorageLink('statusText') private statusText: string = '初始化沙箱...'
  @StorageLink('progress') private progress: number = 10
  @StorageLink('dshPort') private dshPort: number = 0

  build() {
    Column() {
      if (this.isReady) {
        // Web 组件: 加载 dsh 完整 Web UI
        DshWebComponent({ port: this.dshPort })
          .layoutWeight(1)
      } else {
        // 启动画面
        Column() {
          Image($r('app.media.ic_dsh_logo'))
            .width(80).height(80)
          Text(this.statusText)
            .fontSize(18)
            .margin({ top: 20 })
          Progress({ value: this.progress, total: 100 })
            .width(200)
            .margin({ top: 20 })
        }
        .layoutWeight(1)
        .justifyContent(FlexAlign.Center)
      }
    }
    .width('100%').height('100%')
  }
}

/**
 * Web 组件: 加载 dsh Web UI (满血版)。
 */
@Component
struct DshWebComponent {
  @Prop port: number
  private webBridge = new DshWebBridge()
  private controller: webview.WebviewController = new webview.WebviewController()

  build() {
    Web({ src: `http://127.0.0.1:${this.port}`, controller: this.controller })
      .javaAccess(true)                // 启用 JavaScript
      .domStorageAccess(true)          // DOM Storage
      .fileAccess(true)                 // 文件访问
      .mixedMode(MixedMode.Allow)      // 允许混合内容
      .databaseAccess(true)             // 数据库访问
      .geolocationAccess(false)        // 不需要地理定位
      .onPageBegin(() => {
        // 注入 JS 桥接
        this.controller.runJavaScript(`
          window.DshNative = {
            getKeepAliveStatus: function() {
              return window.dshBridge.getKeepAliveStatus();
            },
            activateKeepAlive: function(level, duration) {
              window.dshBridge.activateKeepAlive(level, duration);
            },
            releaseKeepAlive: function() {
              window.dshBridge.releaseKeepAlive();
            }
          };
        `)
      })
      .layoutWeight(1)
      .width('100%')
  }
}
```

### 4.6 DshWebBridge.ets — JS ↔ Native 桥接

```typescript
import { DshKeepAliveManager } from './DshKeepAliveManager';

/**
 * JavaScript ↔ Native 桥接。
 * dsh Web UI 可通过 window.DshNative 调用原生功能。
 */
export class DshWebBridge {

  /**
   * 查询保活状态。
   */
  getKeepAliveStatus(): string {
    return JSON.stringify({
      active: true,
      level: 'medium',
      remainingSeconds: 300,
    })
  }

  /**
   * 激活保活。
   */
  activateKeepAlive(level: string, duration: number): void {
    DshKeepAliveManager.getInstance().activate(level, duration)
  }

  /**
   * 释放保活。
   */
  releaseKeepAlive(): void {
    DshKeepAliveManager.getInstance().release()
  }
}
```

---

## 五、鸿蒙特有适配项

### 5.1 ContinuousTask 后台保活

| 状态 | 存活时间 | 保活方案 |
|---|---|---|
| 前台 | 无限 | 无需保活 |
| 后台 (ContinuousTask) | 长时间 (需通知) | 持续后台任务 |
| 后台 (普通) | 短暂 | WorkScheduler 定期唤醒 |
| 被杀 | 进程终止 | 用户重新打开 App |

### 5.2 WorkScheduler 健康检查

```typescript
// 周期性健康检查配置
const workInfo: workScheduler.WorkInfo = {
  workId: 1,
  triggerType: workScheduler.TriggerType.TIMESTAMP_PERIODIC,
  timeInterval: 60,      // 60 秒
  isPersisted: true,     // 重启后保持
}
```

### 5.3 RunningLock CPU 唤醒

```typescript
// 防止 CPU 休眠 (critical 别)
const runningLock = new RunningLock(
  'DSH::KeepAlive::Critical',
  RunningLockType.BACKGROUND
)
await runningLock.hold(duration * 1000)
```

### 5.4 proot 兼容性

| 鸿蒙版本 | proot 兼容性 | 说明 |
|---|---|---|
| HarmonyOS 4.x | 部分兼容 | 基于 Android 内核, proot 可运行 |
| HarmonyOS NEXT (5.0) | 需适配 | 纯鸿蒙内核, proot 需重新编译 |
| OpenHarmony | 需适配 | 社区版, 需测试 proot 兼容性 |

**HarmonyOS NEXT 适配方案**: 如果 proot 不兼容纯鸿蒙内核, 可降级为直接执行 Node.js (无沙箱隔离), 或使用鸿蒙原生的沙箱机制。

---

## 六、鸿蒙打包发布

### 6.1 HAP 构建

```bash
# 1. 构建 dsh 满血产物
 cd /root/workspace/deepseek-harness
pnpm install && pnpm run build && pnpm run build:web

# 2. 构建 rootfs
bash scripts/build-mobile-rootfs.sh

# 3. 将 rootfs 和 proot 复制到 rawfile 目录
cp dsh-rootfs-arm64.tar.gz apps/mobile/harmony/entry/src/main/resources/rawfile/rootfs.tar.gz
cp proot-arm64 apps/mobile/harmony/entry/src/main/resources/rawfile/proot

# 4. 使用 DevEco Studio 构建
# Build → Build HAP(s)
# 产物: entry-default-signed.hap
```

### 6.2 发布到华为应用市场

```bash
# 使用 hdc 工具安装
c hdc install entry-default-signed.hap

# 发布到 AppGallery
# https://developer.huawei.com/consumer/cn/agconnect/
```

---

## 七、鸿蒙测试清单

| 测试项 | 方法 | 预期 |
|---|---|---|
| 容器启动 | 冷启动 App | 30s 内 Web 组件显示 dsh UI |
| ContinuousTask | 后台 5 分钟 | 通知栏显示, 进程不被杀 |
| WorkScheduler | kill proot 进程 | 60s 内自动重启 |
| RunningLock | 关屏 10 分钟 | dsh 继续运行, AI 任务不中断 |
| AI 保活工具 | 对话中执行长时间任务 | AI 调用 keepalive, ContinuousTask 激活 |
| 内存 | DevEco Profiler | Node.js < 300MB, Web < 200MB |
| 第三方插件 | dsh plugin add xxx | 安装后 HMR 热重载, UI 正常 |
| HarmonyOS NEXT | 在 NEXT 设备测试 | proot 兼容或降级方案生效 |
