package com.enginesound.haptics.audio

import android.content.Context
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Build
import com.enginesound.haptics.motor.MotorController
import kotlinx.coroutines.CoroutineScope

/**
 * 音频捕获与处理总管理器
 * 统一调度【本地音频文件播放】与【全局系统声音捕获】两种数据来源
 */
class AudioCaptureManager(
    private val context: Context,
    private val motorController: MotorController,
    private val coroutineScope: CoroutineScope,
    private val onEnergyUpdate: (Float) -> Unit,
    private val onStateChanged: (Boolean) -> Unit
) : AutoCloseable {

    enum class AudioSource {
        LOCAL_FILE,
        GLOBAL_SYSTEM
    }

    var currentSource: AudioSource = AudioSource.LOCAL_FILE
        set(value) {
            if (field != value) {
                stop()
                field = value
            }
        }

    val localPlayer = LocalAudioPlayer(
        context = context,
        motorController = motorController,
        onEnergyUpdate = onEnergyUpdate,
        onPlaybackStateChanged = onStateChanged
    )

    val globalCapture: GlobalAudioCapture? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        GlobalAudioCapture(
            context = context,
            motorController = motorController,
            onEnergyUpdate = onEnergyUpdate,
            onCaptureStateChanged = onStateChanged
        )
    } else null

    var sensitivityGain: Float = 1.0f
        set(value) {
            field = value
            localPlayer.sensitivityGain = value
            globalCapture?.sensitivityGain = value
        }

    var lowFreqThreshold: Float = 0.15f
        set(value) {
            field = value
            localPlayer.lowFreqThreshold = value
            globalCapture?.lowFreqThreshold = value
        }

    /**
     * 启动本地文件播放
     */
    fun startLocal(uri: Uri) {
        currentSource = AudioSource.LOCAL_FILE
        localPlayer.start(uri, coroutineScope)
    }

    /**
     * 启动全局捕获
     */
    fun startGlobal(mediaProjection: MediaProjection) {
        currentSource = AudioSource.GLOBAL_SYSTEM
        globalCapture?.startCapture(mediaProjection, coroutineScope)
    }

    /**
     * 暂停当前播放或捕获
     */
    fun pause() {
        when (currentSource) {
            AudioSource.LOCAL_FILE -> localPlayer.pause()
            AudioSource.GLOBAL_SYSTEM -> globalCapture?.stopCapture()
        }
        motorController.stop()
    }

    /**
     * 恢复当前播放
     */
    fun resume(localUri: Uri?, mediaProjection: MediaProjection?) {
        when (currentSource) {
            AudioSource.LOCAL_FILE -> {
                if (localUri != null) {
                    localPlayer.start(localUri, coroutineScope)
                }
            }
            AudioSource.GLOBAL_SYSTEM -> {
                if (mediaProjection != null) {
                    globalCapture?.startCapture(mediaProjection, coroutineScope)
                }
            }
        }
    }

    /**
     * 彻底停止所有音频输入与马达
     */
    fun stop() {
        localPlayer.stop()
        globalCapture?.stopCapture()
        motorController.stop()
    }

    override fun close() {
        stop()
        localPlayer.close()
        globalCapture?.close()
    }
}
