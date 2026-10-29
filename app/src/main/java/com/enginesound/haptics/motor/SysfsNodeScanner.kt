package com.enginesound.haptics.motor

import android.util.Log
import com.enginesound.haptics.device.DeviceProfileManager
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * 通用与专属硬件马达 Sysfs 节点扫描器
 *
 * 覆盖机型：
 * 1. 深度适配：红米 K40 游戏增强版 (ares / 天玑1200 / AAC 线性马达)
 * 2. 通用适配：高通骁龙平台 (Xiaomi, OnePlus, Samsung, Sony, Meizu 等 qcom-haptics)
 * 3. 通用适配：三星平台 (sec_vib)、联发科通用平台 (m_vibrator / timed_output)
 * 4. 防御机制：在未 ROOT 手机上安全降级，绝不执行无效 su 指令造成 ANR 或闪退。
 */
object SysfsNodeScanner {

    private const val TAG = "SysfsNodeScanner"

    val CANDIDATE_DIRECTORIES = listOf(
        // 1. K40G / MIUI 标准 leds 子系统马达节点 (优先匹配 K40G)
        "/sys/class/leds/vibrator",
        // 2. MTK 天玑平台 I2C AW8697 / AAC 芯片节点
        "/sys/devices/platform/soc/11016000.i2c/i2c-5/5-005a/leds/vibrator",
        "/sys/devices/platform/soc/11007000.i2c/i2c-0/0-005a/leds/vibrator",
        "/sys/devices/platform/1000b000.pwrap/1000b000.pwrap:main_pmic/mt6359-vibrator/timed_output/vibrator",
        // 3. 高通骁龙平台通用触觉驱动节点 (Qualcomm QPNP / SPMI Haptics)
        "/sys/class/qcom-haptics",
        "/sys/devices/platform/soc/c440000.qcom,spmi/spmi-0/spmi0-02/c440000.qcom,spmi:qcom,pm8150b@2:qcom,haptics@c000/leds/vibrator",
        "/sys/devices/platform/soc/c440000.qcom,spmi/spmi-0/spmi0-04/c440000.qcom,spmi:qcom,pm8350b@4:qcom,haptics@c000/leds/vibrator",
        "/sys/devices/platform/soc/c440000.qcom,spmi/spmi-0/spmi0-01/c440000.qcom,spmi:qcom,pm8550b@1:qcom,haptics@c000/leds/vibrator",
        // 4. 三星平台专有振动节点
        "/sys/class/sec_vib/vibrator",
        "/sys/devices/virtual/timed_output/vibrator",
        // 5. 传统 Linux timed_output / misc vibrator 通用节点
        "/sys/class/timed_output/vibrator",
        "/sys/class/misc/m_vibrator",
        "/sys/bus/i2c/drivers/aw8697_haptic"
    )

    data class DiscoveredNodes(
        val baseDir: String,
        val brightnessNode: String?, // 振幅控制 (0~255 或 0~100)
        val durationNode: String?,   // 持续时间控制 (ms)
        val activateNode: String?,   // 启动/停止开关 (1=开, 0=关)
        val enableNode: String?      // 传统 timed_output enable 节点 (写入 ms)
    )

    /**
     * 自动检测当前设备上可用的 Sysfs 节点
     * @param suShellAvailable 是否有 ROOT 权限
     */
    fun detectNodes(suShellAvailable: Boolean): DiscoveredNodes? {
        Log.i(TAG, "开始探测硬件马达 Sysfs 节点 (ROOT权限: $suShellAvailable)...")

        // 1. 先检查本地文件系统是否有直接可读路径
        for (dirPath in CANDIDATE_DIRECTORIES) {
            val dir = File(dirPath)
            val exists = try {
                if (dir.exists()) true else if (suShellAvailable) checkExistsViaSu(dirPath) else false
            } catch (e: Exception) {
                false
            }

            if (exists) {
                Log.i(TAG, "定位到匹配的马达节点目录: $dirPath")
                val brightness = resolveNode(dirPath, listOf("brightness", "level", "gain", "vmax"), suShellAvailable)
                val duration = resolveNode(dirPath, listOf("duration", "time"), suShellAvailable)
                val activate = resolveNode(dirPath, listOf("activate", "state", "play"), suShellAvailable)
                val enable = resolveNode(dirPath, listOf("enable"), suShellAvailable)

                return DiscoveredNodes(
                    baseDir = dirPath,
                    brightnessNode = brightness,
                    durationNode = duration,
                    activateNode = activate,
                    enableNode = enable
                )
            }
        }

        // 2. 若预设静态路径未命中且有 ROOT，在 /sys/class 中动态搜索
        if (suShellAvailable) {
            val dynamicPath = findNodeViaSu("find /sys/class/leds/ -name \"*vibrator*\" -maxdepth 1 2>/dev/null | head -n 1")
            if (!dynamicPath.isNullOrEmpty()) {
                Log.i(TAG, "通过 ROOT 动态发现马达节点: $dynamicPath")
                return DiscoveredNodes(
                    baseDir = dynamicPath,
                    brightnessNode = "$dynamicPath/brightness",
                    durationNode = "$dynamicPath/duration",
                    activateNode = "$dynamicPath/activate",
                    enableNode = null
                )
            }
        }

        Log.i(TAG, "未发现物理 Sysfs 节点，将平滑使用 Android 原生 Vibrator 引擎")
        return null
    }

    private fun resolveNode(dir: String, candidates: List<String>, suAvailable: Boolean): String? {
        for (name in candidates) {
            val path = "$dir/$name"
            val exists = try {
                if (File(path).exists()) true else if (suAvailable) checkExistsViaSu(path) else false
            } catch (e: Exception) {
                false
            }
            if (exists) return path
        }
        return null
    }

    private fun checkExistsViaSu(path: String): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "[ -e '$path' ] && echo 1 || echo 0"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            line?.trim() == "1"
        } catch (e: Exception) {
            false
        }
    }

    private fun findNodeViaSu(command: String): String? {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()?.trim()
            process.waitFor()
            if (!line.isNullOrEmpty()) line else null
        } catch (e: Exception) {
            null
        }
    }
}
