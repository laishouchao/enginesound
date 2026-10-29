package com.enginesound.haptics.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.enginesound.haptics.audio.AudioCaptureManager
import com.enginesound.haptics.device.DeviceProfileManager
import com.enginesound.haptics.motor.MotorController
import com.enginesound.haptics.service.HapticEngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 触觉引擎状态管理 ViewModel (全机型通用适配感知)
 */
class HapticViewModel(application: Application) : AndroidViewModel(application) {

    // 绑定的常驻后台服务引用
    private var boundService: HapticEngineService? = null

    // 设备画像
    val deviceProfile: DeviceProfileManager.DeviceProfile = DeviceProfileManager.getProfile(application)

    // 音频来源
    private val _audioSource = MutableStateFlow(AudioCaptureManager.AudioSource.LOCAL_FILE)
    val audioSource: StateFlow<AudioCaptureManager.AudioSource> = _audioSource.asStateFlow()

    // 工作模式 (模式 A: 喇叭+鼓点脉冲 / 模式 B: 静音喇叭+连续物理模拟)
    private val _workMode = MutableStateFlow(MotorController.WorkMode.MODE_A_SPEAKER_AND_BEAT)
    val workMode: StateFlow<MotorController.WorkMode> = _workMode.asStateFlow()

    // 马达驱动方式 (自适应：已 ROOT 且支持节点则开启 ROOT_SYSFS，否则默认 NATIVE_VIBRATOR)
    private val _engineType = MutableStateFlow(
        if (deviceProfile.isRedmiK40G && deviceProfile.isRooted) MotorController.EngineType.ROOT_SYSFS
        else MotorController.EngineType.NATIVE_VIBRATOR
    )
    val engineType: StateFlow<MotorController.EngineType> = _engineType.asStateFlow()

    private val _isRootEngineAvailable = MutableStateFlow(deviceProfile.isRooted)
    val isRootEngineAvailable: StateFlow<Boolean> = _isRootEngineAvailable.asStateFlow()

    // 运行状态
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    // 本地音频文件名称与 Uri
    private val _selectedFileName = MutableStateFlow<String?>("未选择文件")
    val selectedFileName: StateFlow<String?> = _selectedFileName.asStateFlow()
    var selectedFileUri: Uri? = null
        private set

    // 实时低频能量值 (0.0 ~ 1.0)
    private val _realtimeEnergy = MutableStateFlow(0.0f)
    val realtimeEnergy: StateFlow<Float> = _realtimeEnergy.asStateFlow()

    // 调节参数
    private val _masterGain = MutableStateFlow(1.0f)
    val masterGain: StateFlow<Float> = _masterGain.asStateFlow()

    private val _maxLimit = MutableStateFlow(0.85f)
    val maxLimit: StateFlow<Float> = _maxLimit.asStateFlow()

    private val _latencyOffsetMs = MutableStateFlow(0)
    val latencyOffsetMs: StateFlow<Int> = _latencyOffsetMs.asStateFlow()

    private val _lowFreqThreshold = MutableStateFlow(0.15f)
    val lowFreqThreshold: StateFlow<Float> = _lowFreqThreshold.asStateFlow()

    // 硬件风险提示弹窗
    private val _showRiskDialog = MutableStateFlow(true)
    val showRiskDialog: StateFlow<Boolean> = _showRiskDialog.asStateFlow()

