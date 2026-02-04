package com.example.cherrydiskinfo.tools.adb

import kotlinx.coroutines.runBlocking

/**
 * ADB 命令行工具示例
 * 
 * 这是一个示例类，展示如何使用 AdbClient 和 AdbStorageScanner。
 * 实际使用时，可以将其改造成真正的命令行工具或集成到 UI 中。
 */
object AdbCliExample {
    
    /**
     * 主入口（示例用法）
     */
    @JvmStatic
    fun main(args: Array<String>) {
        runBlocking {
            val adbClient = AdbClient()
            
            // 检查 ADB 是否可用
            if (!adbClient.isAdbAvailable()) {
                println("❌ ADB 不可用，请确保已安装 Android SDK 并添加到 PATH")
                return@runBlocking
            }
            println("✓ ADB 可用")
            
            // 获取设备列表
            val devices = adbClient.getDevices()
            if (devices.isEmpty()) {
                println("❌ 没有连接的设备")
                return@runBlocking
            }
            
            println("\n已连接的设备:")
            devices.forEachIndexed { index, device ->
                println("  ${index + 1}. ${device.getDisplayName()} (${device.serial})")
            }
            
            // 选择第一个可用设备
            val targetDevice = devices.find { it.state == AdbDevice.DeviceState.DEVICE }
                ?: run {
                    println("❌ 没有可用的设备（所有设备都处于离线或未授权状态）")
                    return@runBlocking
                }
            
            println("\n选择设备: ${targetDevice.getDisplayName()}")
            
            // 创建存储扫描器
            val scanner = AdbStorageScanner(adbClient)
            
            // 扫描存储
            println("\n正在扫描存储设备...")
            val storageDevices = scanner.scanDevice(targetDevice.serial)
            
            if (storageDevices.isEmpty()) {
                println("❌ 未找到存储设备")
            } else {
                println("\n找到 ${storageDevices.size} 个存储设备:")
                storageDevices.forEach { device ->
                    printStorageDeviceInfo(device)
                }
            }
            
            // 获取磁盘统计
            println("\n获取磁盘统计...")
            try {
                val stats = scanner.getStorageStats(targetDevice.serial)
                println("  文件系统类型: ${stats.fsType}")
                println("  总写入操作: ${stats.totalWrites}")
            } catch (e: Exception) {
                println("  获取磁盘统计失败: ${e.message}")
            }
            
            // 获取分区信息
            println("\n分区信息:")
            val partitions = scanner.getPartitions(targetDevice.serial)
            partitions.take(10).forEach { part ->
                println("  ${part.name}: ${part.blocks} blocks")
            }
            if (partitions.size > 10) {
                println("  ... 还有 ${partitions.size - 10} 个分区")
            }
            
            // 尝试获取属性
            println("\n设备属性:")
            val props = listOf(
                "ro.product.model",
                "ro.product.brand",
                "ro.build.version.release",
                "ro.build.version.sdk",
                "ro.board.platform",
                "ro.boot.hardware"
            )
            
            props.forEach { prop ->
                val value = adbClient.getProp(targetDevice.serial, prop)
                println("  $prop: $value")
            }
            
            // 关闭客户端
            adbClient.close()
            println("\n✓ 扫描完成")
        }
    }
    
    /**
     * 打印存储设备信息
     */
    private fun printStorageDeviceInfo(device: AdbStorageScanner.StorageDevice) {
        println("\n  设备: ${device.name}")
        println("    类型: ${device.type}")
        println("    容量: ${formatBytes(device.size)}")
        device.model?.let { println("    型号: $it") }
        
        device.healthInfo?.let { health ->
            println("    健康信息:")
            health.lifeTime?.let { println("      Life Time: $it") }
            health.preEolInfo?.let { println("      Pre-EOL Info: $it") }
            health.wearLevel?.let { println("      磨损水平: $it%") }
            health.estimatedHealth?.let { println("      预估健康度: $it%") }
        } ?: run {
            println("    健康信息: 无法获取（可能需要 root）")
        }
    }
    
    /**
     * 格式化字节数
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 * 1024 -> String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}

/**
 * ADB 命令行工具（可作为独立程序运行）
 */
class AdbCommandLineTool {
    
    private val adbClient = AdbClient()
    
    /**
     * 执行命令
     */
    suspend fun executeCommand(args: Array<String>): Int {
        if (args.isEmpty()) {
            printUsage()
            return 1
        }
        
        val command = args[0]
        val commandArgs = args.drop(1).toTypedArray()
        
        return when (command) {
            "devices" -> cmdDevices()
            "shell" -> cmdShell(commandArgs)
            "storage" -> cmdStorage(commandArgs)
            "health" -> cmdHealth(commandArgs)
            "pull" -> cmdPull(commandArgs)
            "push" -> cmdPush(commandArgs)
            "install" -> cmdInstall(commandArgs)
            "help" -> { printUsage(); 0 }
            else -> {
                println("未知命令: $command")
                printUsage()
                1
            }
        }
    }
    
    /**
     * 设备列表命令
     */
    private suspend fun cmdDevices(): Int {
        val devices = adbClient.getDevices()
        if (devices.isEmpty()) {
            println("没有连接的设备")
            return 0
        }
        
        println("已连接的设备列表:")
        println("%-20s %-10s %s".format("序列号", "状态", "描述"))
        println("-".repeat(60))
        
        devices.forEach { device ->
            println("%-20s %-10s %s".format(
                device.serial,
                device.state.name,
                device.getDisplayName()
            ))
        }
        
        return 0
    }
    
