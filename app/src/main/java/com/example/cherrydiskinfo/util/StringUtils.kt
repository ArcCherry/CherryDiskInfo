package com.example.cherrydiskinfo.util

import com.example.cherrydiskinfo.data.model.HealthStatus
import com.example.cherrydiskinfo.data.model.StorageType

/**
 * 字符串工具类
 * 提供本地化显示支持
 */
object StringUtils {
    
    /**
     * 获取存储类型的友好名称
     */
    fun getStorageTypeName(type: StorageType): String {
        return when (type) {
            StorageType.EMMC -> "eMMC"
            StorageType.UFS -> "UFS"
            StorageType.NVME -> "NVMe"
            StorageType.UNKNOWN -> "未知"
        }
    }
    
    /**
     * 获取存储类型的详细描述
     */
    fun getStorageTypeDescription(type: StorageType): String {
        return when (type) {
            StorageType.EMMC -> "嵌入式多媒体卡 (Embedded MultiMediaCard)"
            StorageType.UFS -> "通用闪存存储 (Universal Flash Storage)"
            StorageType.NVME -> "非易失性内存主机控制器接口"
            StorageType.UNKNOWN -> "无法识别存储类型"
        }
    }
    
    /**
     * 获取健康状态的中文名称
     */
    fun getHealthStatusName(status: HealthStatus): String {
        return when (status) {
            HealthStatus.GOOD -> "良好"
            HealthStatus.CAUTION -> "警告"
            HealthStatus.BAD -> "危险"
            HealthStatus.UNKNOWN -> "未知"
        }
    }
    
    /**
     * 获取健康状态的详细描述
     */
    fun getHealthStatusDescription(status: HealthStatus): String {
        return when (status) {
            HealthStatus.GOOD -> "存储健康状况良好，可放心使用"
            HealthStatus.CAUTION -> "存储健康度下降，建议定期备份重要数据"
            HealthStatus.BAD -> "存储健康度严重不足，建议尽快更换设备"
            HealthStatus.UNKNOWN -> "无法获取存储健康信息"
        }
    }
    
    /**
     * 格式化温度显示
     */
    fun formatTemperature(celsius: Int): String {
        return if (celsius >= 0) "$celsius°C" else "未知"
    }
    
    /**
     * 格式化时间（小时 -> 天/小时）
     */
    fun formatHours(hours: Long): String {
        return when {
            hours <= 0 -> "未知"
            hours >= 24 * 365 -> {
                val years = hours / (24 * 365)
                val days = (hours % (24 * 365)) / 24
                "${years}年 ${days}天"
            }
            hours >= 24 -> {
                val days = hours / 24
                val remainingHours = hours % 24
                "${days}天 ${remainingHours}小时"
            }
            else -> "${hours}小时"
        }
    }
    
    /**
     * 格式化次数
     */
    fun formatCount(count: Long): String {
        return when {
            count <= 0 -> "未知"
            count >= 10000 -> String.format("%.1f万", count / 10000.0)
            else -> count.toString()
        }
    }
    
    /**
     * 获取寿命状态的建议
     */
    fun getHealthAdvice(healthPercent: Int): String {
        return when {
            healthPercent < 0 -> "建议获取 Root 权限以查看详细的存储健康信息"
            healthPercent >= 90 -> "存储健康状况优秀，继续保持正常使用习惯"
            healthPercent >= 70 -> "存储健康状况良好，注意避免频繁的大文件写入"
            healthPercent >= 50 -> "存储开始老化，建议开启云备份，避免存储满载"
            healthPercent >= 30 -> "存储老化较严重，务必定期备份，考虑更换设备"
            else -> "存储即将达到寿命终点，强烈建议立即备份并更换设备"
        }
    }
}
