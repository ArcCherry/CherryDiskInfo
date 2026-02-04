package com.example.cherrydiskinfo.data.datasource

import android.content.Context
import android.util.Log
import com.example.cherrydiskinfo.data.model.HealthStatus
import com.example.cherrydiskinfo.data.model.RootStatus
import com.example.cherrydiskinfo.data.model.StorageInfo
import com.example.cherrydiskinfo.data.model.StorageType
import com.example.cherrydiskinfo.util.NonRootStorageInfo
import com.example.cherrydiskinfo.util.RootShell
import com.example.cherrydiskinfo.util.StorageUtils

/**
 * 存储信息数据源接口
 */
interface StorageDataSource {
    
    /**
     * 检查数据源是否可用
     */
    fun isAvailable(): Boolean
    
    /**
     * 获取存储信息
     * @return 存储信息，获取失败返回 null
     */
    suspend fun getStorageInfo(): StorageInfo?
    
    /**
     * 检查 Root 权限状态
     */
    fun checkRootStatus(): RootStatus
}

/**
 * Root 数据源 - 通过执行 su 命令获取底层信息
 */
class RootStorageDataSource : StorageDataSource {
    
    companion object {
        private const val TAG = "RootStorageDataSource"
        private const val DEFAULT_BLOCK_DEVICE = "mmcblk0"
    }
    
    override fun isAvailable(): Boolean {
        return checkRootStatus() == RootStatus.GRANTED
    }
    
    override fun checkRootStatus(): RootStatus {
        return try {
            val process = Runtime.getRuntime().exec("su -c id")
            val result = process.waitFor()
            if (result == 0) RootStatus.GRANTED else RootStatus.DENIED
        } catch (e: Exception) {
            RootStatus.DENIED
        }
    }
    
