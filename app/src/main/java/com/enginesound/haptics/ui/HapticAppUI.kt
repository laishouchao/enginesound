package com.enginesound.haptics.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enginesound.haptics.audio.AudioCaptureManager
import com.enginesound.haptics.device.DeviceProfileManager
import com.enginesound.haptics.motor.MotorController
import com.enginesound.haptics.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HapticAppUI(
    viewModel: HapticViewModel,
    onPickAudioFile: () -> Unit,
    onRequestMediaProjection: () -> Unit
) {
    val audioSource by viewModel.audioSource.collectAsState()
    val workMode by viewModel.workMode.collectAsState()
    val engineType by viewModel.engineType.collectAsState()
    val isRunning by viewModel.isRunning.collectAsState()
    val selectedFileName by viewModel.selectedFileName.collectAsState()
    val realtimeEnergy by viewModel.realtimeEnergy.collectAsState()
    val masterGain by viewModel.masterGain.collectAsState()
    val maxLimit by viewModel.maxLimit.collectAsState()
    val latencyOffset by viewModel.latencyOffsetMs.collectAsState()
    val lowFreqThreshold by viewModel.lowFreqThreshold.collectAsState()
    val showRiskDialog by viewModel.showRiskDialog.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val isRootAvailable by viewModel.isRootEngineAvailable.collectAsState()

    val profile = viewModel.deviceProfile

    // 硬件风险提示弹窗
    if (showRiskDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissRiskDialog() },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed) },
            title = { Text("⚠️ 硬件安全与寿命提醒", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "手机线性马达设计用于短促震动反馈。\n\n" +
                            "1. 长时间连续满负荷振动可能会引起音圈发热或机械弹片金属疲劳。\n" +
                            "2. 系统内已内置「最大振幅硬顶限制」与「持续高负荷过热限幅算法」，建议将最大振动限制保持在 85% 以下。\n" +
                            "3. 连续体验建议不要超过 30 分钟。",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissRiskDialog() },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberGreen)
                ) {
                    Text("我已知晓并同意", color = DarkBackground, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = DarkSurface
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "触觉低音炮",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (profile.isRedmiK40G) CyberGreen.copy(alpha = 0.2f) else CyberCyan.copy(alpha = 0.2f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (profile.isRedmiK40G) "K40G 特调版" else "${profile.manufacturer} 通用版",
                                color = if (profile.isRedmiK40G) CyberGreen else CyberCyan,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.openRiskDialog() }) {
                        Icon(Icons.Default.Info, contentDescription = "风险提醒", tint = CyberAmber)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBackground)
            )
        },
        containerColor = DarkBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 0. 设备画像识别卡片 (展示通用兼容与适配状态)
            DeviceProfileCard(profile = profile, isRootAvailable = isRootAvailable)

            // 1. 实时能量动态光柱与频谱表
            RealtimeEnergyCard(realtimeEnergy = realtimeEnergy, threshold = lowFreqThreshold, isRunning = isRunning)

            // 2. 主控启动 / 暂停大按钮
            StartPauseButton(
                isRunning = isRunning,
                onClick = { viewModel.toggleStartPause(onRequestMediaProjection) }
            )

            // 3. 音频来源切换
            AudioSourceSelectorCard(
                currentSource = audioSource,
                selectedFileName = selectedFileName,
                onSelectSource = { viewModel.setAudioSource(it) },
                onPickFile = onPickAudioFile
            )

            // 4. 工作模式切换 (模式 A 喇叭+鼓点 vs 模式 B 仅振动模拟)
            WorkModeSelectorCard(
                currentMode = workMode,
                onSelectMode = { viewModel.setWorkMode(it) }
            )

            // 5. 马达驱动方式切换 (ROOT Sysfs 直驱 vs 原生 Vibrator)
            EngineSelectorCard(
                currentEngine = engineType,
                isRootAvailable = isRootAvailable,
                onSelectEngine = { viewModel.setEngineType(it) }
            )

            // 6. 核心参数调节滑块区 (增益、保护门限、延迟校准、触发门槛)
            TuningSlidersCard(
                masterGain = masterGain,
                onGainChange = { viewModel.setMasterGain(it) },
                maxLimit = maxLimit,
                onMaxLimitChange = { viewModel.setMaxLimit(it) },
                latencyOffset = latencyOffset,
                onLatencyChange = { viewModel.setLatencyOffsetMs(it) },
                lowFreqThreshold = lowFreqThreshold,
                onThresholdChange = { viewModel.setLowFreqThreshold(it) }
            )

            // 7. 实时低频能量与事件调试控制台
            DebugLogConsoleCard(logs = logs)
        }
    }
}

