package com.example.cherrydiskinfo.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
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
import rikka.shizuku.Shizuku

/**
 * Shizuku 状态
 */
enum class ShizukuStatus {
    NOT_INSTALLED,      // Shizuku 未安装/未运行
    PERMISSION_DENIED,  // 已安装但未授权
    AVAILABLE,          // 可用
    CHECKING            // 检查中
}

/**
 * 存储信息 ViewModel
 */
class StorageViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "StorageViewModel"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
    }

    private val repository = StorageRepository(application.applicationContext)

    private val _storageInfo = MutableStateFlow<StorageInfoResult>(StorageInfoResult.Loading)
    val storageInfo: StateFlow<StorageInfoResult> = _storageInfo.asStateFlow()

    private val _rootStatus = MutableStateFlow(RootStatus.UNKNOWN)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private val _dataSources = MutableStateFlow<List<DataSourceInfo>>(emptyList())
    val dataSources: StateFlow<List<DataSourceInfo>> = _dataSources.asStateFlow()

    private val _shizukuStatus = MutableStateFlow(ShizukuStatus.CHECKING)
    val shizukuStatus: StateFlow<ShizukuStatus> = _shizukuStatus.asStateFlow()

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d(TAG, "Shizuku binder received")
        checkShizukuStatus()
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d(TAG, "Shizuku binder dead")
        _shizukuStatus.value = ShizukuStatus.NOT_INSTALLED
    }

    private val shizukuPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
                Log.d(TAG, "Shizuku permission result: $grantResult")
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    _shizukuStatus.value = ShizukuStatus.AVAILABLE
                    // 权限获取后刷新数据
                    refreshDataSources()
                    loadStorageInfo()
                } else {
                    _shizukuStatus.value = ShizukuStatus.PERMISSION_DENIED
                }
            }
        }

    init {
        initShizukuListeners()
        checkRootStatus()
        checkShizukuStatus()
        refreshDataSources()
        loadStorageInfo()
    }

    private fun initShizukuListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
            Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.addRequestPermissionResultListener(shizukuPermissionResultListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to init Shizuku listeners: ${e.message}")
        }
    }

    /**
     * 检查 Shizuku 状态
     */
    fun checkShizukuStatus() {
        _shizukuStatus.value = try {
            if (!Shizuku.pingBinder()) {
                ShizukuStatus.NOT_INSTALLED
            } else if (Shizuku.isPreV11()) {
                ShizukuStatus.NOT_INSTALLED
            } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                ShizukuStatus.AVAILABLE
            } else {
                ShizukuStatus.PERMISSION_DENIED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check Shizuku status: ${e.message}")
            ShizukuStatus.NOT_INSTALLED
        }
    }

    /**
     * 请求 Shizuku 权限
     */
    fun requestShizukuPermission() {
        try {
            if (Shizuku.pingBinder() && !Shizuku.isPreV11()) {
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request Shizuku permission: ${e.message}")
        }
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

    override fun onCleared() {
        super.onCleared()
        try {
            Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
            Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionResultListener)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup: ${e.message}")
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
