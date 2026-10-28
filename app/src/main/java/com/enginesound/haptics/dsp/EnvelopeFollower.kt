package com.enginesound.haptics.dsp

import kotlin.math.max
import kotlin.math.min

/**
 * 包络跟随器与平滑滤波器
 *
 * 核心目的：
 * 1. 平滑滤波（EMA / 双斜率包络）：防止马达因为音频帧间能量突变而产生剧烈的机械碎震或频闪抖动（爆震）。
 * 2. 模式 A (触觉鼓点)：检测低频瞬态跳变（Transient Beat），输出瞬态脉冲。
 * 3. 模式 B (连续振动模拟)：将音频低频能量连续映射为物理共振，如同让马达扮演手机内的低音喇叭振膜。
 */
class EnvelopeFollower(
    // 启动系数 (Attack)：能量上升时的响应速度，较高值保证鼓点来临时马达响应迅速 (0.0 ~ 1.0)
    var attackCoeff: Float = 0.65f,
    // 释放系数 (Decay/Release)：能量下降时的回落速度，平缓回落可消除断崖式断电造成的杂音 (0.0 ~ 1.0)
    var decayCoeff: Float = 0.25f
) {
    // 当前平滑后的连续能量状态 (用于模式 B)
    private var currentContinuousEnvelope: Float = 0.0f

    // 瞬态检测器历史均值 (用于模式 A 瞬态鼓点判定)
    private var shortTermEnergyAvg: Float = 0.0f
    private var lastBeatTimestamp: Long = 0L

    /**
     * 重置滤波器状态（例如歌曲切换、暂停时）
     */
    fun reset() {
        currentContinuousEnvelope = 0.0f
        shortTermEnergyAvg = 0.0f
        lastBeatTimestamp = 0L
    }

    /**
     * 模式 B 专用：连续包络平滑计算
     * 将原始瞬时低频能量经过不对称低通滤波，生成连贯柔顺的驱动信号
     */
    fun processContinuous(rawEnergy: Float): Float {
        val target = min(1.0f, max(0.0f, rawEnergy))

        currentContinuousEnvelope = if (target > currentContinuousEnvelope) {
            // 上升段：快速跟随
            currentContinuousEnvelope + attackCoeff * (target - currentContinuousEnvelope)
        } else {
            // 下降段：平滑衰减，避免电机突然掉电造成冲击
            currentContinuousEnvelope - decayCoeff * (currentContinuousEnvelope - target)
        }

        // 极小信号衰减到底，防止长期残留微弱直流
        if (currentContinuousEnvelope < 0.01f) {
            currentContinuousEnvelope = 0.0f
        }

        return currentContinuousEnvelope
    }

    /**
     * 模式 A 专用：瞬态鼓点检测 (Transient Beat Detection)
     * 当瞬时能量相比于前期的移动均值出现爆发式突跃时，判定为鼓点
     *
     * @param rawEnergy 当前帧的原始低频能量
     * @param minBeatIntervalMs 两次鼓点之间的最小冷却时间（防止同一个大鼓敲击产生多次碎震）
     * @return 触发鼓点时的强度 (0.0f 表示未触发，>0.0f 表示鼓点振动强度)
     */
    fun detectBeatPulse(
        rawEnergy: Float,
        sensitivityRatio: Float = 1.35f,
        minBeatIntervalMs: Long = 90L
    ): Float {
        val now = System.currentTimeMillis()

        // 移动平均线跟踪背景低频水平
        shortTermEnergyAvg = 0.88f * shortTermEnergyAvg + 0.12f * rawEnergy

        // 判断当前能量是否显著超越均值，并且达到触发门槛
        val isTransientPeak = rawEnergy > 0.22f && rawEnergy > (shortTermEnergyAvg * sensitivityRatio)
        val isCooledDown = (now - lastBeatTimestamp) >= minBeatIntervalMs

        return if (isTransientPeak && isCooledDown) {
            lastBeatTimestamp = now
            // 鼓点脉冲强度映射
            min(1.0f, max(0.2f, rawEnergy))
        } else {
            0.0f
        }
    }
}
