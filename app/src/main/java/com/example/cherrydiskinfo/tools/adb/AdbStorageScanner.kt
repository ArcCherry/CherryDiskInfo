package com.example.cherrydiskinfo.tools.adb

/**
 * ADB 存储扫描器
 * 通过 ADB 获取设备的存储健康信息
 */
class AdbStorageScanner(private val adbClient: AdbClient) {
    
    /**
     * 存储设备信息
     */
    data class StorageDevice(
        val name: String,
        val type: StorageType,
        val size: Long,
        val model: String?,
        val healthInfo: HealthInfo?
    ) {
        enum class StorageType {
            EMMC, UFS, NVME, UNKNOWN
        }
        
        data class HealthInfo(
            val lifeTime: String?,
            val preEolInfo: String?,
            val wearLevel: Int?,
            val estimatedHealth: Int?
        )
    }
    
    /**
     * 扫描指定设备的存储信息
     */
    suspend fun scanDevice(deviceSerial: String): List<StorageDevice> {
        val devices = mutableListOf<StorageDevice>()
        
        // 1. 获取块设备列表
        val blockDevices = getBlockDevices(deviceSerial)
        
        // 2. 获取每个设备的详细信息
        for (blockDev in blockDevices) {
            val storageDev = analyzeBlockDevice(deviceSerial, blockDev)
            if (storageDev != null) {
                devices.add(storageDev)
            }
        }
        
        return devices
    }
    
    /**
     * 获取块设备列表
     */
    private suspend fun getBlockDevices(deviceSerial: String): List<String> {
        val result = adbClient.shell(deviceSerial, "ls", "/sys/block")
        if (!result.success) return emptyList()
        
        return result.stdout.lines()
            .filter { line ->
                line.startsWith("mmcblk") || 
                line.startsWith("sda") || 
                line.startsWith("nvme")
            }
    }
    
    /**
     * 分析块设备
     */
    private suspend fun analyzeBlockDevice(
        deviceSerial: String,
        blockName: String
    ): StorageDevice? {
        val basePath = "/sys/block/$blockName"
        
        // 获取大小
        val sizeResult = adbClient.shell(deviceSerial, "cat", "$basePath/size")
        val size = sizeResult.stdout.trim().toLongOrNull()?.times(512) ?: 0L
        
        // 获取型号
        val modelResult = adbClient.shell(deviceSerial, "cat", "$basePath/device/model")
        val model = modelResult.stdout.trim().takeIf { it.isNotEmpty() }
        
        // 推断类型
        val type = when {
            blockName.startsWith("mmcblk") -> StorageDevice.StorageType.EMMC
            blockName.startsWith("sda") -> StorageDevice.StorageType.UFS
            blockName.startsWith("nvme") -> StorageDevice.StorageType.NVME
            else -> StorageDevice.StorageType.UNKNOWN
        }
        
        // 获取健康信息（需要 root）
        val healthInfo = getHealthInfo(deviceSerial, blockName)
        
        return StorageDevice(
            name = blockName,
            type = type,
            size = size,
            model = model,
            healthInfo = healthInfo
        )
    }
    
    /**
     * 获取健康信息
     */
    private suspend fun getHealthInfo(
        deviceSerial: String,
        blockName: String
    ): StorageDevice.HealthInfo? {
        val devicePath = "/sys/block/$blockName/device"
        
        // 尝试读取各种健康相关文件
        val lifeTimeResult = adbClient.shell(deviceSerial, "cat", "$devicePath/life_time")
        val preEolResult = adbClient.shell(deviceSerial, "cat", "$devicePath/pre_eol_info")
        
        val lifeTime = lifeTimeResult.stdout.trim().takeIf { it.isNotEmpty() }
        val preEolInfo = preEolResult.stdout.trim().takeIf { it.isNotEmpty() }
        
        if (lifeTime == null && preEolInfo == null) {
            return null
        }
        
        // 解析磨损水平
        val wearLevel = lifeTime?.let { parseLifeTime(it) }
        
        // 估算健康度
        val estimatedHealth = wearLevel?.let { 100 - it }
        
        return StorageDevice.HealthInfo(
            lifeTime = lifeTime,
            preEolInfo = preEolInfo,
            wearLevel = wearLevel,
            estimatedHealth = estimatedHealth
        )
    }
    
    /**
     * 解析 life_time 值
     */
    private fun parseLifeTime(lifeTime: String): Int? {
        return try {
            val value = lifeTime.removePrefix("0x").toInt(16)
            when (value) {
                0x00 -> null
                0x01 -> 5   // 0-10% used
                0x02 -> 15  // 10-20% used
                0x03 -> 25
                0x04 -> 35
                0x05 -> 45
                0x06 -> 55
                0x07 -> 65
                0x08 -> 75
                0x09 -> 85
                0x0A -> 95  // 90-100% used
                0x0B -> 100 // Exceeded
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 获取详细的 SMART 信息（需要 root 和 smartctl）
     */
    suspend fun getSmartInfo(deviceSerial: String, blockName: String): String {
        val result = adbClient.shell(deviceSerial, "smartctl", "-a", "/dev/block/$blockName")
        return if (result.success) result.stdout else ""
    }
    
    /**
     * 获取 MMC 扩展 CSD 信息（需要 root）
     */
    suspend fun getExtCsdInfo(deviceSerial: String, blockName: String): String {
        // 尝试使用 mmc 命令
        val mmcResult = adbClient.shell(deviceSerial, "mmc", "extcsd", "read", "/dev/block/$blockName")
        if (mmcResult.success) return mmcResult.stdout
        
        // 尝试直接读取 sysfs
        val csdResult = adbClient.shell(deviceSerial, "cat", "/sys/block/$blockName/device/ext_csd")
        return if (csdResult.success) csdResult.stdout else ""
    }
    
    /**
     * 获取存储统计信息
     */
    suspend fun getStorageStats(deviceSerial: String): StorageStats {
        val result = adbClient.shell(deviceSerial, "dumpsys", "diskstats")
        val output = result.stdout
        
        var totalWrites: Long = 0
        var fsType: String = "unknown"
        
        output.lines().forEach { line ->
            when {
                line.contains("writes:", ignoreCase = true) -> {
                    Regex("writes:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                        totalWrites = it
                    }
                }
                line.contains("f2fs", ignoreCase = true) -> fsType = "F2FS"
                line.contains("ext4", ignoreCase = true) -> fsType = "ext4"
            }
        }
        
        return StorageStats(totalWrites, fsType)
    }
    
    data class StorageStats(
        val totalWrites: Long,
        val fsType: String
    )
    
    /**
     * 获取磁盘分区信息
     */
    suspend fun getPartitions(deviceSerial: String): List<PartitionInfo> {
        val result = adbClient.shell(deviceSerial, "cat", "/proc/partitions")
        if (!result.success) return emptyList()
        
        return result.stdout.lines()
            .drop(2) // 跳过表头
            .mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4) {
                    PartitionInfo(
                        major = parts[0].toIntOrNull() ?: 0,
                        minor = parts[1].toIntOrNull() ?: 0,
                        blocks = parts[2].toLongOrNull() ?: 0,
                        name = parts[3]
                    )
                } else null
            }
    }
    
    data class PartitionInfo(
        val major: Int,
        val minor: Int,
        val blocks: Long,
        val name: String
    )
}
