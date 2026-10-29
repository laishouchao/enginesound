package com.enginesound.haptics.motor

import android.content.Context
import android.util.Log
import com.enginesound.haptics.device.DeviceProfileManager
import com.enginesound.haptics.dsp.EnvelopeFollower
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min

/**
 * 马达驱动综合调度与保护控制器 (具备全品牌通用适配 + K40G 专属深度定制)
 */
class MotorController(
    private val context: Context,
    var onDebugLog: ((String) -> Unit)? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "MotorController"
    }

    enum class EngineType {
        NATIVE_VIBRATOR, // Android 原生通用 Vibrator 驱动 (兼容所有品牌手机)
        ROOT_SYSFS       // ROOT Sysfs 硬件直驱 (K40G 及已 ROOT 平台超低延迟)
    }

    enum class WorkMode {
        MODE_A_SPEAKER_AND_BEAT,    // 模式 A：扬声器出声 + 马达跟随低频鼓点触觉震动
        MODE_B_VIBRATION_SIMULATION // 模式 B：扬声器静音 + 连续低频振膜物理模拟
    }

    val deviceProfile = DeviceProfileManager.getProfile(context)

    // 引擎实例
    private val nativeEngine = NativeVibratorEngine(context)
    private val rootEngine = RootSysfsEngine()
    private val envelopeFollower = EnvelopeFollower()

    var isRootEngineReady: Boolean = false
        private set

    // 当前选用的马达驱动引擎（根据设备特征智能初始化默认值）
    var currentEngineType: EngineType = EngineType.NATIVE_VIBRATOR
        set(value) {
            if (value == EngineType.ROOT_SYSFS && !isRootEngineReady) {
                onDebugLog?.invoke("当前设备未获取 ROOT 或未找到可用硬件节点，维持通用原生引擎")
                field = EngineType.NATIVE_VIBRATOR
                return
            }
            field = value
            stop()
            onDebugLog?.invoke("已切换马达引擎: ${if (value == EngineType.ROOT_SYSFS) "ROOT Sysfs 极速硬件直驱" else "通用原生 Vibrator"}")
        }

    // 当前工作模式
    var currentWorkMode: WorkMode = WorkMode.MODE_A_SPEAKER_AND_BEAT
        set(value) {
            field = value
            stop()
            envelopeFollower.reset()
            onDebugLog?.invoke("已切换工作模式: ${if (value == WorkMode.MODE_A_SPEAKER_AND_BEAT) "模式 A (扬声器+鼓点)" else "模式 B (仅振动模拟/扬声器静音)"}")
        }

    // 用户调节参数
    var masterGain: Float = 1.0f         // 总震动增益 (0.0 ~ 2.0)
    var maxIntensityLimit: Float = 0.85f  // 最大振幅安全上限 (0.1 ~ 1.0)
    var latencyOffsetMs: Int = 0          // 延迟时间偏移补偿 (毫秒)
    var isThermalProtectionEnabled: Boolean = true

    // 硬件过热与线圈保护：连续高占空比追踪
    private var highLoadConsecutiveFrames = 0
    private var isThrottled = false

    // 延迟任务结构
    private data class DelayedVibration(
        val executeTimeMs: Long,
        val intensity: Float,
        val durationMs: Int,
        val isBeat: Boolean
    )
    private val delayQueue = ConcurrentLinkedQueue<DelayedVibration>()

    init {
        // 智能探查机型与 ROOT 状态
        if (deviceProfile.isRooted) {
            try {
                val discoveredNodes = SysfsNodeScanner.detectNodes(suShellAvailable = true)
                isRootEngineReady = rootEngine.initialize(discoveredNodes)
            } catch (e: Exception) {
                Log.w(TAG, "探查 ROOT 节点异常，安全降级: ${e.message}")
                isRootEngineReady = false
            }
        } else {
            isRootEngineReady = false
        }

        // 默认引擎判定：如果是已 ROOT 的 K40G，默认启动最高性能的 ROOT_SYSFS；其余手机默认采用兼容性最稳的原生引擎
        currentEngineType = if (deviceProfile.isRedmiK40G && isRootEngineReady) {
            EngineType.ROOT_SYSFS
        } else {
            EngineType.NATIVE_VIBRATOR
        }

        onDebugLog?.invoke("设备适配画像: ${deviceProfile.recommendedEngineDescription}")
    }

    /**
     * 接收 DSP 提取的当前音频低频能量并执行触觉分发
     * @param lowFreqEnergy 0.0f ~ 1.0f 归一化低频能量
     */
    fun processLowFreqEnergy(lowFreqEnergy: Float) {
        val calculatedIntensity: Float
        val durationMs: Int
        var isBeat = false

        when (currentWorkMode) {
            WorkMode.MODE_A_SPEAKER_AND_BEAT -> {
                // 模式 A：寻找瞬态鼓点爆发点 (Transient Beat)
                val beatPower = envelopeFollower.detectBeatPulse(lowFreqEnergy)
                if (beatPower > 0.0f) {
                    calculatedIntensity = beatPower * masterGain
                    durationMs = 42 // 典型鼓点清脆打击感脉冲持续时间 (35ms ~ 45ms)
                    isBeat = true
                } else {
                    return
                }
            }

            WorkMode.MODE_B_VIBRATION_SIMULATION -> {
                // 模式 B：连续不对称包络平滑，模拟低音单元物理连续共振
                val smoothEnvelope = envelopeFollower.processContinuous(lowFreqEnergy)
                if (smoothEnvelope <= 0.005f) {
                    stop()
                    return
                }
                calculatedIntensity = smoothEnvelope * masterGain
                durationMs = 24 // 连续帧间隔驱动 (20ms ~ 25ms)
                isBeat = false
            }
        }

        // 1. 施加最大强度限制保护 (硬顶截断)
        var safeIntensity = min(calculatedIntensity, maxIntensityLimit)

        // 2. 施加线圈过热与机械寿命保护 (Duty Cycle 软保护)
        if (isThermalProtectionEnabled) {
            safeIntensity = applyThermalProtection(safeIntensity)
        }

        // 3. 处理延迟校准
        if (latencyOffsetMs > 0) {
            val targetTime = System.currentTimeMillis() + latencyOffsetMs
            delayQueue.add(DelayedVibration(targetTime, safeIntensity, durationMs, isBeat))
            drainDelayedQueue()
        } else {
            dispatchVibration(safeIntensity, durationMs, isBeat)
        }
    }

    /**
     * 持续高负荷降温降幅保护算法
     */
    private fun applyThermalProtection(inputIntensity: Float): Float {
        if (inputIntensity > 0.80f) {
            highLoadConsecutiveFrames++
            // 如果连续超过 100 帧（约 2 秒钟）保持在 80% 以上极高振幅
            if (highLoadConsecutiveFrames > 100) {
                if (!isThrottled) {
                    isThrottled = true
                    onDebugLog?.invoke("⚠️ 触发马达过热保护：连续高功率输出，已自动限制振幅至 60%")
                }
                return inputIntensity * 0.60f
            }
        } else {
            if (highLoadConsecutiveFrames > 0) highLoadConsecutiveFrames--
            if (highLoadConsecutiveFrames < 20 && isThrottled) {
                isThrottled = false
                onDebugLog?.invoke("马达负荷已恢复安全状态，解除过热保护限制")
            }
        }
        return inputIntensity
    }

    /**
     * 分发振动命令到底层驱动引擎
     */
    private fun dispatchVibration(intensity: Float, durationMs: Int, isBeat: Boolean) {
        if (intensity <= 0.001f) {
            stop()
            return
        }

        when (currentEngineType) {
            EngineType.ROOT_SYSFS -> {
                if (isRootEngineReady) {
                    rootEngine.vibrate(intensity, durationMs)
                } else {
                    nativeEngine.vibrate(intensity, durationMs, isBeatPulse = isBeat)
                }
            }
            EngineType.NATIVE_VIBRATOR -> {
                if (isBeat && deviceProfile.supportsRichHaptics) {
                    nativeEngine.vibrateRichBeat(intensity)
                } else {
                    nativeEngine.vibrate(intensity, durationMs, isBeatPulse = isBeat)
                }
            }
        }
    }

    /**
     * 处理正向延迟队列中的振动任务
     */
    private fun drainDelayedQueue() {
        val now = System.currentTimeMillis()
        while (!delayQueue.isEmpty()) {
            val task = delayQueue.peek() ?: break
            if (now >= task.executeTimeMs) {
                delayQueue.poll()
                dispatchVibration(task.intensity, task.durationMs, task.isBeat)
            } else {
                break
            }
        }
    }

    /**
     * 立即切断马达供电与动作
     */
    fun stop() {
        delayQueue.clear()
        envelopeFollower.reset()
        if (isRootEngineReady) {
            rootEngine.stop()
        }
        nativeEngine.stop()
    }

    override fun close() {
        stop()
        rootEngine.close()
        nativeEngine.close()
    }
}
