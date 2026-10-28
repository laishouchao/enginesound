package com.enginesound.haptics.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.enginesound.haptics.MainActivity
import com.enginesound.haptics.R
import com.enginesound.haptics.audio.AudioCaptureManager
import com.enginesound.haptics.motor.MotorController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 触觉引擎前台常驻服务
 *
 * 核心目的：
 * 1. 挂载系统前台服务 (Foreground Service)，持有 CPU WakeLock，防止 MIUI/HyperOS 后台激进省电查杀。
 * 2. 托管 MediaProjection 与 AudioRecord 生命周期，保证切换至 B站、网易云音乐等其他 App 时后台持续生效。
 * 3. 集中持有 MotorController 与 AudioCaptureManager，为 Activity 提供稳定的 Binder 控制桥梁。
 */
class HapticEngineService : Service() {

    companion object {
        private const val TAG = "HapticEngineService"
        private const val CHANNEL_ID = "haptic_engine_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.enginesound.haptics.ACTION_START"
        const val ACTION_STOP = "com.enginesound.haptics.ACTION_STOP"
    }

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var wakeLock: PowerManager.WakeLock? = null

    lateinit var motorController: MotorController
        private set
    lateinit var audioManager: AudioCaptureManager
        private set

    // 状态流供 UI 实时响应观察
    private val _realtimeEnergy = MutableStateFlow(0.0f)
    val realtimeEnergy = _realtimeEnergy.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _debugLogs = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val debugLogs = _debugLogs.asSharedFlow()

    private var mediaProjection: MediaProjection? = null
    private var localAudioUri: Uri? = null

    inner class LocalBinder : Binder() {
        fun getService(): HapticEngineService = this@HapticEngineService
    }

    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "HapticEngineService 创建中...")

        acquireWakeLock()

        motorController = MotorController(this) { logMessage ->
            _debugLogs.tryEmit(logMessage)
        }

        audioManager = AudioCaptureManager(
            context = this,
            motorController = motorController,
            coroutineScope = serviceScope,
            onEnergyUpdate = { energy ->
                _realtimeEnergy.value = energy
            },
            onStateChanged = { running ->
                _isRunning.value = running
                updateNotification(running)
            }
        )

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                pauseOrStop()
            }
            else -> {
                startForegroundNotification()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun setMediaProjection(projection: MediaProjection) {
        this.mediaProjection = projection
    }

    fun setLocalAudioUri(uri: Uri) {
        this.localAudioUri = uri
    }

    /**
     * 开始/恢复运行
     */
    fun startOrResume() {
        startForegroundNotification()
        when (audioManager.currentSource) {
            AudioCaptureManager.AudioSource.LOCAL_FILE -> {
                localAudioUri?.let { uri ->
                    audioManager.startLocal(uri)
                } ?: run {
                    _debugLogs.tryEmit("未选择本地音频文件")
                }
            }
            AudioCaptureManager.AudioSource.GLOBAL_SYSTEM -> {
                mediaProjection?.let { projection ->
                    audioManager.startGlobal(projection)
                } ?: run {
                    _debugLogs.tryEmit("未获得系统录音/录屏授权")
                }
            }
        }
    }

    /**
     * 暂停或停止运行
     */
    fun pauseOrStop() {
        audioManager.pause()
        motorController.stop()
        _realtimeEnergy.value = 0.0f
        _isRunning.value = false
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "EngineSound:HapticEngineWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(24 * 60 * 60 * 1000L) // 24小时最长超时
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (ignored: Exception) {}
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "触觉引擎后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保证低频音频分析与马达振动在后台平稳运行"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = buildNotification(isRunning.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            startForeground(NOTIFICATION_ID, notification, serviceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(running: Boolean) {
        val notification = buildNotification(running)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(running: Boolean): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingOpenApp = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, HapticEngineService::class.java).apply {
            action = ACTION_STOP
        }
        val pendingStop = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("触觉低音炮 (${if (running) "运行中" else "已暂停"})")
            .setContentText(if (running) "低频触觉引擎正在驱动 X 轴线性马达..." else "引擎处于静止待机状态")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingOpenApp)
            .addAction(
                android.R.drawable.ic_media_pause,
                "暂停/停止",
                pendingStop
            )
            .setOngoing(running)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "HapticEngineService 销毁")
        pauseOrStop()
        releaseWakeLock()
        audioManager.close()
        motorController.close()
        serviceScope.cancel()
    }
}
