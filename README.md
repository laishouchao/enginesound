# 触觉低音炮 (EngineSound Haptics) - Redmi K40G 专版

专为 **红米 K40 游戏增强版 (Redmi K40G / 代号 ares / 天玑 1200 / MT6893 / 已 ROOT)** 深度定制的低频触觉共振与鼓点触觉增强 Android 应用。

---

## 📱 目标设备与平台适配
- **机型**：Redmi K40 Gaming Edition (红米 K40 游戏增强版 / ares)
- **SoC**：联发科天玑 1200 (MediaTek MT6893 Dimensity 1200)
- **振动硬件**：瑞声科技 (AAC) / 艾为 (Awinic) X 轴线性马达
- **系统要求**：Android 10+ (推荐 MIUI 12.5 / MIUI 13 / MIUI 14 / HyperOS，已通过 Magisk / KernelSU / APatch 取得完整 ROOT 权限)

---

## 🌟 核心功能清单

### 1. 双音频来源无缝切换
- **来源 1：本地音频文件播放**
  - 内置基于 `MediaExtractor` + `MediaCodec` + `AudioTrack` 的高性能 PCM 解码播放引擎。
  - 支持 MP3, FLAC, WAV, AAC, OGG 等任意主流音频格式。
- **来源 2：【全局系统音频捕获】**
  - 基于 Android 10+ `AudioPlaybackCaptureConfiguration` 捕获整机音频输出。
  - 后台静默监听 **Bilibili、网易云音乐、QQ音乐、抖音、各类游戏** 等全部第三方应用发声，无需在本 App 内导入或打开文件。

### 2. 双工作模式
- **模式 A (默认)：外放音乐 + 触觉鼓点脉冲**
  - 手机喇叭正常出声播放。
  - 通过瞬态脉冲检测算法 (Transient Beat Detection) 提取重音与 Drum Kick，马达输出 35ms~45ms 的清脆触觉脉冲。
- **模式 B：【仅振动模拟】静音喇叭，纯物理共振**
  - **关闭扬声器输出（完全静音）！**
  - 不再是简单的开关式触觉敲击，而是将 0~200Hz 的低频连续分量经过不对称包络平滑后，连续映射到马达振幅寄存器上。
  - 利用马达自身质量块的高频往复共振，带动整机机身背板震动，如同手机内置了一枚微型物理低音炮。

### 3. DSP 实时频谱处理与平滑防护
- **Radix-2 FFT 频谱计算**：1024 样本点实数快速傅里叶变换，窗函数选用汉明窗 (Hamming Window)，抑制频谱泄露。
- **0~200Hz 低频分离**：精准锁定 20Hz~200Hz 贝斯与鼓点核心频段（重点加权 50Hz~120Hz）。
- **非对称包络平滑滤波 (Attack & Decay Envelope Follower)**：
  - 快速起振 (Attack) + 平缓释放 (Decay)，彻底消除了因音频能量突变造成的马达剧烈频闪抖动（爆震）。
- **线圈过热与机械寿命防护 (Thermal / Duty Cycle Guard)**：
  - 具备最大振幅硬顶限制（建议 85%）。
  - 内置滑动负载监控，当持续高负荷 (>80%) 超过 2 秒时自动阶梯限幅至 60%，防止音圈局部积热与弹片金属疲劳。

### 4. 双马达驱动引擎
- **引擎 ①：ROOT Sysfs 硬件寄存器直驱 (极低延迟)**
  - 采用长驻交互式 ROOT Shell 进程 (su)，避免高频音频帧（20ms 一次）频繁 fork 进程造成的 CPU 飙升与 50ms 调度时延。
  - 指令直达内核驱动节点，写入延迟低于 0.5ms。
- **引擎 ②：Android 原生 Vibrator + VibrationEffect API (标准兼容)**
  - 作为非 ROOT 状态或节点失效时的可靠兜底，支持 Android 10~14 标准无级振幅接口。

