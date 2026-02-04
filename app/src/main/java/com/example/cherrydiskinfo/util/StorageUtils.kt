package com.example.cherrydiskinfo.util

import android.os.StatFs
import android.util.Log
import com.example.cherrydiskinfo.data.model.StorageType
import java.io.File

/**
 * 存储工具类
 */
object StorageUtils {
    
    private const val TAG = "StorageUtils"
    
    /**
     * 存储设备路径配置
     */
    object Paths {
        // eMMC/UFS 设备路径
        const val MMC_HOST = "/sys/class/mmc_host"
        const val BLOCK_DIR = "/sys/block"
        const val MMC_BLOCK_PREFIX = "mmcblk"
        const val SD_BLOCK_PREFIX = "sd"
        
        // 常用设备信息文件
        const val DEVICE_TYPE = "/device/type"
        const val DEVICE_NAME = "/device/name"
        const val DEVICE_SERIAL = "/device/serial"
        const val DEVICE_MANFID = "/device/manfid"
        const val DEVICE_OEMID = "/device/oemid"
        const val DEVICE_FWREV = "/device/fwrev"
        const val DEVICE_HWREV = "/device/hwrev"
        const val DEVICE_SSR = "/device/ssr"
        const val DEVICE_LIFE_TIME = "/device/life_time"
        const val DEVICE_PRE_EOL_INFO = "/device/pre_eol_info"
        const val BLOCK_SIZE = "/queue/logical_block_size"
        const val BLOCK_COUNT = "/size"
        
        // proc 文件
        const val PROC_PARTITIONS = "/proc/partitions"
        const val PROC_DISKSTATS = "/proc/diskstats"
    }
    
    /**
     * 检测存储类型
     */
    fun detectStorageType(blockDevice: String = "mmcblk0"): StorageType {
        // 1. 检查是否为 UFS (通常设备名为 sda 或包含 ufs 标识)
        val ufsPaths = listOf(
            "/sys/bus/ufs",
            "/sys/class/ufs",
            "/sys/block/sda"
        )
        
        for (path in ufsPaths) {
            if (RootShell.fileExists(path)) {
                // 进一步确认
                val model = RootShell.readFile("$path/device/product")
                if (model.contains("UFS", ignoreCase = true) || 
                    model.contains("SCSI", ignoreCase = true)) {
                    return StorageType.UFS
                }
            }
        }
        
        // 2. 检查 eMMC (mmcblk 设备)
        val mmcPath = "${Paths.BLOCK_DIR}/$blockDevice"
        if (RootShell.fileExists(mmcPath)) {
            val type = RootShell.readFile("$mmcPath${Paths.DEVICE_TYPE}")
            val name = RootShell.readFile("$mmcPath${Paths.DEVICE_NAME}")
            
            if (type.contains("MMC", ignoreCase = true) ||
                name.contains("MMC", ignoreCase = true) ||
                blockDevice.startsWith(Paths.MMC_BLOCK_PREFIX)) {
                return StorageType.EMMC
            }
        }
        
        // 3. 检查 NVMe
        val nvmePaths = RootShell.findPath("/sys/block/nvme*")
        if (nvmePaths.isNotEmpty()) {
            return StorageType.NVME
        }
        
        return StorageType.UNKNOWN
    }
    
    /**
     * 获取块设备列表
     */
    fun getBlockDevices(): List<String> {
        val devices = mutableListOf<String>()
        
        // 读取 /sys/block 目录
        val blockDirs = RootShell.listDir(Paths.BLOCK_DIR)
        for (dir in blockDirs) {
            // 过滤出存储设备（排除 loop、ram 等虚拟设备）
            if (dir.startsWith(Paths.MMC_BLOCK_PREFIX) || 
                dir.startsWith(Paths.SD_BLOCK_PREFIX) ||
                dir.startsWith("nvme") ||
                dir.startsWith("sda")) {
                // 确认是物理设备（有 device 目录）
                if (RootShell.fileExists("${Paths.BLOCK_DIR}/$dir/device")) {
                    devices.add(dir)
                }
            }
        }
        
        return devices
    }
    
