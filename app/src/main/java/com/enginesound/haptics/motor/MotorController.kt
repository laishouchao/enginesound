package com.enginesound.haptics.motor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.enginesound.haptics.dsp.EnvelopeFollower
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/**
 * 马达驱动综合调度与保护控制器
 *
 * 负责：
 * 1. 切换【原生 Vibrator 引擎】与【ROOT Sysfs 直接硬件引擎】
 * 2. 调度【模式 A: 鼓点触觉脉冲】与【模式 B: 连续低频物理共振模拟】
 * 3. 硬件安全防护：最大振幅硬限制 (Max Limit) 与线圈过热/长载保护 (Thermal/Duty Protection)
 * 4. 延迟校准补偿队列 (Latency Calibration Queue)
 * 5. 暂停时即刻切断马达供电
 */
class MotorController(
    private val context: Context,
    var onDebugLog: ((String) -> Unit)? = null
) : AutoCloseable {

    companion object {
        private const val TAG = "MotorController"
    }

    enum class EngineType {
        NATIVE_VIBRATOR,
        ROOT_SYSFS
    }

    enum class WorkMode {
        MODE_A_SPEAKER_AND_BEAT,    // 模式 A：扬声器出声 + 马达跟随低频鼓点触觉震动
        MODE_B_VIBRATION_SIMULATION // 模式 B：扬声器静音 + 连续低频振膜物理模拟
    }

    // 当前选用的马达驱动引擎
    var currentEngineType: EngineType = EngineType.ROOT_SYSFS
        set(value) {
            field = value
            stop()
            onDebugLog?.invoke("已切换马达引擎: ${value.name}")
        }

    // 当前工作模式
    var currentWorkMode: WorkMode = WorkMode.MODE_A_SPEAKER_AND_BEAT
        set(value) {
            field = value
            stop()
            envelopeFollower.reset()
            onDebugLog?.invoke("已切换工作模式: ${value.name}")
        }

    // 用户调节参数
    var masterGain: Float = 1.0f         // 总震动增益 (0.0 ~ 2.0)
    var maxIntensityLimit: Float = 0.85f  // 最大振幅安全上限 (0.1 ~ 1.0)
    var latencyOffsetMs: Int = 0          // 延迟时间偏移补偿 (毫秒)
    var isThermalProtectionEnabled: Boolean = true

    // 引擎实例
    private val nativeEngine = NativeVibratorEngine(context)
    private val rootEngine = RootSysfsEngine()
    private val envelopeFollower = EnvelopeFollower()

    private var isRootEngineReady = false
    private val mainHandler = Handler(Looper.getMainLooper())

    // 硬件过热与线圈保护：连续高占空比追踪
    private var highLoadConsecutiveFrames = 0
    private var isThrottled = false

    // 延迟任务结构
    private data class DelayedVibration(
        val executeTimeMs: Long,
        val intensity: Float,
        val durationMs: Int
    )
    private val delayQueue = ConcurrentLinkedQueue<DelayedVibration>()

    init {
        // 尝试探测硬件节点并初始化 ROOT
        val discoveredNodes = SysfsNodeScanner.detectNodes(suShellAvailable = true)
        isRootEngineReady = rootEngine.initialize(discoveredNodes)
        if (!isRootEngineReady) {
            Log.w(TAG, "ROOT 引擎不可用，强制降级为原生 Vibrator 引擎")
            currentEngineType = EngineType.NATIVE_VIBRATOR
        }
    }

    /**
     * 接收 DSP 提取的当前音频低频能量并执行触觉分发
     * @param lowFreqEnergy 0.0f ~ 1.0f 归一化低频能量
     */
    fun processLowFreqEnergy(lowFreqEnergy: Float) {
        val calculatedIntensity: Float
        val durationMs: Int

        when (currentWorkMode) {
            WorkMode.MODE_A_SPEAKER_AND_BEAT -> {
                // 模式 A：寻找瞬态鼓点爆发点 (Transient Beat)
                val beatPower = envelopeFollower.detectBeatPulse(lowFreqEnergy)
                if (beatPower > 0.0f) {
                    calculatedIntensity = beatPower * masterGain
                    durationMs = 42 // 典型鼓点清脆打击感脉冲持续时间 (35ms ~ 45ms)
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
            delayQueue.add(DelayedVibration(targetTime, safeIntensity, durationMs))
            drainDelayedQueue()
        } else {
            dispatchVibration(safeIntensity, durationMs)
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
     * 分发振动命令到具体底层引擎
     */
    private fun dispatchVibration(intensity: Float, durationMs: Int) {
        if (intensity <= 0.001f) {
            stop()
            return
        }

        when (currentEngineType) {
            EngineType.ROOT_SYSFS -> {
                if (isRootEngineReady) {
                    rootEngine.vibrate(intensity, durationMs)
                } else {
                    nativeEngine.vibrate(intensity, durationMs)
                }
            }
            EngineType.NATIVE_VIBRATOR -> {
                nativeEngine.vibrate(intensity, durationMs)
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
                dispatchVibration(task.intensity, task.durationMs)
            } else {
                break
            }
        }
    }

    /**
     * 立即停止马达（当音乐暂停、切歌或界面点击暂停时调用）
     */
    fun stop() {
        delayQueue.clear()
        envelopeFollower.reset()
        rootEngine.stop()
        nativeEngine.stop()
    }

    override fun close() {
        stop()
        rootEngine.close()
        nativeEngine.close()
    }
}