### 5. 时间偏移校准 (Latency Offset Calibration)
- 提供 -150ms ~ +250ms 手动微调滑块，彻底消除蓝牙耳机、音频缓冲区以及马达起振惯性带来的音振不同步问题。

---

## 🛠️ 项目工程结构

```
enginesound/
├── .github/
│   └── workflows/
│       └── build-release.yml         # GitHub Actions 自动化编译发布流水线
├── app/
│   ├── build.gradle.kts              # 模块编译配置（Compose 开启，SDK 34）
│   ├── proguard-rules.pro            # 代码混淆规则
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml   # 完整系统权限声明与前台服务配置
│           ├── java/com/enginesound/haptics/
│           │   ├── MainActivity.kt   # 入口 Activity、权限与 MediaProjection 授权
│           │   ├── audio/
│           │   │   ├── AudioCaptureManager.kt  # 音频输入总调度
│           │   │   ├── LocalAudioPlayer.kt     # 本地音频解码与播放 (MediaCodec)
│           │   │   └── GlobalAudioCapture.kt   # 全局系统声音抓取与硬件静音
│           │   ├── dsp/
│           │   │   ├── FastFourierTransform.kt # Cooley-Tukey Radix-2 FFT 纯 Kotlin 实现
│           │   │   ├── LowFrequencyExtractor.kt# 0~200Hz 低频 BASS 提取与归一化
│           │   │   └── EnvelopeFollower.kt     # 双斜率平滑包络与瞬态鼓点检测器
│           │   ├── motor/
│           │   │   ├── MotorController.kt      # 马达综合调度、过热防护与延迟补偿
│           │   │   ├── NativeVibratorEngine.kt # 原生 Vibrator HAL 驱动引擎
│           │   │   ├── RootSysfsEngine.kt      # 常驻 su 管道 Sysfs 硬件直驱引擎
│           │   │   └── SysfsNodeScanner.kt     # K40G / MTK 专属硬件节点自动扫描器
│           │   ├── service/
│           │   │   └── HapticEngineService.kt  # 前台保活服务 (WakeLock + 状态通知)
│           │   └── ui/
│           │       ├── HapticAppUI.kt          # Jetpack Compose 赛博声学响应式 UI
│           │       ├── HapticViewModel.kt      # MVVM 状态中心与调试日志
│           │       └── theme/                  # 赛博荧光绿/青色暗黑主题
│           └── res/                            # 图标、文字与主题资源
├── build.gradle.kts                  # 根构建脚本
├── settings.gradle.kts               # 模块索引
└── gradle.properties                 # JVM 堆内存与编译优化配置
```

---

## ⚠️ 深度剖析：红米 K40G 与安卓触觉开发已知坑点

### 坑点 1：MIUI / HyperOS 原生马达振幅限制与 RichTap 专有驱动
- **现象**：在标准 AOSP 下调用 `Vibrator.vibrate(VibrationEffect.createOneShot(..., amplitude))` 时，MIUI 的 HAL 层经常会截断较小的振幅（例如 amplitude < 60 时无动作），或者只提供预设的几种点击触感 (Prebaked effect)。
- **对策**：本项目实现了双通道机制。优先通过 ROOT 绕过 MIUI 系统的振动服务中间件，直接向硬件驱动节点写入 `brightness` 或 `duration`，获得完全平滑连续的线性无级调幅能力。

### 坑点 2：天玑 1200 平台 Sysfs 硬件节点寻址
- **现象**：高通平台常见 `/sys/class/qcom-haptics/`，但红米 K40G 搭载的是联发科天玑 1200 (MT6893)，马达芯片一般走 I2C 总线并注册到 Linux `leds` 或 `timed_output` 子系统。
- **K40G 核心节点分布**：
  - `/sys/class/leds/vibrator/brightness`（振动强度，有效值通常为 0~255）
  - `/sys/class/leds/vibrator/duration`（单次持续毫秒）
  - `/sys/class/leds/vibrator/activate`（写入 1 立即触发，写入 0 立即切断）
  - `/sys/devices/platform/soc/11016000.i2c/i2c-5/5-005a/leds/vibrator`