    override suspend fun getStorageInfo(): StorageInfo? {
        if (!isAvailable()) {
            Log.w(TAG, "Root not available")
            return null
        }
        
        return try {
            // 1. 获取块设备名称
            val blockDevices = StorageUtils.getBlockDevices()
            val blockDevice = blockDevices.firstOrNull { it.startsWith("mmcblk") || it.startsWith("sda") } 
                ?: DEFAULT_BLOCK_DEVICE
            
            Log.d(TAG, "Using block device: $blockDevice")
            
            // 2. 检测存储类型
            val storageType = StorageUtils.detectStorageType(blockDevice)
            Log.d(TAG, "Detected storage type: $storageType")
            
            // 3. 获取容量
            val capacity = StorageUtils.getStorageCapacity(blockDevice)
            
            // 4. 获取设备信息
            val model = StorageUtils.getDeviceModel(blockDevice)
            val firmware = StorageUtils.getFirmwareVersion(blockDevice)
            val serial = StorageUtils.getSerialNumber(blockDevice)
            
            // 5. 获取寿命信息（EXT_CSD 中的 life_time）
            val lifeTimeInfo = getLifeTimeInfo(blockDevice)
            val healthPercentage = lifeTimeInfo.first
            val healthStatus = lifeTimeInfo.second
            
            // 6. 获取写入统计
            val (bytesWritten, writeCount) = StorageUtils.getWriteStats(blockDevice)
            
            // 7. 获取通电时间和次数（如果可用）
            val (powerOnHours, powerCycleCount) = getPowerInfo(blockDevice)
            
            // 8. 获取温度（如果可用）
            val temperature = getTemperature(blockDevice)
            
            // 9. 获取可用空间（通过 StatFs）
            val availableBytes = StorageUtils.getCapacityFromStatFs()
            
            StorageInfo(
                name = "/dev/block/$blockDevice",
                type = storageType,
                model = model,
                firmwareVersion = firmware,
                serialNumber = serial,
                totalCapacity = capacity,
                availableBytes = availableBytes,
                healthStatus = healthStatus,
                healthPercentage = healthPercentage,
                temperature = temperature,
                totalBytesWritten = bytesWritten,
                powerOnHours = powerOnHours,
                powerCycleCount = powerCycleCount,
                wearLevel = healthPercentage,
                estimatedLifePercent = healthPercentage
            ).also {
                Log.i(TAG, "Storage info retrieved: $it")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage info", e)
            null
        }
    }
    
    /**
     * 获取寿命信息
     * 从 EXT_CSD 的 LIFE_TIME_EST_A 和 PRE_EOL_INFO 字段读取
     */
    private fun getLifeTimeInfo(blockDevice: String): Pair<Int, HealthStatus> {
        val mmcPath = "${StorageUtils.Paths.BLOCK_DIR}/$blockDevice"
        
        // 尝试读取 life_time (eMMC 5.0+ / UFS 2.0+)
        val lifeTime = RootShell.readFile("$mmcPath${StorageUtils.Paths.DEVICE_LIFE_TIME}")
        val preEolInfo = RootShell.readFile("$mmcPath${StorageUtils.Paths.DEVICE_PRE_EOL_INFO}")
        
        Log.d(TAG, "Life time: $lifeTime, Pre-EOL: $preEolInfo")
        
        // 解析 life_time
        val healthPercent = if (lifeTime.isNotEmpty()) {
            StorageUtils.parseLifeTime(lifeTime)
        } else {
            -1
        }
        
        // 根据寿命确定健康状态
        val healthStatus = when {
            healthPercent < 0 -> HealthStatus.UNKNOWN
            healthPercent >= 80 -> HealthStatus.GOOD
            healthPercent >= 50 -> HealthStatus.CAUTION
            else -> HealthStatus.BAD
        }
        
        return Pair(healthPercent, healthStatus)
    }
    
    /**
     * 获取电源信息（通电时间和次数）
     * 注：移动存储通常不提供标准的 SMART 电源信息
     */
    private fun getPowerInfo(blockDevice: String): Pair<Long, Long> {
        // 尝试从 SSR (Supported Settings Report) 获取
        val mmcPath = "${StorageUtils.Paths.BLOCK_DIR}/$blockDevice"
        val ssr = RootShell.readFile("$mmcPath${StorageUtils.Paths.DEVICE_SSR}")
        
        // 目前大部分移动存储不直接暴露这些信息
        // 可以通过解析 smartctl 输出获取（如果安装了）
        val smartOutput = RootShell.execute("smartctl -a /dev/block/$blockDevice 2>/dev/null")
        
        var powerOnHours = 0L
        var powerCycleCount = 0L
        
        if (smartOutput.isNotEmpty()) {
            smartOutput.lines().forEach { line ->
                when {
                    line.contains("Power_On_Hours") -> {
                        powerOnHours = extractSmartValue(line)
                    }
                    line.contains("Power_Cycle_Count") -> {
                        powerCycleCount = extractSmartValue(line)
                    }
                }
            }
        }
        
        return Pair(powerOnHours, powerCycleCount)
    }
    
    /**
     * 获取温度
     */
    private fun getTemperature(blockDevice: String): Int {
        // 尝试从 smartctl 获取
        val smartOutput = RootShell.execute("smartctl -a /dev/block/$blockDevice 2>/dev/null")
        
        smartOutput.lines().forEach { line ->
            if (line.contains("Temperature")) {
                val temp = extractSmartValue(line)
                if (temp > 0) return temp.toInt()
            }
        }
        
        // 尝试从 thermal zone 获取（系统温度作为参考）
        val thermalPaths = RootShell.findPath("/sys/class/thermal/thermal_zone*/temp")
        for (path in thermalPaths) {
            val temp = RootShell.readFile(path).toLongOrNull() ?: 0
            if (temp > 0) {
                // thermal 通常以 millidegree 为单位
                return (temp / 1000).toInt()
            }
        }
        
        return -1
    }
    
    /**
     * 从 SMART 输出行提取数值
     */
    private fun extractSmartValue(line: String): Long {
        return try {
            // SMART 格式类似：Power_On_Hours 0x0032 100 100 000 Old_age Always - 12345
            val parts = line.split(Regex("\\s+"))
            parts.lastOrNull()?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}

/**
 * ADB 数据源 - 通过 dumpsys 等命令获取（需要 ADB 授权）
 */
class AdbStorageDataSource : StorageDataSource {
    
    override fun isAvailable(): Boolean {
        // 检测是否可以通过 ADB shell 访问
        return try {
            val process = Runtime.getRuntime().exec("dumpsys diskstats")
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }
    
    override fun checkRootStatus(): RootStatus = RootStatus.UNKNOWN
    
    override suspend fun getStorageInfo(): StorageInfo? {
        // TODO: 实现通过 dumpsys diskstats 等获取信息
        return null
    }
}

/**
 * 系统 API 数据源 - Android 15+ StorageHealthStats
 */
class SystemApiStorageDataSource : StorageDataSource {
    
    override fun isAvailable(): Boolean {
        // Android 15 (API 35) 及以上
        return android.os.Build.VERSION.SDK_INT >= 35
    }
    
    override fun checkRootStatus(): RootStatus = RootStatus.UNKNOWN
    
    override suspend fun getStorageInfo(): StorageInfo? {
        // TODO: 使用 StorageHealthStats API
        return null
    }
}

/**
 * 估算数据源 - 基于系统统计信息估算（无需 Root）
 */
class EstimatedStorageDataSource(private val context: Context? = null) : StorageDataSource {
    
    companion object {
        private const val TAG = "EstimatedStorageDataSource"
    }
    
    override fun isAvailable(): Boolean {
        // 始终可用，但精度最低
        return true
    }
    
    override fun checkRootStatus(): RootStatus = RootStatus.UNKNOWN
    
    override suspend fun getStorageInfo(): StorageInfo? {
        return try {
            Log.i(TAG, "Getting estimated storage info using non-root methods")
            
            // 运行诊断
            context?.let { ctx ->
                val diagnosis = NonRootStorageInfo.diagnose(ctx)
                Log.d(TAG, "=== Diagnostics ===")
                Log.d(TAG, "diskStatsReadable: ${diagnosis.diskStatsReadable}")
                Log.d(TAG, "sysBlockReadable: ${diagnosis.sysBlockReadable}")
                Log.d(TAG, "dumpsysAvailable: ${diagnosis.dumpsysAvailable}")
                Log.d(TAG, "storageManagerAvailable: ${diagnosis.storageManagerAvailable}")
                Log.d(TAG, "Build props: ${diagnosis.buildProps}")
            }
            
            // 1. 通过 Build 属性和系统特征推断存储类型
            val storageType = NonRootStorageInfo.detectStorageType()
            Log.d(TAG, "Inferred storage type: $storageType")
            
            // 2. 获取容量信息
            val capacityInfo = context?.let { NonRootStorageInfo.getStorageCapacity(it) }
                ?: StorageUtils.getCapacityFromStatFs()
                    .let { NonRootStorageInfo.StorageCapacityInfo(totalBytes = it) }
            Log.d(TAG, "Capacity: ${capacityInfo.totalBytes} bytes")
            
            // 3. 尝试读取 /proc/diskstats
            val diskStats = NonRootStorageInfo.readDiskStats()
            Log.d(TAG, "Disk stats: ${diskStats.size} devices found")
            diskStats.forEach { stat ->
                Log.d(TAG, "  ${stat.device}: written=${stat.bytesWritten} bytes")
            }
            
            // 4. 尝试读取 /proc/partitions
            val partitions = NonRootStorageInfo.readPartitions()
            Log.d(TAG, "Partitions: ${partitions.size} entries found")
            
            // 5. 尝试读取 /sys/block
            val sysBlockInfo = NonRootStorageInfo.readSysBlockInfo()
            Log.d(TAG, "Sys block info: ${sysBlockInfo.size} devices found")
            sysBlockInfo.forEach { info ->
                Log.d(TAG, "  ${info.name}: size=${info.size}, model=${info.model}")
            }
            
            // 6. 尝试 dumpsys
            val dumpsysInfo = NonRootStorageInfo.getDumpsysDiskStats()
            Log.d(TAG, "Dumpsys info: $dumpsysInfo")
            
            // 7. 尝试 df 命令
            val dfInfo = NonRootStorageInfo.getDfInfo()
            Log.d(TAG, "Df entries: ${dfInfo.size}")
            
            // 8. 尝试 f2fs 统计
            val fsStats = NonRootStorageInfo.readFsStats()
            Log.d(TAG, "FS stats entries: ${fsStats.size}")
            fsStats.forEach { (path, value) ->
                Log.d(TAG, "  $path: $value")
            }
            
            // 9. 获取主设备信息
            val mainBlockInfo = sysBlockInfo.firstOrNull { 
                it.name == "mmcblk0" || it.name == "sda" || it.name == "nvme0n1" 
            } ?: sysBlockInfo.firstOrNull()
            
            // 10. 估算写入量（优先使用 diskstats）
            val estimatedBytesWritten = when {
                diskStats.isNotEmpty() -> {
                    val mainDevice = diskStats.find { 
                        it.device == "mmcblk0" || it.device == "sda" || it.device == "nvme0n1" 
                    } ?: diskStats.first()
                    mainDevice.bytesWritten
                }
                dumpsysInfo != null && dumpsysInfo.totalWrites > 0 -> {
                    dumpsysInfo.totalWrites
                }
                else -> 0L
            }
            Log.d(TAG, "Estimated bytes written: $estimatedBytesWritten")
            
            // 11. 估算健康度（基于写入量 / 预估 TBW）
            val estimatedHealth = if (diskStats.isNotEmpty()) {
                NonRootStorageInfo.estimateHealth(diskStats, capacityInfo.totalBytes)
            } else {
                // 无法读取写入统计时返回 -1
                Log.w(TAG, "Cannot estimate health: no disk stats available")
                -1
            }
            Log.d(TAG, "Estimated health: $estimatedHealth%")
            
            // 12. 获取存储卷信息
            val volumes = context?.let { NonRootStorageInfo.getStorageVolumes(it) } ?: emptyList()
            
            // 13. 尝试获取 storaged 统计
            val storagedStats = NonRootStorageInfo.getStoragedStats()
            Log.d(TAG, "Storaged stats: $storagedStats")
            
            // 14. 尝试基于使用时间估算健康度（当其他方法都失败时）
            val usageBasedHealth = if (estimatedHealth < 0) {
                context?.let {
                    try {
                        val estimate = NonRootStorageInfo.estimateHealthByUsage(it)
                        Log.d(TAG, "Usage-based health estimate: ${estimate.healthPercent}%")
                        estimate.healthPercent
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to estimate health by usage", e)
                        -1
                    }
                } ?: -1
            } else {
                estimatedHealth
            }
            
            // 15. 构建设备型号描述
            val model = buildModelDescription(storageType, mainBlockInfo, partitions, dumpsysInfo)
            
            // 16. 获取固件信息
            val firmwareInfo = buildFirmwareDescription(dumpsysInfo)
            
            // 使用更准确的健康度值
            val finalHealth = if (estimatedHealth >= 0) estimatedHealth else usageBasedHealth
            
            // 确定健康状态
            val healthStatus = when {
                finalHealth < 0 -> HealthStatus.UNKNOWN
                finalHealth >= 80 -> HealthStatus.GOOD
                finalHealth >= 50 -> HealthStatus.CAUTION
                else -> HealthStatus.BAD
            }
            
            // 如果有 storaged 数据，用它补充写入量
            val finalBytesWritten = when {
                estimatedBytesWritten > 0 -> estimatedBytesWritten
                storagedStats != null && storagedStats.writeBytes > 0 -> storagedStats.writeBytes
                else -> 0L
            }
            
            StorageInfo(
                name = mainBlockInfo?.name?.let { "/dev/block/$it" } ?: volumes.firstOrNull()?.uuid?.let { "/dev/block/$it" } ?: "/data",
                type = storageType,
                model = model,
                firmwareVersion = firmwareInfo,
                serialNumber = "读取受限（需 Root）",
                totalCapacity = capacityInfo.totalBytes,
                availableBytes = capacityInfo.availableBytes,  // 传入实际的可用空间
                healthStatus = healthStatus,
                healthPercentage = finalHealth,
                temperature = -1, // 无法获取
                totalBytesWritten = finalBytesWritten,
                powerOnHours = 0L, // 无法获取
                powerCycleCount = 0L, // 无法获取
                wearLevel = finalHealth,
                estimatedLifePercent = finalHealth
            ).also {
                Log.i(TAG, "Estimated storage info: type=$storageType, health=$estimatedHealth%, written=${it.getFormattedBytesWritten()}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get estimated storage info", e)
            // 返回基本信息
            getBasicStorageInfo()
        }
    }
    
    /**
     * 构建设备型号描述
     */
    private fun buildModelDescription(
        type: StorageType,
        blockInfo: NonRootStorageInfo.SysBlockInfo?,
        partitions: List<NonRootStorageInfo.PartitionInfo>,
        dumpsysInfo: NonRootStorageInfo.DumpsysDiskInfo?
    ): String {
        val parts = mutableListOf<String>()
        
        // 存储类型
        parts.add(when (type) {
            StorageType.EMMC -> "eMMC"
            StorageType.UFS -> "UFS"
            StorageType.NVME -> "NVMe"
            StorageType.UNKNOWN -> "Flash Storage"
        })
        
        // 文件系统类型
        if (dumpsysInfo?.fsType != null && dumpsysInfo.fsType != "unknown") {
            parts.add("${dumpsysInfo.fsType}文件系统")
        }
        
        // 设备型号（如果有）
        if (blockInfo != null && blockInfo.model != "Unknown") {
            parts.add(blockInfo.model)
        }
        
        // 分区信息
        if (partitions.isNotEmpty()) {
            val mainPart = partitions.find { it.name == "mmcblk0" || it.name == "sda" || it.name == "nvme0n1" }
                ?: partitions.first()
            parts.add("(${mainPart.name})")
        }
        
        return parts.joinToString(" ")
    }
    
    /**
     * 构建固件描述
     */
    private fun buildFirmwareDescription(dumpsysInfo: NonRootStorageInfo.DumpsysDiskInfo?): String {
        val info = mutableListOf<String>()
        
        // Android 版本
        info.add("Android ${android.os.Build.VERSION.RELEASE}")
        
        // API 级别
        info.add("API ${android.os.Build.VERSION.SDK_INT}")
        
        // 文件系统类型
        if (dumpsysInfo?.fsType != null && dumpsysInfo.fsType != "unknown") {
            info.add(dumpsysInfo.fsType.uppercase())
        }
        
        // 设备代号
        info.add(android.os.Build.BOARD)
        
        return info.joinToString(" | ")
    }
    
    /**
     * 获取最基本的存储信息（保底方案）
     */
    private fun getBasicStorageInfo(): StorageInfo {
        val capacity = StorageUtils.getCapacityFromStatFs()
        val type = NonRootStorageInfo.detectStorageType()
        
        // 尝试获取可用空间
        val availableBytes = try {
            context?.let {
                val statFs = android.os.StatFs(it.filesDir.path)
                statFs.availableBytes
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
        
        return StorageInfo(
            name = "/data",
            type = type,
            model = "${type.name} Device (Estimated)",
            firmwareVersion = "Android ${android.os.Build.VERSION.RELEASE}",
            serialNumber = "需 Root 权限",
            totalCapacity = capacity,
            availableBytes = availableBytes,
            healthStatus = HealthStatus.UNKNOWN,
            healthPercentage = -1,
            temperature = -1,
            totalBytesWritten = 0L,
            powerOnHours = 0L,
            powerCycleCount = 0L,
            wearLevel = -1,
            estimatedLifePercent = -1
        )
    }
}
