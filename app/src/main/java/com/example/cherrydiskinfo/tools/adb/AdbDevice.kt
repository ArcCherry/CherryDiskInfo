package com.example.cherrydiskinfo.tools.adb

/**
 * ADB 设备信息
 */
data class AdbDevice(
    val serial: String,
    val state: DeviceState,
    val product: String? = null,
    val model: String? = null,
    val device: String? = null,
    val transportId: String? = null
) {
    enum class DeviceState {
        DEVICE,      // 已连接且可用
        OFFLINE,     // 离线
        UNAUTHORIZED,// 未授权
        RECOVERY,    // 恢复模式
        BOOTLOADER,  // Bootloader 模式
        UNKNOWN      // 未知状态
    }
    
    /**
     * 获取设备描述
     */
    fun getDisplayName(): String {
        return buildString {
            append(model ?: product ?: device ?: serial)
            if (state != DeviceState.DEVICE) {
                append(" [${state.name}]")
            }
        }
    }
    
    companion object {
        /**
         * 从 adb devices -l 输出行解析
         */
        fun parse(line: String): AdbDevice? {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return null
            
            val serial = parts[0]
            val stateStr = parts[1]
            val state = when (stateStr.lowercase()) {
                "device" -> DeviceState.DEVICE
                "offline" -> DeviceState.OFFLINE
                "unauthorized" -> DeviceState.UNAUTHORIZED
                "recovery" -> DeviceState.RECOVERY
                "bootloader" -> DeviceState.BOOTLOADER
                else -> DeviceState.UNKNOWN
            }
            
            // 解析额外信息
            var product: String? = null
            var model: String? = null
            var device: String? = null
            var transportId: String? = null
            
            for (i in 2 until parts.size) {
                val part = parts[i]
                when {
                    part.startsWith("product:") -> product = part.substringAfter(":")
                    part.startsWith("model:") -> model = part.substringAfter(":")
                    part.startsWith("device:") -> device = part.substringAfter(":")
                    part.startsWith("transport_id:") -> transportId = part.substringAfter(":")
                }
            }
            
            return AdbDevice(serial, state, product, model, device, transportId)
        }
    }
}