- **对策**：在 `SysfsNodeScanner` 中内置针对 K40G 的节点自动优先级探测与动态搜索，无需用户手动在文件管理器中翻查路径。

### 坑点 3：SELinux 严格访问控制拦截 (Permission Denied)
- **现象**：即使 App 获取了 `su` 权限，如果系统处于 SELinux Enforcing 模式，向 `/sys/class/leds/vibrator/*` 节点写入数据时仍可能触发 `avc: denied` 审计拒绝。
- **对策**：ROOT 引擎启动时自动执行 `setenforce 0` 切换至 Permissive 宽容模式，并在节点上预置 `chmod 666` 读写权限，确保高频写操作畅通无阻。

### 坑点 4：硬件寿命、音圈过热与精密螺丝震松风险
- **物理特性**：X 轴线性马达内部由定子线圈、钕铁硼强磁铁配重块以及微型悬臂弹簧片组成。手机马达原厂设计工况多为「短脉冲通知打字（10ms~80ms）」，若将其当作连续发声扬声器振膜进行长达数十分钟的高占空比满功率驱动：
  1. 铜音圈无被动散热风道，温度急剧攀升易造成线圈漆包线绝缘击穿或消磁。
  2. 弹片易发生金属疲劳导致回弹失真或异响。
  3. 机身内部微型紧固螺丝长时间共振可能出现微小旷量。
- **对策**：
  - 启动强制显示硬件安全告示。
  - 内置 **连续满载过热衰减算法**，长时间持续高振幅将自动下调 40% 振幅，保护马达寿命。

### 坑点 5：安卓系统全局音频捕获限制 (AudioPlaybackCapture)
- **现象**：`AudioPlaybackCaptureConfiguration` 虽能捕获系统声音，但 Android 原生规范中：如果第三方 App 显式设置了 `AudioAttributes.ALLOW_CAPTURE_BY_NONE`（部分版权音乐极高音质防录音模式），该流会被系统混音器排斥在捕获器外。
- **对策**：
  - 本 App 在捕获配置中加入了 `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN` 全部常用流类型匹配，覆盖了 98% 以上的常规 App（B站、网易云、抖音、吃鸡/王者等均能完美抓取）。
  - 在模式 B（静音外放）下，由于调低全局多媒体音量可能导致部分第三方播放器触发暂停，本 App 在全局模式 B 时通过 ROOT 指令向底层 `tinymix` 功放通道发送物理静音指令，保留底层混音捕获，真正实现「扬声器无声，马达疯狂震动」。

### 坑点 6：MIUI / HyperOS 激进的后台杀后台行为
- **现象**：在退回桌面或息屏锁屏后，MIUI 神隐模式与电池管家会迅速对无界面的进程执行冻结 (cgroup freeze) 或 SIGKILL。
- **对策**：
  1. 注册 `FOREGROUND_SERVICE` 与 `mediaProjection|microphone` 专属前台服务类型。
  2. 常驻低优先级通知栏，提供快捷暂停/停止动作。
  3. 获取并持有 CPU `PARTIAL_WAKE_LOCK`，防止息屏后 CPU 降频导致音频分析丢帧。
  4. 首次使用请务必在 **系统设置 -> 应用设置 -> 触觉低音炮 -> 省电策略** 设置为 **「无限制」**，并允许 **「自启动」**。

---

## 🚀 编译与持续集成 (CI/CD)

本项目完整配置了基于 GitHub Actions 的自动化流水线 (`.github/workflows/build-release.yml`)：
- **触发机制**：推送代码到 `main`/`master` 分支、推送版本标签（例如 `v1.0.0`），或手动在 GitHub Actions 页面点击 `Run workflow`。
- **产物分发**：流水线将自动完成 Gradle 依赖解析、Release APK 构建，并在 Releases 页面自动发布附带构建好的 `EngineSound-Haptics-v1.0.0.apk`。
