package com.enginesound.haptics.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.enginesound.haptics.dsp.FastFourierTransform
import com.enginesound.haptics.dsp.LowFrequencyExtractor
import com.enginesound.haptics.motor.MotorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 全局系统音频捕获器 (Android 10+ AudioPlaybackCaptureConfiguration + AudioRecord)
 *
 * 核心功能：
 * 1. 抓取整机所有正在发声的 App 音频流 (Bilibili、网易云音乐、抖音、各类游戏等)。
 * 2. 实时抽取 PCM 16-bit 原始数据，无需在内部打开任何本地音乐文件。
 * 3. 实时进行 FFT 变换分析 0~200Hz 低频脉冲与包络。
 * 4. 模式 B (静音外放) 下通过 ROOT 或音频控制器压制扬声器硬件通道，仅保留马达振动共振。
 */
@RequiresApi(Build.VERSION_CODES.Q)
class GlobalAudioCapture(
    private val context: Context,
    private val motorController: MotorController,
    private val onEnergyUpdate: (Float) -> Unit,
    private val onCaptureStateChanged: (Boolean) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "GlobalAudioCapture"
        private const val SAMPLE_RATE = 44100
        private const val FFT_SIZE = 1024
    }

    private var captureJob: Job? = null
    @Volatile private var isCapturing = false

    private val fft = FastFourierTransform(FFT_SIZE)
    private val lowFreqExtractor = LowFrequencyExtractor(sampleRate = SAMPLE_RATE, fftSize = FFT_SIZE)

    var sensitivityGain: Float = 1.0f
    var lowFreqThreshold: Float = 0.15f

    private var audioRecord: AudioRecord? = null

    /**
     * 启动系统音频全局抓取
     * @param mediaProjection 用户授权录屏/音频捕获后返回的 MediaProjection 实例
     */
    fun startCapture(mediaProjection: MediaProjection, scope: CoroutineScope) {
        stopCapture()
        isCapturing = true
        onCaptureStateChanged(true)

        captureJob = scope.launch(Dispatchers.IO) {
            runCaptureLoop(mediaProjection)
        }
    }

    /**
     * 停止全局音频捕获并恢复系统外放
     */
    fun stopCapture() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null

        // 若处于模式 B 静音状态，还原扬声器输出
        restoreSpeakerOutput()

        motorController.stop()
        onCaptureStateChanged(false)
    }

    private fun runCaptureLoop(mediaProjection: MediaProjection) {
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBufSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufSize, FFT_SIZE * 4)

        try {
            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioRecord?.startRecording()
            Log.i(TAG, "全局系统音频抓取已成功启动")

            val audioBuffer = ShortArray(FFT_SIZE * 2) // 立体声帧
            val pcmAccumulator = FloatArray(FFT_SIZE)
            var pcmIndex = 0
            val magnitudes = FloatArray(FFT_SIZE / 2)

            var lastMode = motorController.currentWorkMode

            while (isCapturing && coroutineContext.isActive) {
                // 检测模式变动：若切入模式 B，通过 ROOT 压制物理扬声器
                if (motorController.currentWorkMode != lastMode) {
                    lastMode = motorController.currentWorkMode
                    if (lastMode == MotorController.WorkMode.MODE_B_VIBRATION_SIMULATION) {
                        muteSpeakerHardwareViaRoot()
                    } else {
                        restoreSpeakerOutput()
                    }
                }

                val readCount = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                if (readCount > 0) {
                    // 立体声转单声道 Float 并归一化到 [-1.0, 1.0]
                    for (i in 0 until readCount step 2) {
                        val left = audioBuffer[i].toFloat() / 32768.0f
                        val right = if (i + 1 < readCount) audioBuffer[i + 1].toFloat() / 32768.0f else left
                        val mono = (left + right) / 2.0f

                        pcmAccumulator[pcmIndex++] = mono

                        if (pcmIndex == FFT_SIZE) {
                            // 执行实数 FFT
                            fft.computeMagnitudes(pcmAccumulator, magnitudes)

                            // 提取 0 ~ 200Hz 低频 BASS 能量
                            val lowEnergy = lowFreqExtractor.extractLowFrequencyEnergy(
                                magnitudes,
                                sensitivityGain = sensitivityGain,
                                threshold = lowFreqThreshold
                            )

                            // 分发给马达调度引擎
                            motorController.processLowFreqEnergy(lowEnergy)
                            onEnergyUpdate(lowEnergy)

                            pcmIndex = 0
                        }
                    }
                } else {
                    Thread.sleep(5)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "全局音频录制捕获循环异常退出", e)
        } finally {
            stopCapture()
        }
    }

    /**
     * 模式 B 下利用 ROOT 权限压制硬件喇叭外放声音
     * (天玑 1200 / MTK 平台通过 tinymix 调整功放芯片开关，不干扰内部音频抓取)
     */
    private fun muteSpeakerHardwareViaRoot() {
        Thread {
            try {
                // 天玑1200常用 tinymix 开关及通用音量切断
                val commands = arrayOf(
                    "tinymix 'Speaker Channel 1 Switch' 0 2>/dev/null",
                    "tinymix 'Speaker Channel 2 Switch' 0 2>/dev/null",
                    "cmd audio set-volume-down 2>/dev/null"
                )
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", commands.joinToString(" ; ")))
                p.waitFor()
                Log.i(TAG, "已触发 ROOT 级硬件扬声器静音")
            } catch (e: Exception) {
                Log.w(TAG, "ROOT 硬件静音执行提示: ${e.message}")
            }
        }.start()
    }

    /**
     * 还原扬声器正常发声
     */
    private fun restoreSpeakerOutput() {
        Thread {
            try {
                val commands = arrayOf(
                    "tinymix 'Speaker Channel 1 Switch' 1 2>/dev/null",
                    "tinymix 'Speaker Channel 2 Switch' 1 2>/dev/null"
                )
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", commands.joinToString(" ; ")))
                p.waitFor()
                Log.i(TAG, "已恢复扬声器外放通道")
            } catch (ignored: Exception) {}
        }.start()
    }

    override fun close() {
        stopCapture()
    }
}