    /**
     * Shell 命令
     */
    private suspend fun cmdShell(args: Array<String>): Int {
        if (args.isEmpty()) {
            println("用法: shell <设备序列号> <命令>")
            return 1
        }
        
        val serial = args[0]
        val command = args.drop(1).joinToString(" ")
        
        println("在设备 $serial 上执行: $command")
        
        val result = adbClient.shell(serial, command)
        if (result.success) {
            println(result.stdout)
        } else {
            println("错误: ${result.stderr}")
        }
        
        return if (result.success) 0 else 1
    }
    
    /**
     * 存储扫描命令
     */
    private suspend fun cmdStorage(args: Array<String>): Int {
        val serial = args.firstOrNull()
            ?: adbClient.getDevices().find { it.state == AdbDevice.DeviceState.DEVICE }?.serial
            ?: run {
                println("没有可用的设备")
                return 1
            }
        
        println("扫描设备 $serial 的存储...")
        
        val scanner = AdbStorageScanner(adbClient)
        val devices = scanner.scanDevice(serial)
        
        if (devices.isEmpty()) {
            println("未找到存储设备")
            return 1
        }
        
        devices.forEach { device ->
            println("\n设备: ${device.name}")
            println("  类型: ${device.type}")
            println("  容量: ${device.size}")
            device.model?.let { println("  型号: $it") }
        }
        
        return 0
    }
    
    /**
     * 健康检查命令
     */
    private suspend fun cmdHealth(args: Array<String>): Int {
        val serial = args.firstOrNull()
            ?: adbClient.getDevices().find { it.state == AdbDevice.DeviceState.DEVICE }?.serial
            ?: run {
                println("没有可用的设备")
                return 1
            }
        
        println("检查设备 $serial 的存储健康...")
        
        val scanner = AdbStorageScanner(adbClient)
        val devices = scanner.scanDevice(serial)
        
        var hasHealthInfo = false
        
        devices.forEach { device ->
            device.healthInfo?.let { health ->
                hasHealthInfo = true
                println("\n设备: ${device.name}")
                health.lifeTime?.let { println("  Life Time: $it") }
                health.preEolInfo?.let { println("  Pre-EOL: $it") }
                health.wearLevel?.let { println("  磨损: $it%") }
                health.estimatedHealth?.let { println("  健康度: $it%") }
            }
        }
        
        if (!hasHealthInfo) {
            println("无法获取健康信息（设备可能需要 root 权限）")
        }
        
        return 0
    }
    
    /**
     * 拉取文件命令
     */
    private suspend fun cmdPull(args: Array<String>): Int {
        if (args.size < 3) {
            println("用法: pull <设备序列号> <远程路径> <本地路径>")
            return 1
        }
        
        val serial = args[0]
        val remotePath = args[1]
        val localPath = args[2]
        
        println("拉取 $remotePath 到 $localPath...")
        
        val result = adbClient.pull(serial, remotePath, localPath)
        return if (result.success) {
            println("✓ 拉取成功")
            0
        } else {
            println("✗ 拉取失败: ${result.stderr}")
            1
        }
    }
    
    /**
     * 推送文件命令
     */
    private suspend fun cmdPush(args: Array<String>): Int {
        if (args.size < 3) {
            println("用法: push <设备序列号> <本地路径> <远程路径>")
            return 1
        }
        
        val serial = args[0]
        val localPath = args[1]
        val remotePath = args[2]
        
        println("推送 $localPath 到 $remotePath...")
        
        val result = adbClient.push(serial, localPath, remotePath)
        return if (result.success) {
            println("✓ 推送成功")
            0
        } else {
            println("✗ 推送失败: ${result.stderr}")
            1
        }
    }
    
    /**
     * 安装 APK 命令
     */
    private suspend fun cmdInstall(args: Array<String>): Int {
        if (args.isEmpty()) {
            println("用法: install <设备序列号> <apk路径> [-r] [-g]")
            return 1
        }
        
        val serial = args[0]
        val apkPath = args[1]
        val reinstall = args.contains("-r")
        val grantPermissions = args.contains("-g")
        
        println("安装 $apkPath...")
        
        val result = adbClient.install(serial, apkPath, reinstall, grantPermissions)
        return if (result.success) {
            println("✓ 安装成功")
            0
        } else {
            println("✗ 安装失败: ${result.stderr}")
            1
        }
    }
    
    /**
     * 打印使用说明
     */
    private fun printUsage() {
        println("""
ADB 存储诊断工具

用法: <command> [options]

命令:
  devices              列出已连接的设备
  shell <serial> <cmd> 在设备上执行 shell 命令
  storage [serial]     扫描设备存储
  health [serial]      检查存储健康
  pull <serial> <remote> <local>  拉取文件
  push <serial> <local> <remote>  推送文件
  install <serial> <apk> [-r] [-g] 安装 APK
  help                 显示此帮助

示例:
  devices                                    # 列出设备
  shell abc123 getprop ro.product.model      # 获取设备型号
  storage abc123                             # 扫描存储
  health                                     # 检查第一个可用设备的健康
  pull abc123 /sdcard/file.txt ./file.txt    # 拉取文件
        """.trimIndent())
    }
    
    fun close() {
        adbClient.close()
    }
}

/**
 * 实际命令行入口（如果作为独立程序运行）
 */
fun main(args: Array<String>) {
    runBlocking {
        val tool = AdbCommandLineTool()
        val exitCode = tool.executeCommand(args)
        tool.close()
        kotlin.system.exitProcess(exitCode)
    }
}
