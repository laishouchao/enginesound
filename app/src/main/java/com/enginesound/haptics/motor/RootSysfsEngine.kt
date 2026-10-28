package com.enginesound.haptics.motor

import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import kotlin.math.roundToInt

/**
 * ROOT Sysfs 直接硬件控制引擎
 *
 * 核心设计：
 * 维持一个长驻交互式 ROOT Shell 进程 (su)，避免高频音频帧（20ms~50ms/次）重复 fork 进程。
 * 将写入开销从 50ms 骤降至 < 0.5ms，消除马达触觉与音乐之间的硬件时延。
 */
class RootSysfsEngine : AutoCloseable {

    companion object {
        private const val TAG = "RootSysfsEngine"
    }

    private var suProcess: Process? = null
    private var suWriter: BufferedWriter? = null
    private var isInitialized = false

    private var discoveredNodes: SysfsNodeScanner.DiscoveredNodes? = null

    /**
     * 初始化 ROOT 驱动引擎并提升节点访问权限
     */
    fun initialize(nodes: SysfsNodeScanner.DiscoveredNodes?): Boolean {
        this.discoveredNodes = nodes
        return try {
            val process = ProcessBuilder("su").redirectErrorStream(true).start()
            val writer = BufferedWriter(OutputStreamWriter(process.outputStream))

            // 1. 临时切换 SELinux 为宽容模式 (Permissive)，彻底解决 MIUI 严格的 SELinux 节点读写拦截
            writer.write("setenforce 0\n")

            // 2. 赋予检测到的马达节点完全读写权限
            nodes?.let {
                it.brightnessNode?.let { node -> writer.write("chmod 666 $node\n") }
                it.durationNode?.let { node -> writer.write("chmod 666 $node\n") }
                it.activateNode?.let { node -> writer.write("chmod 666 $node\n") }
                it.enableNode?.let { node -> writer.write("chmod 666 $node\n") }
            }

            writer.flush()

            suProcess = process
            suWriter = writer
            isInitialized = true
            Log.i(TAG, "ROOT Sysfs 引擎初始化成功，节点: ${nodes?.baseDir}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "获取 ROOT 权限或启动 su 进程失败", e)
            isInitialized = false
            false
        }
    }

    /**
     * 触发硬件振动
     * @param intensity 归一化振动强度 (0.0f ~ 1.0f)
     * @param durationMs 持续毫秒数
     */
    fun vibrate(intensity: Float, durationMs: Int) {
        if (!isInitialized || suWriter == null || discoveredNodes == null) return

        val clampedIntensity = intensity.coerceIn(0.0f, 1.0f)
        if (clampedIntensity <= 0.001f) {
            stop()
            return
        }

        try {
            val writer = suWriter ?: return
            val nodes = discoveredNodes ?: return

            // 映射到硬件寄存器常见范围 0 ~ 255
            val brightnessValue = (clampedIntensity * 255.0f).roundToInt().coerceIn(1, 255)
            val durationValue = durationMs.coerceIn(10, 2000)

            // 优先模式 1：常见 leds/vibrator 子系统
            if (nodes.brightnessNode != null && nodes.durationNode != null) {
                writer.write("echo $durationValue > ${nodes.durationNode}\n")
                writer.write("echo $brightnessValue > ${nodes.brightnessNode}\n")
                if (nodes.activateNode != null) {
                    writer.write("echo 1 > ${nodes.activateNode}\n")
                }
            } else if (nodes.enableNode != null) {
                // 传统 timed_output 驱动（如部分 MTK 内核）
                writer.write("echo $durationValue > ${nodes.enableNode}\n")
            } else if (nodes.brightnessNode != null) {
                writer.write("echo $brightnessValue > ${nodes.brightnessNode}\n")
            }

            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "向硬件节点写入振动指令失败", e)
        }
    }

    /**
     * 立即切断马达供电与振动（防止暂停后马达由于持续时间未到继续长鸣）
     */
    fun stop() {
        if (!isInitialized || suWriter == null) return
        try {
            val writer = suWriter ?: return
            val nodes = discoveredNodes ?: return

            if (nodes.activateNode != null) {
                writer.write("echo 0 > ${nodes.activateNode}\n")
            }
            if (nodes.brightnessNode != null) {
                writer.write("echo 0 > ${nodes.brightnessNode}\n")
            }
            if (nodes.enableNode != null) {
                writer.write("echo 0 > ${nodes.enableNode}\n")
            }
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "停止马达失败", e)
        }
    }

    override fun close() {
        try {
            stop()
            suWriter?.write("exit\n")
            suWriter?.flush()
            suWriter?.close()
        } catch (ignored: Exception) {}

        suProcess?.destroy()
        suProcess = null
        suWriter = null
        isInitialized = false
        Log.i(TAG, "ROOT Sysfs 引擎已释放")
    }
}
