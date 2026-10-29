package com.enginesound.haptics.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
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
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 全局系统音频捕获器 (Android 10+ 通用兼容 + K40G 专属硬件静音优化)
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
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var originalVolume: Int = -1

    /**
     * 启动系统音频全局抓取
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

        // 还原扬声器输出
        restoreSpeakerOutput()

        motorController.stop()
        onCaptureStateChanged(false)
    }

    private fun runCaptureLoop(mediaProjection: MediaProjection) {
        try {
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

            audioRecord = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化未就绪，系统不支持当前采样格式")
                stopCapture()
                return
            }

            audioRecord?.startRecording()
            Log.i(TAG, "全局系统音频抓取循环成功启动")

            val audioBuffer = ShortArray(FFT_SIZE * 2) // 立体声帧
            val pcmAccumulator = FloatArray(FFT_SIZE)
            var pcmIndex = 0
            val magnitudes = FloatArray(FFT_SIZE / 2)

            var lastMode = motorController.currentWorkMode
            if (lastMode == MotorController.WorkMode.MODE_B_VIBRATION_SIMULATION) {
                applySpeakerMute()
            }

            while (isCapturing) {
                // 检测模式变动：若切入模式 B，压制物理扬声器
                if (motorController.currentWorkMode != lastMode) {
                    lastMode = motorController.currentWorkMode
                    if (lastMode == MotorController.WorkMode.MODE_B_VIBRATION_SIMULATION) {
                        applySpeakerMute()
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
            Log.e(TAG, "全局音频捕获循环异常退出", e)
        } finally {
            stopCapture()
        }
    }

    /**
     * 模式 B 静音策略：
     * - 若具备 ROOT 且为 K40G 等支持 tinymix 的平台，优先切断功放通道
     * - 针对通用/非 ROOT 手机，通过系统音频管理器执行流静音，实现全平台统一效果
     */
    private fun applySpeakerMute() {
        if (motorController.deviceProfile.isRooted) {
            Thread {
                try {
                    val commands = arrayOf(
                        "tinymix 'Speaker Channel 1 Switch' 0 2>/dev/null",
                        "tinymix 'Speaker Channel 2 Switch' 0 2>/dev/null"
                    )
                    Runtime.getRuntime().exec(arrayOf("su", "-c", commands.joinToString(" ; "))).waitFor()
                    Log.i(TAG, "已执行 ROOT 功放静音")
                } catch (ignored: Exception) {}
            }.start()
        } else {
            try {
                audioManager?.let { am ->
                    if (originalVolume == -1) {
                        originalVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    Log.i(TAG, "已执行通用 AudioManager 媒体静音")
                }
            } catch (e: Exception) {
                Log.w(TAG, "通用媒体静音提示: ${e.message}")
            }
        }
    }

    /**
     * 还原扬声器正常发声
     */
    private fun restoreSpeakerOutput() {
        if (motorController.deviceProfile.isRooted) {
            Thread {
                try {
                    val commands = arrayOf(
                        "tinymix 'Speaker Channel 1 Switch' 1 2>/dev/null",
                        "tinymix 'Speaker Channel 2 Switch' 1 2>/dev/null"
                    )
                    Runtime.getRuntime().exec(arrayOf("su", "-c", commands.joinToString(" ; "))).waitFor()
                    Log.i(TAG, "已恢复 ROOT 功放通道")
                } catch (ignored: Exception) {}
            }.start()
        }

        if (originalVolume != -1) {
            try {
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, originalVolume, 0)
                originalVolume = -1
                Log.i(TAG, "已恢复通用媒体音量")
            } catch (e: Exception) {
                Log.w(TAG, "恢复媒体音量提示: ${e.message}")
            }
        }
    }

    override fun close() {
        stopCapture()
    }
}