    // 调试日志输出（保留最近 30 条）
    private val _logs = MutableStateFlow<List<String>>(listOf("系统就绪，已生成硬件适配画像"))
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun bindService(service: HapticEngineService) {
        boundService = service

        // 从 Service 同步自适应探测后的引擎模式
        _engineType.value = service.motorController.currentEngineType
        _isRootEngineAvailable.value = service.motorController.isRootEngineReady

        // 同步配置到服务
        service.motorController.currentWorkMode = _workMode.value
        service.motorController.masterGain = _masterGain.value
        service.motorController.maxIntensityLimit = _maxLimit.value
        service.motorController.latencyOffsetMs = _latencyOffsetMs.value
        service.audioManager.currentSource = _audioSource.value
        service.audioManager.lowFreqThreshold = _lowFreqThreshold.value

        // 监听服务的实时能量和日志
        viewModelScope.launch {
            service.realtimeEnergy.collect { energy ->
                _realtimeEnergy.value = energy
            }
        }
        viewModelScope.launch {
            service.isRunning.collect { running ->
                _isRunning.value = running
            }
        }
        viewModelScope.launch {
            service.debugLogs.collect { log ->
                appendLog(log)
            }
        }
    }

    fun unbindService() {
        boundService = null
    }

    fun setAudioSource(source: AudioCaptureManager.AudioSource) {
        _audioSource.value = source
        boundService?.audioManager?.currentSource = source
        appendLog("音频来源切换为: ${if (source == AudioCaptureManager.AudioSource.LOCAL_FILE) "本地音频文件" else "全局系统音频捕获"}")
    }

    fun setWorkMode(mode: MotorController.WorkMode) {
        _workMode.value = mode
        boundService?.motorController?.currentWorkMode = mode
        appendLog("工作模式切换为: ${if (mode == MotorController.WorkMode.MODE_A_SPEAKER_AND_BEAT) "模式 A (扬声器+鼓点)" else "模式 B (仅振动模拟/静音喇叭)"}")
    }

    fun setEngineType(type: MotorController.EngineType) {
        if (type == MotorController.EngineType.ROOT_SYSFS && !_isRootEngineAvailable.value) {
            appendLog("⚠️ 当前设备未获取 ROOT 权限或无匹配驱动节点，无法切换到 Sysfs 直驱")
            return
        }
        _engineType.value = type
        boundService?.motorController?.currentEngineType = type
        appendLog("马达引擎切换为: ${if (type == MotorController.EngineType.ROOT_SYSFS) "ROOT Sysfs 硬件直驱" else "通用原生 Vibrator 引擎"}")
    }

    fun setMasterGain(gain: Float) {
        _masterGain.value = gain
        boundService?.motorController?.masterGain = gain
    }

    fun setMaxLimit(limit: Float) {
        _maxLimit.value = limit
        boundService?.motorController?.maxIntensityLimit = limit
    }

    fun setLatencyOffsetMs(offset: Int) {
        _latencyOffsetMs.value = offset
        boundService?.motorController?.latencyOffsetMs = offset
    }

    fun setLowFreqThreshold(threshold: Float) {
        _lowFreqThreshold.value = threshold
        boundService?.audioManager?.lowFreqThreshold = threshold
    }

    fun setSelectedFile(uri: Uri, name: String) {
        selectedFileUri = uri
        _selectedFileName.value = name
        boundService?.setLocalAudioUri(uri)
        appendLog("已载入本地音频: $name")
    }

    fun toggleStartPause(onRequestMediaProjection: () -> Unit) {
        val service = boundService ?: run {
            appendLog("⚠️ 后台服务正在连接，请稍候...")
            return
        }

        if (_isRunning.value) {
            service.pauseOrStop()
            appendLog("已暂停运行，马达即刻断电")
        } else {
            if (_audioSource.value == AudioCaptureManager.AudioSource.GLOBAL_SYSTEM) {
                onRequestMediaProjection()
            } else {
                if (selectedFileUri == null) {
                    appendLog("⚠️ 请先点击【选择本地音频文件】！")
                    return
                }
                service.startOrResume()
                appendLog("开始播放本地音频并驱动触觉引擎")
            }
        }
    }

    fun dismissRiskDialog() {
        _showRiskDialog.value = false
    }

    fun openRiskDialog() {
        _showRiskDialog.value = true
    }

    private fun appendLog(msg: String) {
        val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$time] $msg"
        val current = _logs.value.toMutableList()
        if (current.size >= 40) current.removeAt(0)
        current.add(formatted)
        _logs.value = current
    }
}
