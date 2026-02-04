package com.example.cherrydiskinfo.data.repository

import android.content.Context
import com.example.cherrydiskinfo.data.datasource.*
import com.example.cherrydiskinfo.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 存储信息仓库
 * 负责协调多个数据源，按优先级获取存储信息
 */
class StorageRepository(private val context: Context? = null) {
    
    private val dataSources by lazy {
        listOf(
            RootStorageDataSource(),              // 优先级最高，信息最全
            SystemApiStorageDataSource(),         // Android 15+ 官方支持
            AdbStorageDataSource(),               // ADB 调试模式
            EstimatedStorageDataSource(context)   // 保底方案（非 Root）
        )
    }
    
    /**
     * 获取存储信息
     * 按优先级尝试所有数据源
     */
    suspend fun getStorageInfo(): StorageInfoResult = withContext(Dispatchers.IO) {
        for (source in dataSources) {
            if (source.isAvailable()) {
                val info = source.getStorageInfo()
                if (info != null) {
                    return@withContext StorageInfoResult.Success(info)
                }
            }
        }
        
        StorageInfoResult.Error("无法获取存储信息")
    }
    
    /**
     * 检查 Root 权限状态
     */
    fun checkRootStatus(): RootStatus {
        return (dataSources.firstOrNull { it is RootStorageDataSource } as? RootStorageDataSource)
            ?.checkRootStatus() ?: RootStatus.UNKNOWN
    }
    
    /**
     * 获取可用数据源列表
     */
    fun getAvailableDataSources(): List<DataSourceInfo> {
        return listOf(
            DataSourceInfo(
                type = DataSourceType.ROOT,
                isAvailable = dataSources[0].isAvailable(),
                description = "需要 Root 权限，可获取完整 SMART 信息"
            ),
            DataSourceInfo(
                type = DataSourceType.SYSTEM_API,
                isAvailable = dataSources[1].isAvailable(),
                description = "Android 15+ 系统 API"
            ),
            DataSourceInfo(
                type = DataSourceType.ADB,
                isAvailable = dataSources[2].isAvailable(),
                description = "需要 ADB 调试授权"
            ),
            DataSourceInfo(
                type = DataSourceType.ESTIMATED,
                isAvailable = true,
                description = "基于系统统计估算（无需 Root）"
            )
        )
    }
}
