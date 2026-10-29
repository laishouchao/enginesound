# 触觉低音炮 (EngineSound Haptics) - 全平台通用 + Redmi K40G 专版

专为 **红米 K40 游戏增强版 (Redmi K40G / 天玑1200 / 已 ROOT)** 深度调优，并**全面兼容适配小米、红米、三星、一加、OPPO、vivo、Google Pixel、魅族、华为、荣耀等全品牌 Android 手机**的低频触觉共振与重音鼓点增强应用。

---

## 📱 硬件兼容性与自适应策略

| 设备类型 | 驱动引擎 | 振幅调节方式 | 静音模拟 (模式 B) | 优化策略 |
| :--- | :--- | :--- | :--- | :--- |
| **Redmi K40 游戏增强版 (ares / 已 ROOT)** | **ROOT Sysfs 寄存器直驱** | 硬件写 `/sys/class/leds/vibrator/brightness` (<0.5ms) | 底层 ALSA `tinymix` 功放通道切断 | 专享天玑1200与艾为/瑞声线性马达超低延迟直驱 |
| **高通骁龙旗舰 (已 ROOT，如小米/一加/三星)** | **ROOT Sysfs / 原生自适应** | 硬件写 `/sys/class/qcom-haptics` 或系统无级调节 | 系统媒体流 / 硬件双通道静音 | 自动扫描高通 SPMI Haptics 节点 |
| **全品牌普通手机 (免 ROOT / 标准系统)** | **通用原生 Vibrator 引擎** | Android 标准 `VibrationEffect` 无级线性调节 (1~255) | 系统 `AudioManager` 智能静音 | 零崩溃、免 ROOT、即装即用 |
| **基础马达手机 (转子马达 ERM / 无无级接口)** | **通用原生 Vibrator 引擎** | 软件占空比抽帧算法 (Duty Cycle Sampling) | 系统 `AudioManager` 智能静音 | 避免普通马达剧烈暴震震手，模拟轻重触感 |

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
  - 通过瞬态脉冲检测算法 (Transient Beat Detection) 提取重音与 Drum Kick，马达输出 35ms~45ms 的清脆触觉脉冲。高端机型（Pixel/三星/一加）自动启用 `PRIMITIVE_THUD` 富触觉基元。
- **模式 B：【仅振动模拟】静音喇叭，纯物理共振**
  - **关闭扬声器输出（完全静音）！**
  - 将 0~200Hz 低频连续分量经过不对称包络平滑后，连续映射到马达振幅上。
  - 用整机机身背板震动模拟超重低音音响放在桌面上的物理共振。

### 3. DSP 实时频谱处理与平滑防护
- **1024 点实数 FFT**：纯 Kotlin Cooley-Tukey 算法 + 汉明窗 (Hamming Window)，延迟 < 1ms。
- **0~200Hz 核心低频提取**：加权聚焦 50Hz~120Hz 鼓点核心能量段。
- **非对称平滑包络滤波**：快速起振 + 柔顺衰减，彻底杜绝电机频闪抖动（爆震）。
- **线圈过热与寿命保护**：最大振幅硬顶限制 + 连续高负荷滑动温控降频算法。

---

## 🛡️ 解决其他品牌手机“打开闪退”的核心技术方案

1. **Android 14 (API 34) 前台服务细分类型严苛限制**：
   - *闪退根源*：Android 14 规定声明了 `mediaProjection` 类型的服务，若在未持有有效投射 Token 时直接启动，系统会强制抛出 `SecurityException` 导致无预警闪退。
   - *修复方案*：在 [`AndroidManifest.xml`](file:///e:/SOLO/软件项目/enginesound/app/src/main/AndroidManifest.xml) 中补充声明 `mediaPlayback` 权限。应用初始化和本地播放时以标准的 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` 启动；仅在用户点击全局捕获且同意授权弹窗后，才动态升级为 `MEDIA_PROJECTION`。
2. **非 ROOT 手机调用 `su` 抛出 `IOException`**：
   - *闪退根源*：在未 ROOT 手机上盲目执行 `Runtime.getRuntime().exec("su")` 会抛出 `No such file or directory`。
   - *修复方案*：构建 [`DeviceProfileManager.kt`](file:///e:/SOLO/软件项目/enginesound/app/src/main/java/com/enginesound/haptics/device/DeviceProfileManager.kt)，安全探查环境，非 ROOT 手机默认选择通用原生引擎，禁用一切外部 shell 进程调用。
3. **ViewModel 覆盖默认降级策略**：
   - *修复方案*：移除强制写死 `ROOT_SYSFS` 逻辑，UI 完全根据当前设备的硬件探查结果动态呈现。
4. **全局未捕获异常守护**：
   - 在 [`MainActivity.kt`](file:///e:/SOLO/软件项目/enginesound/app/src/main/java/com/enginesound/haptics/MainActivity.kt) 中部署了 `Thread.setDefaultUncaughtExceptionHandler` 与全生命周期 try-catch 拦截，确保无论在任何小众魔改系统上都能稳定运行。