@Composable
fun DeviceProfileCard(profile: DeviceProfileManager.DeviceProfile, isRootAvailable: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("已适配硬件", color = TextSecondary, fontSize = 12.sp)
                Text(
                    text = if (isRootAvailable) "ROOT 已就绪" else "免 ROOT 运行中",
                    color = if (isRootAvailable) CyberPurple else CyberGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${profile.manufacturer} ${profile.model}",
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = profile.recommendedEngineDescription,
                color = if (profile.isRedmiK40G) CyberGreen else CyberCyan,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun RealtimeEnergyCard(realtimeEnergy: Float, threshold: Float, isRunning: Boolean) {
    val animatedEnergy by animateFloatAsState(targetValue = realtimeEnergy, label = "energy")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("低频能量指示器 (0~200Hz)", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(
                    text = if (isRunning) "实时能量: ${(realtimeEnergy * 100).toInt()}%" else "待机",
                    color = if (realtimeEnergy > threshold) CyberGreen else TextSecondary,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 能量光柱槽
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(DarkSurfaceVariant)
            ) {
                // 门限参考线
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .offset(x = (threshold * 300).dp)
                        .background(CyberAmber)
                )

                // 动态光柱
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedEnergy.coerceIn(0.0f, 1.0f))
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(CyberCyan, CyberGreen, if (animatedEnergy > 0.85f) DangerRed else CyberGreenGlow)
                            )
                        )
                )
            }
        }
    }
}

