package com.example.cherrydiskinfo.data.model

/**
 * Root 权限状态
 */
enum class RootStatus {
    GRANTED,    // 已获取 Root
    DENIED,     // 被拒绝/未Root
    UNKNOWN     // 未知状态
}

/**
 * 数据源类型
 */
enum class DataSourceType {
    ROOT,           // 通过 Root 读取
    ADB,            // 通过 ADB 读取
    SYSTEM_API,     // Android 15+ StorageHealthStats
    ESTIMATED       // 估算值
}

/**
 * 数据获取结果
 */
data class DataSourceInfo(
    val type: DataSourceType,
    val isAvailable: Boolean,
    val description: String
)