    /**
     * 获取存储容量
     */
    fun getStorageCapacity(blockDevice: String = "mmcblk0"): Long {
        // 方法1：通过 /sys/block 读取
        val sizeStr = RootShell.readFile("${Paths.BLOCK_DIR}/$blockDevice${Paths.BLOCK_COUNT}")
        val blockSizeStr = RootShell.readFile("${Paths.BLOCK_DIR}/$blockDevice${Paths.BLOCK_SIZE}")
        
        return if (sizeStr.isNotEmpty() && blockSizeStr.isNotEmpty()) {
            try {
                val sectors = sizeStr.trim().toLong()
                val blockSize = blockSizeStr.trim().toLong()
                sectors * blockSize
            } catch (e: NumberFormatException) {
                Log.e(TAG, "Failed to parse capacity", e)
                getCapacityFromStatFs()
            }
        } else {
            getCapacityFromStatFs()
        }
    }
    
    /**
     * 通过 StatFs 获取容量（无需 Root）
     */
    fun getCapacityFromStatFs(): Long {
        return try {
            val statFs = StatFs(File("/data").path)
            statFs.totalBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get capacity from StatFs", e)
            0L
        }
    }
    
    /**
     * 解析 eMMC/UFS 的 life_time 值
     * @param lifeTime 原始值 (0x01-0x0B)
     * @return 预估剩余寿命百分比
     * 
     * eMMC 5.0+ / UFS 2.0+ 支持
     * 0x01: 0-10% life time used
     * 0x02: 10-20% life time used
     * ...
     * 0x0A: 90-100% life time used
     * 0x0B:Exceeded max life time
     */
    fun parseLifeTime(lifeTime: String): Int {
        return try {
            val value = lifeTime.trim().removePrefix("0x").toInt(16)
            when (value) {
                0x00 -> -1  // 未定义
                0x01 -> 95  // 0-10% used -> 90-100% remaining
                0x02 -> 85  // 10-20% used
                0x03 -> 75
                0x04 -> 65
                0x05 -> 55
                0x06 -> 45
                0x07 -> 35
                0x08 -> 25
                0x09 -> 15
                0x0A -> 5   // 90-100% used
                0x0B -> 0   // Exceeded
                else -> -1
            }
        } catch (e: Exception) {
            -1
        }
    }
    
    /**
     * 获取设备型号名称
     */
    fun getDeviceModel(blockDevice: String = "mmcblk0"): String {
        val mmcPath = "${Paths.BLOCK_DIR}/$blockDevice"
        
        // 尝试读取 name
        val name = RootShell.readFile("$mmcPath${Paths.DEVICE_NAME}")
        if (name.isNotEmpty()) return name
        
        // 尝试读取 manfid + oemid 组合
        val manfid = RootShell.readFile("$mmcPath${Paths.DEVICE_MANFID}")
        val oemid = RootShell.readFile("$mmcPath${Paths.DEVICE_OEMID}")
        
        return if (manfid.isNotEmpty() || oemid.isNotEmpty()) {
            "MMC $manfid $oemid"
        } else {
            "Unknown Device"
        }
    }
    
    /**
     * 获取固件版本
     */
    fun getFirmwareVersion(blockDevice: String = "mmcblk0"): String {
        val mmcPath = "${Paths.BLOCK_DIR}/$blockDevice"
        
        val fwrev = RootShell.readFile("$mmcPath${Paths.DEVICE_FWREV}")
        val hwrev = RootShell.readFile("$mmcPath${Paths.DEVICE_HWREV}")
        
        return if (fwrev.isNotEmpty()) {
            if (hwrev.isNotEmpty()) "FW:$fwrev HW:$hwrev"
            else fwrev
        } else {
            "Unknown"
        }
    }
    
    /**
     * 获取序列号
     */
    fun getSerialNumber(blockDevice: String = "mmcblk0"): String {
        val mmcPath = "${Paths.BLOCK_DIR}/$blockDevice"
        val serial = RootShell.readFile("$mmcPath${Paths.DEVICE_SERIAL}")
        return serial.ifEmpty { "Unknown" }
    }
    
    /**
     * 获取写入统计（通过 /proc/diskstats）
     */
    fun getWriteStats(blockDevice: String = "mmcblk0"): Pair<Long, Long> {
        // 返回 (写入扇区数, 写入次数)
        val stats = RootShell.readFile(Paths.PROC_DISKSTATS)
        val lines = stats.split("\n")
        
        for (line in lines) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 14 && parts[2] == blockDevice) {
                return try {
                    val sectorsWritten = parts[9].toLong()
                    val writesCompleted = parts[7].toLong()
                    Pair(sectorsWritten * 512, writesCompleted) // 扇区通常512字节
                } catch (e: NumberFormatException) {
                    Pair(0L, 0L)
                }
            }
        }
        
        return Pair(0L, 0L)
    }
}
