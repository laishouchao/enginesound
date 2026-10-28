package com.enginesound.haptics.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 快速傅里叶变换 (Cooley-Tukey Radix-2 FFT) 纯 Kotlin 实现
 * 针对实时音频处理做了轻量化优化，无需集成复杂的 C/C++ NDK 即可在 Android 上运行。
 */
class FastFourierTransform(private val n: Int) {

    init {
        require(n > 0 && (n and (n - 1)) == 0) { "FFT 样本大小 N 必须是 2 的整数次幂 (例如 512, 1024, 2048)" }
    }

    // 汉明窗（Hamming Window）预计算查找表，用于平滑音频切片两端，防止频域能量泄露 (Spectral Leakage)
    private val window: FloatArray = FloatArray(n) { i ->
        (0.54 - 0.46 * cos(2.0 * PI * i / (n - 1))).toFloat()
    }

    // 位翻转重排表 (Bit Reversal Lookup Table) 提前预计算，加速计算过程
    private val bitReverse: IntArray = IntArray(n)

    init {
        var j = 0
        for (i in 0 until n - 1) {
            bitReverse[i] = j
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }
        bitReverse[n - 1] = n - 1
    }

    /**
     * 执行实数序列的 FFT 计算
     * @param realInput 输入的实数 PCM 采样点（大小必须等于 N）
     * @param magnitudes 输出的各频率点幅值数组（大小为 N / 2）
     */
    fun computeMagnitudes(realInput: FloatArray, magnitudes: FloatArray) {
        val real = FloatArray(n)
        val imag = FloatArray(n)

        // 1. 加窗处理与位反转重排
        for (i in 0 until n) {
            val revIndex = bitReverse[i]
            real[revIndex] = realInput[i] * window[i]
            imag[revIndex] = 0.0f
        }

        // 2. Cooley-Tukey 蝶形运算
        var size = 2
        while (size <= n) {
            val halfsize = size shr 1
            val tablestep = 2.0 * PI / size
            for (i in 0 until n step size) {
                for (j in i until i + halfsize) {
                    val k = j - i
                    val angle = -k * tablestep
                    val wReal = cos(angle).toFloat()
                    val wImag = sin(angle).toFloat()

                    val tr = wReal * real[j + halfsize] - wImag * imag[j + halfsize]
                    val ti = wReal * imag[j + halfsize] + wImag * real[j + halfsize]

                    real[j + halfsize] = real[j] - tr
                    imag[j + halfsize] = imag[j] - ti
                    real[j] += tr
                    imag[j] += ti
                }
            }
            size = size shl 1
        }

        // 3. 计算前半部分频率点的幅值 (|X[k]| = sqrt(Re^2 + Im^2))
        val halfN = n / 2
        val maxOut = if (magnitudes.size < halfN) magnitudes.size else halfN
        for (k in 0 until maxOut) {
            magnitudes[k] = sqrt(real[k] * real[k] + imag[k] * imag[k])
        }
    }
}
