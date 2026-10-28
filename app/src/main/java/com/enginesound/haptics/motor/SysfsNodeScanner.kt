package com.enginesound.haptics.motor

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Sysfs 硬件节点扫描器
 *
 * 专门适配 红米 K40 游戏增强版 (Redmi K40G / 代号 ares / 天玑1200 / MT6893) 及各类 Android 设备。
 * K40G 内部搭载了 AAC 艾为/瑞声 X轴线性马达，在不同 MIUI/HyperOS 或第三方 ROM 下，节点路径略有差异。
 */
object SysfsNodeScanner {

    private const val TAG = "SysfsNodeScanner"

    // 预定义的常见马达硬件驱动节点目录（按 K40G / MTK 平台匹配优先级排序）
    val CANDIDATE_DIRECTORIES = listOf(
        // 1. K40G / MIUI 标准 leds 子系统马达节点
        "/sys/class/leds/vibrator",
        // 2. MTK 天玑 I2C 总线挂载的 AW8697 / AAC 触觉芯片节点
        "/sys/devices/platform/soc/11016000.i2c/i2c-5/5-005a/leds/vibrator",
        "/sys/devices/platform/soc/11007000.i2c/i2c-0/0-005a/leds/vibrator",
        // 3. MTK 电源管理 PMIC timed_output 节点
        "/sys/devices/platform/1000b000.pwrap/1000b000.pwrap:main_pmic/mt6359-vibrator/timed_output/vibrator",
        "/sys/class/timed_output/vibrator",
        // 4. 瑞声 / 艾为独立驱动字符节点目录
        "/sys/bus/i2c/drivers/aw8697_haptic",
        "/sys/class/qcom-haptics"
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
     * @param suShellAvailable 是否有 ROOT 权限执行辅助探测
     */
    fun detectNodes(suShellAvailable: Boolean): DiscoveredNodes? {
        Log.i(TAG, "开始探测 Redmi K40G 线性马达硬件节点...")

        // 首先通过本地文件系统或 su 检测已知的候选路径
        for (dirPath in CANDIDATE_DIRECTORIES) {
            val dir = File(dirPath)
            val exists = if (suShellAvailable) checkExistsViaSu(dirPath) else dir.exists()

            if (exists) {
                Log.i(TAG, "发现可用马达节点目录: $dirPath")
                val brightness = resolveNode(dirPath, listOf("brightness", "level", "gain", "vmax"))
                val duration = resolveNode(dirPath, listOf("duration", "time"))
                val activate = resolveNode(dirPath, listOf("activate", "state"))
                val enable = resolveNode(dirPath, listOf("enable"))

                return DiscoveredNodes(
                    baseDir = dirPath,
                    brightnessNode = brightness,
                    durationNode = duration,
                    activateNode = activate,
                    enableNode = enable
                )
            }
        }

        // 如果预设列表未命中且具有 ROOT，尝试在 /sys/class 中动态搜索
        if (suShellAvailable) {
            val dynamicPath = findNodeViaSu("find /sys/class/leds/ -name \"*vibrator*\" -maxdepth 1 | head -n 1")
            if (!dynamicPath.isNullOrEmpty()) {
                Log.i(TAG, "通过 ROOT 动态查找到节点目录: $dynamicPath")
                return DiscoveredNodes(
                    baseDir = dynamicPath,
                    brightnessNode = "$dynamicPath/brightness",
                    durationNode = "$dynamicPath/duration",
                    activateNode = "$dynamicPath/activate",
                    enableNode = null
                )
            }
        }

        Log.w(TAG, "未能探测到匹配的马达硬件节点，将回退至原生 Vibrator API")
        return null
    }

    private fun resolveNode(dir: String, candidates: List<String>): String? {
        for (name in candidates) {
            val path = "$dir/$name"
            if (File(path).exists() || checkExistsViaSu(path)) {
                return path
            }
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
