package com.enginesound.haptics.motor

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.roundToInt

/**
 * Android 原生 Vibrator / VibrationEffect 引擎
 *
 * 作为非 ROOT 或硬件节点无法访问时的可靠兜底 (Fallback)。
 * 利用系统标准的硬件抽象层 (HAL) 进行振动，对系统版本及权限要求最低。
 */
class NativeVibratorEngine(context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "NativeVibratorEngine"
    }

    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    val hasAmplitudeControl: Boolean = vibrator.hasAmplitudeControl()

    init {
        Log.i(TAG, "原生 Vibrator 引擎就绪，振幅无级调节支持状态: $hasAmplitudeControl")
    }

    /**
     * 触发原生系统振动
     * @param intensity 0.0f ~ 1.0f 归一化强度
     * @param durationMs 持续毫秒
     */
    fun vibrate(intensity: Float, durationMs: Int) {
        val clampedIntensity = intensity.coerceIn(0.0f, 1.0f)
        if (clampedIntensity <= 0.001f) {
            stop()
            return
        }

        val duration = durationMs.coerceIn(10, 1000).toLong()

        try {
            if (hasAmplitudeControl) {
                // 将浮点强度映射到系统 1~255 振幅
                val amplitude = (clampedIntensity * 255.0f).roundToInt().coerceIn(1, 255)
                val effect = VibrationEffect.createOneShot(duration, amplitude)
                vibrator.vibrate(effect)
            } else {
                // 若 ROM 屏蔽了原生无级振幅接口，使用默认强度
                val effect = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行原生振动调用失败", e)
        }
    }

    /**
     * 立即终止当前所有震动
     */
    fun stop() {
        try {
            vibrator.cancel()
        } catch (e: Exception) {
            Log.e(TAG, "取消原生振动失败", e)
        }
    }

    override fun close() {
        stop()
    }
}
