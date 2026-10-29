package com.enginesound.haptics.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File

/**
 * 全局设备特征与通用硬件适配管理器
 *
 * 核心目标：
 * 1. 保证应用在小米、红米、三星、一加、OPPO、vivo、华为、荣耀、魅族、Google Pixel 等全品牌手机上零闪退、自适应运行。
 * 2. 智能探测机型是否为 Redmi K40G (ares)；若是，开启专享的天玑1200与AAC马达底层寄存器直驱。
 * 3. 若为其他品牌机型，自动适配高通 (qcom-haptics)、三星 (sec_vib) 或系统原生无级触觉引擎。
 * 4. 杜绝未 ROOT 手机因盲目调用 `su` 命令而发生崩溃或 ANR 假死。
 */
object DeviceProfileManager {

    private const val TAG = "DeviceProfileManager"

    data class DeviceProfile(
        val manufacturer: String,
        val model: String,
        val deviceCodename: String,
        val isRedmiK40G: Boolean,
        val isRooted: Boolean,
        val hasVibrator: Boolean,
        val hasAmplitudeControl: Boolean,
        val supportsRichHaptics: Boolean,
        val recommendedEngineDescription: String
    )

    private var cachedProfile: DeviceProfile? = null

    /**
     * 探测并返回当前设备的完整硬件画像
     */
    fun getProfile(context: Context): DeviceProfile {
        cachedProfile?.let { return it }

        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val codename = Build.DEVICE.orEmpty().lowercase()
        val product = Build.PRODUCT.orEmpty().lowercase()

        // 判定是否为红米 K40 游戏增强版 (开发代号 ares / aresin)
        val isK40G = codename == "ares" || codename == "aresin" ||
                product.contains("ares") ||
                model.contains("M2104K10AC", ignoreCase = true) ||
                model.contains("K40 Gaming", ignoreCase = true)

        val isRooted = checkRootSafe()

        // 探测系统振动器特征
        val vibrator = getSystemVibrator(context)
        val hasVibrator = vibrator?.hasVibrator() ?: false
        val hasAmplitude = vibrator?.hasAmplitudeControl() ?: false

        // 探测 Android 11+ / 12+ 富触觉基元支持度 (Haptic Primitives: Click, Thud, Low Tick)
        val supportsRichHaptics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && vibrator != null) {
            try {
                vibrator.areAllPrimitivesSupported(
                    VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_THUD,
                    VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                )
            } catch (ignored: Exception) {
                false
            }
        } else false

        val desc = when {
            isK40G && isRooted -> "Redmi K40G 专版特调 (天玑1200 + Sysfs 直驱)"
            isK40G && !isRooted -> "Redmi K40G (未ROOT，已启用原生无级触觉模式)"
            isRooted && hasAmplitude -> "$manufacturer 通用适配 (已ROOT，支持 Sysfs/原生双驱)"
            hasAmplitude -> "$manufacturer 通用适配 (支持无级线性振幅调节)"
            hasVibrator -> "$manufacturer 通用适配 (基础振动兼容模式)"
            else -> "未检测到物理振动马达"
        }

        val profile = DeviceProfile(
            manufacturer = manufacturer,
            model = model,
            deviceCodename = codename,
            isRedmiK40G = isK40G,
            isRooted = isRooted,
            hasVibrator = hasVibrator,
            hasAmplitudeControl = hasAmplitude,
            supportsRichHaptics = supportsRichHaptics,
            recommendedEngineDescription = desc
        )

        Log.i(TAG, "已生成设备硬件画像: $profile")
        cachedProfile = profile
        return profile
    }

    /**
     * 安全无死锁、无异常泄漏的 ROOT 环境检测
     */
    private fun checkRootSafe(): Boolean {
        // 1. 检查各常见系统目录下的 su 二进制文件
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su",
            "/apex/com.android.runtime/bin/su",
            "/data/adb/ksu/bin/su",   // KernelSU
            "/data/adb/ap/bin/su"     // APatch
        )

        for (path in suPaths) {
            try {
                if (File(path).exists()) {
                    return true
                }
            } catch (ignored: Exception) {}
        }

        // 2. 尝试执行极简 which 检查
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            false
        }
    }

    fun getSystemVibrator(context: Context): Vibrator? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 Vibrator 硬件服务失败", e)
            null
        }
    }
}