@Composable
fun StartPauseButton(isRunning: Boolean, onClick: () -> Unit) {
    val bgColor by animateColorAsState(if (isRunning) DangerRed else CyberGreen, label = "btnColor")

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = bgColor)
    ) {
        Icon(
            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null,
            tint = DarkBackground
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isRunning) "暂停引擎 (马达即刻断电)" else "启动触觉引擎",
            color = DarkBackground,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
fun AudioSourceSelectorCard(
    currentSource: AudioCaptureManager.AudioSource,
    selectedFileName: String?,
    onSelectSource: (AudioCaptureManager.AudioSource) -> Unit,
    onPickFile: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("音频来源选择", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = currentSource == AudioCaptureManager.AudioSource.LOCAL_FILE,
                    onClick = { onSelectSource(AudioCaptureManager.AudioSource.LOCAL_FILE) },
                    label = { Text("本地音频文件") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen,
                        selectedLabelColor = DarkBackground
                    )
                )
                FilterChip(
                    selected = currentSource == AudioCaptureManager.AudioSource.GLOBAL_SYSTEM,
                    onClick = { onSelectSource(AudioCaptureManager.AudioSource.GLOBAL_SYSTEM) },
                    label = { Text("全局系统音频捕获") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberCyan,
                        selectedLabelColor = DarkBackground
                    )
                )
            }

            if (currentSource == AudioCaptureManager.AudioSource.LOCAL_FILE) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = selectedFileName ?: "未选择文件",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = onPickFile,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberGreen)
                    ) {
                        Text("选择 MP3/音频")
                    }
                }
            } else {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "支持捕获 B站、网易云、抖音、游戏等所有第三方应用发声，无需在应用内导入文件。",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
fun WorkModeSelectorCard(
    currentMode: MotorController.WorkMode,
    onSelectMode: (MotorController.WorkMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("工作模式选择", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(10.dp))

            // 模式 A 选项
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentMode == MotorController.WorkMode.MODE_A_SPEAKER_AND_BEAT,
                    onClick = { onSelectMode(MotorController.WorkMode.MODE_A_SPEAKER_AND_BEAT) },
                    colors = RadioButtonDefaults.colors(selectedColor = CyberGreen)
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("模式 A（默认）：喇叭正常播放 + 鼓点触觉振动", color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("外放正常出声，马达在低频瞬态重音打击时输出清脆触觉脉冲。", color = TextSecondary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 模式 B 选项
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = currentMode == MotorController.WorkMode.MODE_B_VIBRATION_SIMULATION,
                    onClick = { onSelectMode(MotorController.WorkMode.MODE_B_VIBRATION_SIMULATION) },
                    colors = RadioButtonDefaults.colors(selectedColor = CyberCyan)
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text("模式 B：【仅振动模拟】静音喇叭，纯物理共振", color = CyberCyan, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text("喇叭静音不出声！将低频分量连续平滑映射到马达振幅，用整机共振模拟超重低音音响。", color = TextSecondary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun EngineSelectorCard(
    currentEngine: MotorController.EngineType,
    isRootAvailable: Boolean,
    onSelectEngine: (MotorController.EngineType) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("马达底层驱动方式", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isRootAvailable) CyberPurple.copy(alpha = 0.2f) else DarkSurfaceVariant)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (isRootAvailable) "ROOT 高权限模式" else "通用免 ROOT 模式",
                        color = if (isRootAvailable) CyberPurple else TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = currentEngine == MotorController.EngineType.NATIVE_VIBRATOR,
                    onClick = { onSelectEngine(MotorController.EngineType.NATIVE_VIBRATOR) },
                    label = { Text("通用原生 Vibrator (全机型)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberGreen,
                        selectedLabelColor = DarkBackground
                    )
                )

                FilterChip(
                    selected = currentEngine == MotorController.EngineType.ROOT_SYSFS,
                    onClick = { onSelectEngine(MotorController.EngineType.ROOT_SYSFS) },
                    enabled = isRootAvailable,
                    label = { Text("ROOT Sysfs 直驱 (<1ms)") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = CyberPurple,
                        selectedLabelColor = Color.White,
                        disabledContainerColor = DarkSurfaceVariant,
                        disabledLabelColor = TextSecondary.copy(alpha = 0.5f)
                    )
                )
            }

            if (!isRootAvailable) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "提示：本机未取得 ROOT 权限或无直接写节点，已自动使用稳定通用的原生 Vibrator 引擎。",
                    color = TextSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
fun TuningSlidersCard(
    masterGain: Float,
    onGainChange: (Float) -> Unit,
    maxLimit: Float,
    onMaxLimitChange: (Float) -> Unit,
    latencyOffset: Int,
    onLatencyChange: (Int) -> Unit,
    lowFreqThreshold: Float,
    onThresholdChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("核心参数校准与安全限制", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            // 1. 震动增益
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("震动总增益强度", color = TextSecondary, fontSize = 13.sp)
                    Text("${(masterGain * 100).toInt()}%", color = CyberGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = masterGain,
                    onValueChange = onGainChange,
                    valueRange = 0.1f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = CyberGreen, activeTrackColor = CyberGreen)
                )
            }

            // 2. 最大强度限制保护
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("最大振幅保护上限 (防止损坏线圈)", color = TextSecondary, fontSize = 13.sp)
                    Text("${(maxLimit * 100).toInt()}%", color = CyberAmber, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = maxLimit,
                    onValueChange = onMaxLimitChange,
                    valueRange = 0.3f..1.0f,
                    colors = SliderDefaults.colors(thumbColor = CyberAmber, activeTrackColor = CyberAmber)
                )
            }

            // 3. 时间延迟偏移校准
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("马达时间偏移校准 (解决音振不同步)", color = TextSecondary, fontSize = 13.sp)
                    Text("${latencyOffset} ms", color = CyberCyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = latencyOffset.toFloat(),
                    onValueChange = { onLatencyChange(it.toInt()) },
                    valueRange = -150f..250f,
                    colors = SliderDefaults.colors(thumbColor = CyberCyan, activeTrackColor = CyberCyan)
                )
            }

            // 4. 低频触发门限
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("低频触发门槛 (过滤微弱底噪)", color = TextSecondary, fontSize = 13.sp)
                    Text("${(lowFreqThreshold * 100).toInt()}%", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Slider(
                    value = lowFreqThreshold,
                    onValueChange = onThresholdChange,
                    valueRange = 0.05f..0.45f,
                    colors = SliderDefaults.colors(thumbColor = TextPrimary, activeTrackColor = TextPrimary)
                )
            }
        }
    }
}

@Composable
fun DebugLogConsoleCard(logs: List<String>) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B0E14)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("实时低频能量与内核事件控制台", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(CyberGreen)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true
            ) {
                items(logs.reversed()) { logLine ->
                    Text(
                        text = logLine,
                        color = if (logLine.contains("⚠️")) DangerRed else Color(0xFF81C784),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
