package com.enginesound.haptics.motor

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.enginesound.haptics.device.DeviceProfileManager
import kotlin.math.roundToInt

/**
 * 通用 Android 原生 Vibrator / VibrationEffect 引擎
 *
 * 核心设计：
 * 1. 适配全部 Android 10 ~ 15 手机（三星、一加、小米、OPPO、vivo、Pixel、华为、荣耀等）。
 * 2. 具备完善的振幅自适应能力：
 *    - 若手机支持无级振幅调频 (Linear Motor / X轴/Z轴)：精确输出 1~255 强度。
 *    - 若手机仅支持基础振动 (如转子马达 ERM / 基础触觉)：采用时间占空比 (Duty-Cycle) 模拟轻重振感，避免剧烈震手。
 * 3. 增强 Android 11+ / 12+ 富触觉基元支持 (Rich Haptics)。
 * 4. 零异常泄漏：在无马达设备、平板或禁用振动模式下优雅跳过，绝不崩溃。
 */
class NativeVibratorEngine(context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "NativeVibratorEngine"
    }

    private val vibrator: Vibrator? = DeviceProfileManager.getSystemVibrator(context)

    val hasVibrator: Boolean = vibrator?.hasVibrator() ?: false
    val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() ?: false

    // 转子马达或不支持无级振幅设备使用的占空比计数器
    private var ermFrameCounter = 0

    init {
        Log.i(TAG, "通用原生 Vibrator 引擎初始化完成: hasVibrator=$hasVibrator, hasAmplitudeControl=$hasAmplitudeControl")
    }

    /**
     * 触发通用系统振动
     * @param intensity 0.0f ~ 1.0f 归一化振动强度
     * @param durationMs 持续毫秒
     * @param isBeatPulse 是否为模式 A 的瞬态鼓点脉冲
     */
    fun vibrate(intensity: Float, durationMs: Int, isBeatPulse: Boolean = false) {
        val targetVibrator = vibrator ?: return
        if (!hasVibrator) return

        val clampedIntensity = intensity.coerceIn(0.0f, 1.0f)
        if (clampedIntensity <= 0.005f) {
            stop()
            return
        }

        val duration = durationMs.coerceIn(10, 1000).toLong()

        try {
            if (hasAmplitudeControl) {
                // 1. 支持无级振幅调频的高品质线性马达手机
                val amplitude = (clampedIntensity * 255.0f).roundToInt().coerceIn(1, 255)
                val effect = VibrationEffect.createOneShot(duration, amplitude)
                targetVibrator.vibrate(effect)
            } else {
                // 2. 普通转子马达或被系统屏蔽了无级接口的机型：
                // 采用软件占空比抽帧算法 (Duty Cycle Sampling) 模拟轻重触感
                ermFrameCounter++
                val shouldTrigger = when {
                    clampedIntensity > 0.75f -> true
                    clampedIntensity > 0.45f -> (ermFrameCounter % 2 == 0)
                    clampedIntensity > 0.20f -> (ermFrameCounter % 3 == 0)
                    else -> (ermFrameCounter % 5 == 0)
                }

                if (shouldTrigger) {
                    val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                    targetVibrator.vibrate(effect)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行原生振动调用异常", e)
        }
    }

    /**
     * 针对支持 Rich Haptics 的高端手机（如 Pixel / 三星 / 一加旗舰），输出极其干脆的鼓点清脆敲击
     */
    fun vibrateRichBeat(scale: Float) {
        val targetVibrator = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val clampedScale = scale.coerceIn(0.1f, 1.0f)
                val effect = VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, clampedScale)
                    .compose()
                targetVibrator.vibrate(effect)
                return
            } catch (ignored: Exception) {}
        }
        // 若不支持富触觉基元，平滑回退至标准 createOneShot
        vibrate(scale, 40, isBeatPulse = true)
    }

    /**
     * 立即切断系统振动
     */
    fun stop() {
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "取消原生振动失败", e)
        }
    }

    override fun close() {
        stop()
    }
}
