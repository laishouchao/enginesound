package com.enginesound.haptics.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.enginesound.haptics.dsp.FastFourierTransform
import com.enginesound.haptics.dsp.LowFrequencyExtractor
import com.enginesound.haptics.motor.MotorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * 本地音频文件解码与播放器 (MediaExtractor + MediaCodec + AudioTrack + FFT)
 *
 * 核心特性：
 * 1. 软解/硬解任意格式本地音频 (MP3, AAC, FLAC, WAV, OGG 等) 输出 16-bit PCM。
 * 2. 模式 A (常规播放)：PCM 流写入 AudioTrack 驱动扬声器正常出声，同时提取低频触发马达。
 * 3. 模式 B (纯振动模拟)：不向扬声器输出声音（静音 AudioTrack），仅根据 PCM 流计算低频能量
 *    并驱动马达连续物理共振，按实际播放速度精准时钟推进。
 */
class LocalAudioPlayer(
    private val context: Context,
    private val motorController: MotorController,
    private val onEnergyUpdate: (Float) -> Unit,
    private val onPlaybackStateChanged: (Boolean) -> Unit
) : AutoCloseable {

    companion object {
        private const val TAG = "LocalAudioPlayer"
        private const val FFT_SIZE = 1024
    }

    private var playerJob: Job? = null
    @Volatile private var isPlaying = false
    @Volatile private var isPaused = false

    private val fft = FastFourierTransform(FFT_SIZE)
    private var lowFreqExtractor = LowFrequencyExtractor(sampleRate = 44100, fftSize = FFT_SIZE)

    var sensitivityGain: Float = 1.0f
    var lowFreqThreshold: Float = 0.15f

    /**
     * 开始或恢复播放指定 Uri 音频
     */
    fun start(uri: Uri, scope: CoroutineScope) {
        stop()
        isPlaying = true
        isPaused = false
        onPlaybackStateChanged(true)

        playerJob = scope.launch(Dispatchers.IO) {
            runDecoderLoop(uri)
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        isPaused = true
        motorController.stop()
        onPlaybackStateChanged(false)
    }

    /**
     * 恢复播放
     */
    fun resume() {
        isPaused = false
        onPlaybackStateChanged(true)
    }

    /**
     * 停止播放并释放解码资源
     */
    fun stop() {
        isPlaying = false
        isPaused = false
        playerJob?.cancel()
        playerJob = null
        motorController.stop()
        onPlaybackStateChanged(false)
    }

    private fun runDecoderLoop(uri: Uri) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var audioTrack: AudioTrack? = null

        try {
            extractor.setDataSource(context, uri, null)
            val audioTrackIndex = findAudioTrack(extractor)
            if (audioTrackIndex < 0) {
                Log.e(TAG, "未在音频文件中找到音频轨道")
                return
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg"
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else 44100
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            } else 2

            lowFreqExtractor = LowFrequencyExtractor(sampleRate = sampleRate, fftSize = FFT_SIZE)

            val channelConfig = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

            // 创建 AudioTrack 用于外放扬声器发声
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build()
                )
                .setBufferSizeInBytes(max(minBufferSize, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            audioTrack.play()

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEOS = false

            // 采样样本缓冲区（用于 FFT 分析）
            val pcmAccumulator = FloatArray(FFT_SIZE)
            var pcmIndex = 0
            val magnitudes = FloatArray(FFT_SIZE / 2)

            val frameDurationUs = (FFT_SIZE.toDouble() / sampleRate * 1_000_000).toLong()
            var lastClockTime = System.nanoTime()

            while (isPlaying && !Thread.currentThread().isInterrupted) {
                if (isPaused) {
                    Thread.sleep(20)
                    lastClockTime = System.nanoTime()
                    continue
                }

                // 1. 将待解码数据送入 MediaCodec 输入缓冲
                if (!isEOS) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_SECTION)
                                isEOS = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // 2. 从 MediaCodec 接收解码完毕的 16-bit PCM 数据
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outIndex)
                    if (outputBuffer != null && info.size > 0) {
                        outputBuffer.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        val chunkBytes = ByteArray(info.size)
                        outputBuffer.get(chunkBytes)

                        // 模式判定：模式 A 扬声器正常发声；模式 B 扬声器完全静音不输出
                        if (motorController.currentWorkMode == MotorController.WorkMode.MODE_A_SPEAKER_AND_BEAT) {
                            audioTrack.write(chunkBytes, 0, chunkBytes.size)
                        } else {
                            // 模式 B：扬声器静音，按真实时钟平滑休眠推进，保证振动与音乐节拍一致
                            val now = System.nanoTime()
                            val elapsedUs = (now - lastClockTime) / 1000
                            val targetChunkUs = (chunkBytes.size.toDouble() / (sampleRate * channelCount * 2) * 1_000_000).toLong()
                            if (targetChunkUs > elapsedUs) {
                                Thread.sleep((targetChunkUs - elapsedUs) / 1000)
                            }
                            lastClockTime = System.nanoTime()
                        }

                        // 3. 将 16-bit PCM 数据转为单声道 Float 进行 FFT 实时频谱分析
                        val shortBuf = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        while (shortBuf.hasRemaining()) {
                            val sample = if (channelCount == 2) {
                                val left = shortBuf.get().toFloat()
                                val right = if (shortBuf.hasRemaining()) shortBuf.get().toFloat() else left
                                (left + right) / 2.0f / 32768.0f
                            } else {
                                shortBuf.get().toFloat() / 32768.0f
                            }

                            pcmAccumulator[pcmIndex++] = sample
                            if (pcmIndex == FFT_SIZE) {
                                // 蓄满一帧 1024 样本点，进行 FFT 计算
                                fft.computeMagnitudes(pcmAccumulator, magnitudes)
                                val lowEnergy = lowFreqExtractor.extractLowFrequencyEnergy(
                                    magnitudes,
                                    sensitivityGain = sensitivityGain,
                                    threshold = lowFreqThreshold
                                )

                                // 驱动马达触觉
                                motorController.processLowFreqEnergy(lowEnergy)
                                onEnergyUpdate(lowEnergy)

                                pcmIndex = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                }

                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    Log.i(TAG, "本地音频已播放完毕")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "本地音频解码播放发生异常", e)
        } finally {
            try {
                audioTrack?.stop()
                audioTrack?.release()
                codec?.stop()
                codec?.release()
                extractor.release()
            } catch (ignored: Exception) {}
            motorController.stop()
            onPlaybackStateChanged(false)
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                return i
            }
        }
        return -1
    }

    override fun close() {
        stop()
    }
}
