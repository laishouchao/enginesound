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
 * 触觉引擎前台常驻服务 (高鲁棒性·全平台零闪退设计)
 *
 * 核心设计：
 * 1. 严格契合 Android 14 (API 34) 细分前台服务类型规则，杜绝因未授权 MediaProjection 盲目
 *    声明 FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION 导致的 SecurityException 闪退。
 * 2. 在待机与本地音乐模式下，使用 MEDIA_PLAYBACK 稳固挂载前台保活；获得录屏投射授权后动态
 *    升级为 MEDIA_PROJECTION。
 * 3. 持有 CPU WakeLock 防止后台休眠，安全捕获异常。
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
        // 取得有效 MediaProjection 句柄后，升级前台服务类型为 MEDIA_PROJECTION
        startForegroundNotification()
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
                    _debugLogs.tryEmit("⚠️ 未选择本地音频文件")
                }
            }
            AudioCaptureManager.AudioSource.GLOBAL_SYSTEM -> {
                mediaProjection?.let { projection ->
                    audioManager.startGlobal(projection)
                } ?: run {
                    _debugLogs.tryEmit("⚠️ 未获得系统全局音频捕获授权")
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
        updateNotification(false)
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EngineSound:HapticEngineWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24小时超时防护
            }
        } catch (e: Exception) {
            Log.w(TAG, "申请 WakeLock 提示: ${e.message}")
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
            manager?.createNotificationChannel(channel)
        }
    }

    /**
     * 安全启动前台通知 (完美契合 Android 14+ FGS 安全规范)
     */
    private fun startForegroundNotification() {
        val notification = buildNotification(isRunning.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) { // Android 14+
                val serviceType = if (mediaProjection != null) {
                    // 已具备有效 MediaProjection 实例时才声明 MEDIA_PROJECTION 类型
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    // 默认与本地播放模式下，使用标准的 MEDIA_PLAYBACK，杜绝 SecurityException
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                }
                startForeground(NOTIFICATION_ID, notification, serviceType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务通知安全捕获", e)
            try {
                // 若由于个别厂商 ROM 权限限制抛出异常，尝试以无类型兜底挂载
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (ignored: Exception) {}
        }
    }

    private fun updateNotification(running: Boolean) {
        try {
            val notification = buildNotification(running)
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "更新通知提示: ${e.message}")
        }
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
            .setContentTitle("触觉低音炮 (${if (running) "运行中" else "待机"})")
            .setContentText(if (running) "低频触觉引擎正在驱动马达..." else "引擎处于静止待机状态")
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
