package com.example.cherrydiskinfo.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.cherrydiskinfo.data.model.*
import com.example.cherrydiskinfo.data.repository.StorageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 存储信息 ViewModel
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {
    
    private val repository = StorageRepository(application.applicationContext)
    
    private val _storageInfo = MutableStateFlow<StorageInfoResult>(StorageInfoResult.Loading)
    val storageInfo: StateFlow<StorageInfoResult> = _storageInfo.asStateFlow()
    
    private val _rootStatus = MutableStateFlow(RootStatus.UNKNOWN)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()
    
    private val _dataSources = MutableStateFlow<List<DataSourceInfo>>(emptyList())
    val dataSources: StateFlow<List<DataSourceInfo>> = _dataSources.asStateFlow()
    
    init {
        checkRootStatus()
        refreshDataSources()
        loadStorageInfo()
    }
    
    /**
     * 加载存储信息
     */
    fun loadStorageInfo() {
        viewModelScope.launch {
            _storageInfo.value = StorageInfoResult.Loading
            _storageInfo.value = repository.getStorageInfo()
        }
    }
    
    /**
     * 检查 Root 权限状态
     */
    fun checkRootStatus() {
        _rootStatus.value = repository.checkRootStatus()
    }
    
    /**
     * 刷新数据源列表
     */
    fun refreshDataSources() {
        _dataSources.value = repository.getAvailableDataSources()
    }
    
    /**
     * 获取健康状态对应的颜色
     */
    fun getHealthColor(status: HealthStatus): androidx.compose.ui.graphics.Color {
        return when (status) {
            HealthStatus.GOOD -> androidx.compose.ui.graphics.Color(0xFF4CAF50)
            HealthStatus.CAUTION -> androidx.compose.ui.graphics.Color(0xFFFFA726)
            HealthStatus.BAD -> androidx.compose.ui.graphics.Color(0xFFEF5350)
            HealthStatus.UNKNOWN -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
        }
    }
}

/**
 * ViewModel Factory
 */
class StorageViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(StorageViewModel::class.java)) {
            return StorageViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
