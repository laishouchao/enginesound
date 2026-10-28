package com.enginesound.haptics.dsp

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 低频能量提取器 (0 ~ 200 Hz)
 *
 * 作用：从 FFT 频域幅值数组中定位属于 20Hz ~ 200Hz (BASS / 鼓点 / 超重低音) 的频点，
 * 计算其瞬时有效能量，并进行增益放大与归一化。
 */
class LowFrequencyExtractor(
    private val sampleRate: Int = 44100,
    private val fftSize: Int = 1024
) {
    // 频域分辨率：每个频点 Bin 代表的频率跨度 (例如 44100 / 1024 ≈ 43.06 Hz)
    private val binWidth: Float = sampleRate.toFloat() / fftSize

    // 低频范围限制：20Hz ~ 200Hz
    // 过滤掉低于 20Hz 的直流漂移与无法振动的超低频，聚焦于对振动效果最显著的鼓点与贝斯区
    private val minFreq = 20f
    private val maxFreq = 200f

    private val startBin: Int = max(1, (minFreq / binWidth).toInt())
    private val endBin: Int = min(fftSize / 2 - 1, (maxFreq / binWidth).toInt() + 1)

    /**
     * 计算并提取当前帧的低频能量
     * @param magnitudes FFT 计算出的幅值数组
     * @param sensitivityGain 增益放大倍数 (来自用户界面滑块)
     * @param threshold 触发门槛 (低于此值视作背景杂音，归零)
     * @return 归一化后的能量值 (0.0f ~ 1.0f)
     */
    fun extractLowFrequencyEnergy(
        magnitudes: FloatArray,
        sensitivityGain: Float = 1.0f,
        threshold: Float = 0.15f
    ): Float {
        if (startBin >= endBin || endBin > magnitudes.size) {
            return 0.0f
        }

        var sumSq = 0.0f
        var binCount = 0

        for (k in startBin..endBin) {
            val mag = magnitudes[k]
            // 人耳与触觉对 50Hz~120Hz (Kick / 808 鼓点) 最为敏感，赋予稍微更高的权重
            val freq = k * binWidth
            val weight = if (freq in 45f..130f) 1.25f else 1.0f

            sumSq += (mag * weight) * (mag * weight)
            binCount++
        }

        if (binCount == 0) return 0.0f

        // 计算低频段的均方根 (RMS) 能量
        val rms = sqrt(sumSq / binCount)

        // 动态范围缩放 (PCM 16位归一化到 0~1 的实数输入下的经验标尺)
        val normalizedEnergy = (rms / 250.0f) * sensitivityGain

        // 门限判定：低于阈值直接截断为 0，防止安静间奏或底噪导致马达微颤
        return if (normalizedEnergy < threshold) {
            0.0f
        } else {
            // 对超过门限的部分进行线性重映射，保持平滑过渡
            val adjusted = (normalizedEnergy - threshold) / (1.0f - threshold)
            min(1.0f, max(0.0f, adjusted))
        }
    }
}
