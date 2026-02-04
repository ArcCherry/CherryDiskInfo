package com.example.cherrydiskinfo.tools.adb

import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ADB 客户端
 * 用于执行 ADB 命令
 */
class AdbClient(
    private val adbPath: String = "adb",
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    
    companion object {
        private const val DEFAULT_TIMEOUT_MS = 30000L
        private const val DEFAULT_COMMAND_TIMEOUT_MS = 10000L
    }
    
    /**
     * ADB 执行结果
     */
    data class AdbResult(
        val success: Boolean,
        val stdout: String,
        val stderr: String,
        val exitCode: Int
    )
    
    /**
     * 检查 ADB 是否可用
     */
    suspend fun isAdbAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeCommand(listOf(adbPath, "version"), 5000)
            result.success && result.stdout.contains("Android Debug Bridge")
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 获取已连接的设备列表
     */
    suspend fun getDevices(): List<AdbDevice> = withContext(Dispatchers.IO) {
        val result = executeCommand(listOf(adbPath, "devices", "-l"))
        if (!result.success) return@withContext emptyList()
        
        result.stdout.lines()
            .drop(1) // 跳过 "List of devices attached" 行
            .mapNotNull { line ->
                if (line.isBlank()) null
                else AdbDevice.parse(line)
            }
    }
    
    /**
     * 在指定设备上执行 shell 命令
     */
    suspend fun shell(
        deviceSerial: String,
        command: String,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
    ): AdbResult = withContext(Dispatchers.IO) {
        executeCommand(
            listOf(adbPath, "-s", deviceSerial, "shell", command),
            timeoutMs
        )
    }
    
    /**
     * 在指定设备上执行 shell 命令（简化版）
     */
    suspend fun shell(deviceSerial: String, vararg args: String): AdbResult {
        return shell(deviceSerial, args.joinToString(" "))
    }
    
    /**
     * 在任意可用设备上执行 shell 命令
     */
    suspend fun shell(command: String, timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS): AdbResult {
        val devices = getDevices()
        val device = devices.find { it.state == AdbDevice.DeviceState.DEVICE }
            ?: return AdbResult(false, "", "No device connected", -1)
        return shell(device.serial, command, timeoutMs)
    }
    
    /**
     * 获取设备属性
     */
    suspend fun getProp(deviceSerial: String, property: String): String {
        val result = shell(deviceSerial, "getprop", property)
        return if (result.success) result.stdout.trim() else ""
    }
    
    /**
     * 获取所有设备属性
     */
    suspend fun getAllProps(deviceSerial: String): Map<String, String> = withContext(Dispatchers.IO) {
        val result = shell(deviceSerial, "getprop")
        if (!result.success) return@withContext emptyMap()
        
        val props = mutableMapOf<String, String>()
        val regex = Regex("\\[([^\\]]+)\\]: \\[([^\\]]*)\\]")
        
        result.stdout.lines().forEach { line ->
            regex.find(line)?.let { match ->
                val (key, value) = match.destructured
                props[key] = value
            }
        }
        
        props
    }
    
    /**
     * 拉取文件
     */
    suspend fun pull(
        deviceSerial: String,
        remotePath: String,
        localPath: String
    ): AdbResult = withContext(Dispatchers.IO) {
        executeCommand(listOf(adbPath, "-s", deviceSerial, "pull", remotePath, localPath))
    }
    
    /**
     * 推送文件
     */
    suspend fun push(
        deviceSerial: String,
        localPath: String,
        remotePath: String
    ): AdbResult = withContext(Dispatchers.IO) {
        executeCommand(listOf(adbPath, "-s", deviceSerial, "push", localPath, remotePath))
    }
    
    /**
     * 安装 APK
     */
    suspend fun install(
        deviceSerial: String,
        apkPath: String,
        reinstall: Boolean = false,
        grantPermissions: Boolean = false
    ): AdbResult = withContext(Dispatchers.IO) {
        val args = mutableListOf(adbPath, "-s", deviceSerial, "install")
        if (reinstall) args.add("-r")
        if (grantPermissions) args.add("-g")
        args.add(apkPath)
        
        executeCommand(args)
    }
    
    /**
     * 卸载应用
     */
    suspend fun uninstall(deviceSerial: String, packageName: String): AdbResult {
        return executeCommand(listOf(adbPath, "-s", deviceSerial, "uninstall", packageName))
    }
    
    /**
     * 获取日志
     */
    suspend fun logcat(
        deviceSerial: String,
        filter: String? = null,
        lines: Int? = null
    ): AdbResult {
        val args = mutableListOf(adbPath, "-s", deviceSerial, "logcat", "-d")
        if (filter != null) args.add(filter)
        if (lines != null) {
            args.add("-t")
            args.add(lines.toString())
        }
        return executeCommand(args)
    }
    
    /**
     * 清除应用数据
     */
    suspend fun clearAppData(deviceSerial: String, packageName: String): AdbResult {
        return shell(deviceSerial, "pm", "clear", packageName)
    }
    
    /**
     * 强制停止应用
     */
    suspend fun forceStop(deviceSerial: String, packageName: String): AdbResult {
        return shell(deviceSerial, "am", "force-stop", packageName)
    }
    
    /**
     * 启动 Activity
     */
    suspend fun startActivity(
        deviceSerial: String,
        packageName: String,
        activityName: String? = null
    ): AdbResult {
        val component = if (activityName != null) {
            "$packageName/$activityName"
        } else {
            packageName
        }
        return shell(deviceSerial, "am", "start", "-n", component)
    }
    
    /**
     * 发送按键事件
     */
    suspend fun sendKeyEvent(deviceSerial: String, keyCode: Int): AdbResult {
        return shell(deviceSerial, "input", "keyevent", keyCode.toString())
    }
    
    /**
     * 点击屏幕
     */
    suspend fun tap(deviceSerial: String, x: Int, y: Int): AdbResult {
        return shell(deviceSerial, "input", "tap", x.toString(), y.toString())
    }
    
    /**
     * 输入文本
     */
    suspend fun inputText(deviceSerial: String, text: String): AdbResult {
        return shell(deviceSerial, "input", "text", text.replace(" ", "%s"))
    }
    
    /**
     * 截图
     */
    suspend fun screenshot(deviceSerial: String, remotePath: String = "/sdcard/screenshot.png"): AdbResult {
        return shell(deviceSerial, "screencap", "-p", remotePath)
    }
    
    /**
     * 录屏（开始）
     */
    suspend fun startScreenRecord(
        deviceSerial: String,
        remotePath: String = "/sdcard/screenrecord.mp4",
        timeLimitSeconds: Int = 180
    ): AdbResult {
        return shell(
            deviceSerial,
            "screenrecord",
            "--time-limit",
            timeLimitSeconds.toString(),
            remotePath
        )
    }
    
    /**
     * 获取存储信息（dumpsys diskstats）
     */
    suspend fun getDiskStats(deviceSerial: String): String {
        val result = shell(deviceSerial, "dumpsys", "diskstats")
        return if (result.success) result.stdout else ""
    }
    
    /**
     * 获取存储健康信息（需要 root）
     */
    suspend fun getStorageHealth(deviceSerial: String): StorageHealthInfo? {
        // 尝试读取 life_time
        val lifeTimeResult = shell(deviceSerial, "cat", "/sys/block/mmcblk0/device/life_time")
        val preEolResult = shell(deviceSerial, "cat", "/sys/block/mmcblk0/device/pre_eol_info")
        
        if (!lifeTimeResult.success && !preEolResult.success) {
            return null
        }
        
        return StorageHealthInfo(
            lifeTime = lifeTimeResult.stdout.trim().takeIf { it.isNotEmpty() },
            preEolInfo = preEolResult.stdout.trim().takeIf { it.isNotEmpty() }
        )
    }
    
    /**
     * 重启设备
     */
    suspend fun reboot(deviceSerial: String, mode: RebootMode = RebootMode.NORMAL): AdbResult {
        val args = mutableListOf(adbPath, "-s", deviceSerial, "reboot")
        if (mode != RebootMode.NORMAL) {
            args.add(mode.modeName)
        }
        return executeCommand(args)
    }
    
    enum class RebootMode(val modeName: String) {
        NORMAL(""),
        RECOVERY("recovery"),
        BOOTLOADER("bootloader"),
        FASTBOOT("fastboot"),
        EDL("edl")
    }
    
    /**
     * 等待设备连接
     */
    suspend fun waitForDevice(timeoutMs: Long = DEFAULT_TIMEOUT_MS): AdbResult {
        return executeCommand(listOf(adbPath, "wait-for-device"), timeoutMs)
    }
    
    /**
     * 连接远程 ADB 设备
     */
    suspend fun connect(host: String, port: Int = 5555): AdbResult {
        return executeCommand(listOf(adbPath, "connect", "$host:$port"))
    }
    
    /**
     * 断开远程 ADB 设备
     */
    suspend fun disconnect(host: String? = null, port: Int? = null): AdbResult {
        val args = mutableListOf(adbPath, "disconnect")
        if (host != null) {
            args.add(if (port != null) "$host:$port" else host)
        }
        return executeCommand(args)
    }
    
    /**
     * 执行命令的内部方法
     */
    private suspend fun executeCommand(
        command: List<String>,
        timeoutMs: Long = DEFAULT_COMMAND_TIMEOUT_MS
    ): AdbResult = withContext(Dispatchers.IO) {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        
        try {
            val processBuilder = ProcessBuilder(command)
                .redirectErrorStream(false)
            
            val process = processBuilder.start()
            
            // 读取标准输出
            val stdoutJob = launch {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        stdout.appendLine(line)
                    }
                }
            }
            
            // 读取错误输出
            val stderrJob = launch {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        stderr.appendLine(line)
                    }
                }
            }
            
            // 等待完成或超时
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            
            if (!completed) {
                process.destroyForcibly()
                return@withContext AdbResult(
                    false,
                    stdout.toString(),
                    stderr.toString() + "\nCommand timed out after ${timeoutMs}ms",
                    -1
                )
            }
            
            // 等待读取完成
            stdoutJob.join()
            stderrJob.join()
            
            val exitCode = process.exitValue()
            AdbResult(
                exitCode == 0,
                stdout.toString().trim(),
                stderr.toString().trim(),
                exitCode
            )
            
        } catch (e: IOException) {
            AdbResult(false, "", "Failed to execute command: ${e.message}", -1)
        } catch (e: InterruptedException) {
            AdbResult(false, "", "Command interrupted: ${e.message}", -1)
        }
    }
    
    /**
     * 关闭客户端
     */
    fun close() {
        coroutineScope.cancel()
    }
}

/**
 * 存储健康信息
 */
data class StorageHealthInfo(
    val lifeTime: String?,
    val preEolInfo: String?
) {
    fun isValid(): Boolean = !lifeTime.isNullOrEmpty() || !preEolInfo.isNullOrEmpty()
}
