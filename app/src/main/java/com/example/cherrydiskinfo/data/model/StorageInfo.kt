package com.example.cherrydiskinfo.data.model

/**
 * 存储设备类型
 */
enum class StorageType {
    EMMC,       // eMMC 存储
    UFS,        // UFS 存储
    NVME,       // NVMe 存储（部分高端平板/Chromebook）
    UNKNOWN     // 未知类型
}

/**
 * 存储健康状态
 */
enum class HealthStatus {
    GOOD,       // 良好
    CAUTION,    // 警告（寿命 < 80%）
    BAD,        // 危险（寿命 < 50%）
    UNKNOWN     // 未知
}

/**
 * 存储设备核心信息
 * 
 * @property name 设备名称（如 /dev/block/mmcblk0）
 * @property type 存储类型（eMMC/UFS等）
 * @property model 设备型号
 * @property firmwareVersion 固件版本
 * @property serialNumber 序列号
 * @property totalCapacity 总容量（字节）- 这是文件系统层面的总容量
 * @property availableBytes 可用字节数 - 文件系统实际可用空间
 * @property healthStatus 健康状态
 * @property healthPercentage 健康度百分比（0-100）
 * @property temperature 温度（摄氏度，-1表示未知）
 * @property totalBytesWritten 总写入字节数（TBW相关，从diskstats获取）
 * @property powerOnHours 通电时间（小时）
 * @property powerCycleCount 通电次数
 * @property wearLevel 磨损水平（剩余PE周期百分比）
 * @property estimatedLifePercent 预估剩余寿命百分比
 */
data class StorageInfo(
    val name: String = "Unknown",
    val type: StorageType = StorageType.UNKNOWN,
    val model: String = "Unknown",
    val firmwareVersion: String = "Unknown",
    val serialNumber: String = "Unknown",
    val totalCapacity: Long = 0L,
    val availableBytes: Long = -1L,  // -1 表示未知
    val healthStatus: HealthStatus = HealthStatus.UNKNOWN,
    val healthPercentage: Int = -1,
    val temperature: Int = -1,
    val totalBytesWritten: Long = 0L,
    val powerOnHours: Long = 0L,
    val powerCycleCount: Long = 0L,
    val wearLevel: Int = -1,
    val estimatedLifePercent: Int = -1
) {
    /**
     * 获取格式化后的容量字符串
     */
    fun getFormattedCapacity(): String = formatBytes(totalCapacity)
    
    /**
     * 获取格式化后的写入量字符串
     */
    fun getFormattedBytesWritten(): String = formatBytes(totalBytesWritten)
    
    /**
     * 获取已使用容量（基于文件系统实际可用空间）
     * 优先使用 availableBytes，如果不可用则返回0
     */
    fun getUsedCapacity(): Long {
        return if (availableBytes >= 0 && totalCapacity > 0) {
            totalCapacity - availableBytes
        } else {
            0L
        }
    }
    
    /**
     * 获取已使用容量百分比
     */
    fun getUsedPercentage(): Int {
        return if (totalCapacity > 0) {
            ((getUsedCapacity().toDouble() / totalCapacity) * 100).toInt()
        } else {
            0
        }
    }
    
    /**
     * 获取格式化后的已使用容量
     */
    fun getFormattedUsedCapacity(): String = formatBytes(getUsedCapacity())
    
    /**
     * 获取剩余容量（文件系统实际可用）
     */
    fun getAvailableCapacity(): Long {
        return if (availableBytes >= 0) {
            availableBytes
        } else {
            // 如果不知道可用空间，返回总容量减去已使用（虽然这样不准确）
            totalCapacity - getUsedCapacity()
        }
    }
    
    /**
     * 获取格式化后的剩余容量
     */
    fun getFormattedAvailableCapacity(): String = formatBytes(getAvailableCapacity())
    
    /**
     * 获取TBW估算值（基于JEDEC标准）
     */
    fun getEstimatedTBW(): Long {
        // 简化的TBW估算：容量 * PE周期 / 写放大系数
        val peCycles = when (type) {
            StorageType.EMMC -> 3000L  // eMMC 通常是 3000 PE
            StorageType.UFS -> 3000L   // UFS 通常是 3000-5000 PE
            else -> 1000L
        }
        return (totalCapacity * peCycles) / (1024L * 1024L * 1024L * 1024L) // TB
    }
    
    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", value, units[unitIndex])
    }
}

/**
 * SMART 属性（尽可能获取）
 */
data class SmartAttribute(
    val id: Int,
    val name: String,
    val rawValue: Long,
    val normalizedValue: Int,
    val worstValue: Int,
    val threshold: Int,
    val status: String
)

/**
 * 存储信息结果封装
 */
sealed class StorageInfoResult {
    data class Success(val storageInfo: StorageInfo) : StorageInfoResult()
    data class Error(val message: String) : StorageInfoResult()
    data object Loading : StorageInfoResult()
}
