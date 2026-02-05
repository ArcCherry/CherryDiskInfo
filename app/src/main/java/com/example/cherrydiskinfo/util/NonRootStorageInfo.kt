package com.example.cherrydiskinfo.util

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.util.Log
import com.example.cherrydiskinfo.data.model.StorageInfo
import com.example.cherrydiskinfo.data.model.StorageType
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.io.InputStreamReader

/**
 * 非 Root 存储信息获取工具
 * 利用系统 API 和可读的系统文件获取尽可能多的信息
 */
object NonRootStorageInfo {
    
    private const val TAG = "NonRootStorageInfo"
    
    /**
     * 诊断信息 - 用于调试
     */
    data class DiagnosisInfo(
        val diskStatsReadable: Boolean,
        val sysBlockReadable: Boolean,
        val procPartitionsReadable: Boolean,
        val dumpsysAvailable: Boolean,
        val storageManagerAvailable: Boolean,
        val buildProps: Map<String, String>,
        val errors: List<String>
    )
    
    /**
     * 尝试从 storaged 获取存储统计
     */
    fun getStoragedStats(): StoragedStats? {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys storaged")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            parseStoragedOutput(output)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get storaged stats: ${e.message}")
            null
        }
    }
    
    /**
     * 解析 storaged 输出
     */
    private fun parseStoragedOutput(output: String): StoragedStats? {
        var writeBytes: Long? = null
        var readBytes: Long? = null
        var fsyncCalls: Long? = null
        
        output.lines().forEach { line ->
            when {
                line.contains("Write bytes:", ignoreCase = true) -> {
                    Regex("Write bytes:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                        writeBytes = it
                    }
                }
                line.contains("Read bytes:", ignoreCase = true) -> {
                    Regex("Read bytes:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                        readBytes = it
                    }
                }
                line.contains("Fsync calls:", ignoreCase = true) -> {
                    Regex("Fsync calls:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()?.let {
                        fsyncCalls = it
                    }
                }
            }
        }
        
        return if (writeBytes != null || readBytes != null) {
            StoragedStats(
                writeBytes = writeBytes ?: 0,
                readBytes = readBytes ?: 0,
                fsyncCalls = fsyncCalls ?: 0
            )
        } else null
    }
    
    data class StoragedStats(
        val writeBytes: Long,
        val readBytes: Long,
        val fsyncCalls: Long
    )
    
    /**
     * 尝试获取 I/O 统计（/proc/diskstats 的替代）
     */
    fun getIOStats(): List<IOStat> {
        val stats = mutableListOf<IOStat>()
        
        // 尝试 /proc/self/mountinfo
        try {
            File("/proc/self/mountinfo").takeIf { it.canRead() }?.readText()?.lines()?.forEach { line ->
                // 解析挂载信息
            }
        } catch (e: Exception) {
            // 忽略
        }
        
        return stats
    }
    
    data class IOStat(
        val device: String,
        val readBytes: Long,
        val writeBytes: Long
    )
    
    /**
     * 基于使用时间和特征估算健康度
     * 这是一个非常粗略的估算，仅供参考
     */
    fun estimateHealthByUsage(context: Context): HealthEstimate {
        // 获取应用首次安装时间
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val firstInstallTime = packageInfo.firstInstallTime
        val daysSinceInstall = (System.currentTimeMillis() - firstInstallTime) / (1000 * 60 * 60 * 24)
        
        // 估算每日写入量（基于一般使用模式）
        // 普通用户每天大约写入 5-15GB
        val estimatedDailyWrite = 10L * 1024 * 1024 * 1024 // 10GB/天
        val estimatedTotalWrite = daysSinceInstall * estimatedDailyWrite
        
        // 获取存储类型和容量
        val storageType = detectStorageType()
        val capacity = getStorageCapacity(context).totalBytes
        
        // 计算预估 TBW
        val peCycles = when (storageType) {
            StorageType.EMMC -> 3000
            StorageType.UFS -> 3000
            StorageType.NVME -> 600
            else -> 1000
        }
        
        val estimatedTBW = (capacity * peCycles / (1024.0 * 1024 * 1024 * 1024)) * 1024 * 1024 * 1024 * 1024
        
        // 计算健康度
        val healthPercent = if (estimatedTBW > 0) {
            ((1 - estimatedTotalWrite.toDouble() / estimatedTBW) * 100).toInt().coerceIn(0, 100)
        } else {
            -1
        }
        
        return HealthEstimate(
            healthPercent = healthPercent,
            daysSinceInstall = daysSinceInstall,
            estimatedTotalWrite = estimatedTotalWrite,
            estimatedTBW = estimatedTBW.toLong(),
            method = "基于使用时间的粗略估算",
            reliability = "低"
        )
    }
    
    data class HealthEstimate(
        val healthPercent: Int,
        val daysSinceInstall: Long,
        val estimatedTotalWrite: Long,
        val estimatedTBW: Long,
        val method: String,
        val reliability: String
    )
    
    /**
     * 尝试获取小米特定的存储信息
     */
    fun getXiaomiStorageInfo(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        
        // 尝试读取小米可能有的属性
        val props = listOf(
            "ro.boot.storage.type",
            "ro.storage.type",
            "ro.boot.hardware.ufs",
            "ro.boot.hardware.emmc",
            "ro.product.board",
            "ro.board.platform"
        )
        
        for (prop in props) {
            try {
                val process = Runtime.getRuntime().exec("getprop $prop")
                val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
                if (value.isNotEmpty() && value != "null") {
                    info[prop] = value
                }
            } catch (e: Exception) {
                // 忽略
            }
        }
        
        // 尝试读取 persist 分区的一些信息
        try {
            val process = Runtime.getRuntime().exec("ls -la /persist/storage/ 2>/dev/null || echo 'not found'")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            if (!output.contains("not found")) {
                info["persist_storage"] = output
            }
        } catch (e: Exception) {
            // 忽略
        }
        
        return info
    }
    
    /**
     * 获取诊断信息
     */
    fun diagnose(context: Context): DiagnosisInfo {
        val errors = mutableListOf<String>()
        
        // 检查 /proc/diskstats
        val diskStatsReadable = try {
            File("/proc/diskstats").canRead()
        } catch (e: Exception) { false }
        
        // 检查 /sys/block
        val sysBlockReadable = try {
            File("/sys/block").canRead()
        } catch (e: Exception) { false }
        
        // 检查 /proc/partitions
        val procPartitionsReadable = try {
            File("/proc/partitions").canRead()
        } catch (e: Exception) { false }
        
        // 检查 dumpsys
        val dumpsysAvailable = try {
            Runtime.getRuntime().exec("dumpsys diskstats").waitFor() == 0
        } catch (e: Exception) { false }
        
        // 检查 StorageManager
        val storageManagerAvailable = try {
            context.getSystemService(Context.STORAGE_SERVICE) != null
        } catch (e: Exception) { false }
        
        // 收集 Build 属性
        val buildProps = mapOf(
            "HARDWARE" to Build.HARDWARE,
            "BOARD" to Build.BOARD,
            "DEVICE" to Build.DEVICE,
            "PRODUCT" to Build.PRODUCT,
            "MANUFACTURER" to Build.MANUFACTURER,
            "BRAND" to Build.BRAND,
            "MODEL" to Build.MODEL
        )
        
        return DiagnosisInfo(
            diskStatsReadable = diskStatsReadable,
            sysBlockReadable = sysBlockReadable,
            procPartitionsReadable = procPartitionsReadable,
            dumpsysAvailable = dumpsysAvailable,
            storageManagerAvailable = storageManagerAvailable,
            buildProps = buildProps,
            errors = errors
        )
    }

    /**
     * 存储类型检测调试信息
     */
    data class StorageTypeDebugInfo(
        val blockDevices: List<String>,
        val mainDevice: String,
        val ufsBusExists: Boolean,
        val deviceVendor: String,
        val deviceModel: String,
        val deviceType: String,
        val scsiInfo: String,
        val lifeTime: String,
        val detectedType: StorageType,
        val detectionMethod: String
    )

    /**
     * 收集存储类型检测的调试信息
     */
    fun getStorageTypeDebugInfo(): StorageTypeDebugInfo {
        var detectionMethod = "unknown"

        // 1. 列出 /sys/block 设备
        val blockDevices = try {
            File("/sys/block").listFiles()
                ?.map { it.name }
                ?.filter { !it.startsWith("loop") && !it.startsWith("ram") && !it.startsWith("dm-") }
                ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        // 2. 确定主设备
        val mainDevice = blockDevices.find {
            it == "sda" || it == "mmcblk0" || it == "nvme0n1"
        } ?: blockDevices.firstOrNull() ?: "unknown"

        // 3. 检查 UFS 总线
        val ufsBusExists = try {
            File("/sys/bus/ufs").exists()
        } catch (e: Exception) { false }

        // 4. 读取设备信息
        val deviceVendor = readFileOrExec("/sys/block/$mainDevice/device/vendor")
        val deviceModel = readFileOrExec("/sys/block/$mainDevice/device/model")
        val deviceType = readFileOrExec("/sys/block/$mainDevice/device/type")
        val lifeTime = readFileOrExec("/sys/block/$mainDevice/device/life_time")

        // 5. 读取 SCSI 信息
        val scsiInfo = readFileOrExec("/proc/scsi/scsi").take(200)

        // 6. 读取系统属性
        val bootDeviceProp = getSystemProperty("ro.boot.bootdevice")
        val bootDeviceLink = try {
            File("/dev/block/bootdevice").canonicalPath
        } catch (e: Exception) { "" }
        val ufsTracingExists = try {
            File("/sys/kernel/tracing/events/ufs").exists()
        } catch (e: Exception) { false }

        // 7. 检测存储类型（按优先级）
        var detectedType = StorageType.UNKNOWN

        when {
            // 优先级1：设备名直接判断
            mainDevice.startsWith("nvme") -> {
                detectedType = StorageType.NVME
                detectionMethod = "设备名 (nvme*)"
            }
            mainDevice.startsWith("mmcblk") -> {
                detectedType = StorageType.EMMC
                detectionMethod = "设备名 (mmcblk*)"
            }
            // 优先级2：ro.boot.bootdevice 属性
            bootDeviceProp.contains("ufshc", ignoreCase = true) -> {
                detectedType = StorageType.UFS
                detectionMethod = "ro.boot.bootdevice ($bootDeviceProp)"
            }
            bootDeviceProp.contains("mmc", ignoreCase = true) -> {
                detectedType = StorageType.EMMC
                detectionMethod = "ro.boot.bootdevice ($bootDeviceProp)"
            }
            // 优先级3：/dev/block/bootdevice 符号链接
            bootDeviceLink.contains("ufshc", ignoreCase = true) -> {
                detectedType = StorageType.UFS
                detectionMethod = "/dev/block/bootdevice -> ufshc"
            }
            bootDeviceLink.contains("mmc", ignoreCase = true) -> {
                detectedType = StorageType.EMMC
                detectionMethod = "/dev/block/bootdevice -> mmc"
            }
            // 优先级4：/sys/kernel/tracing/events/ufs
            ufsTracingExists -> {
                detectedType = StorageType.UFS
                detectionMethod = "/sys/kernel/tracing/events/ufs exists"
            }
            // 优先级5：/sys/bus/ufs
            ufsBusExists -> {
                detectedType = StorageType.UFS
                detectionMethod = "/sys/bus/ufs exists"
            }
            // 优先级6：sd* 设备但无法确认
            mainDevice.startsWith("sd") -> {
                detectedType = StorageType.UNKNOWN
                detectionMethod = "sd* 设备，无法确认类型"
            }
            else -> {
                detectedType = detectStorageType()
                detectionMethod = "Build 属性推断"
            }
        }

        return StorageTypeDebugInfo(
            blockDevices = blockDevices,
            mainDevice = mainDevice,
            ufsBusExists = ufsBusExists,
            deviceVendor = deviceVendor,
            deviceModel = deviceModel,
            deviceType = deviceType,
            scsiInfo = scsiInfo,
            lifeTime = lifeTime,
            detectedType = detectedType,
            detectionMethod = detectionMethod
        )
    }

    /**
     * 尝试读取文件，失败则尝试通过 shell 命令
     */
    private fun readFileOrExec(path: String): String {
        // 先尝试直接读取
        try {
            val file = File(path)
            if (file.exists() && file.canRead()) {
                return file.readText().trim()
            }
        } catch (e: Exception) {
            // ignore
        }

        // 尝试通过 shell 命令
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "cat $path 2>/dev/null"))
            val result = process.inputStream.bufferedReader().use { it.readText().trim() }
            process.waitFor()
            result.ifBlank { "(empty)" }
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
    }

    /**
     * 通过 Build 属性和系统特征推断存储类型
     */
    fun detectStorageType(): StorageType {
        // 方法1（最可靠）：检查 ro.boot.bootdevice 系统属性
        try {
            val bootDevice = getSystemProperty("ro.boot.bootdevice")
            if (bootDevice.contains("ufshc", ignoreCase = true)) {
                Log.d(TAG, "UFS detected via ro.boot.bootdevice: $bootDevice")
                return StorageType.UFS
            }
            if (bootDevice.contains("mmc", ignoreCase = true) || bootDevice.contains("emmc", ignoreCase = true)) {
                Log.d(TAG, "eMMC detected via ro.boot.bootdevice: $bootDevice")
                return StorageType.EMMC
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read ro.boot.bootdevice: ${e.message}")
        }

        // 方法2：检查 /dev/block/bootdevice 符号链接
        try {
            val bootDeviceLink = File("/dev/block/bootdevice").canonicalPath
            if (bootDeviceLink.contains("ufshc", ignoreCase = true)) {
                Log.d(TAG, "UFS detected via /dev/block/bootdevice: $bootDeviceLink")
                return StorageType.UFS
            }
            if (bootDeviceLink.contains("mmc", ignoreCase = true)) {
                Log.d(TAG, "eMMC detected via /dev/block/bootdevice: $bootDeviceLink")
                return StorageType.EMMC
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read /dev/block/bootdevice: ${e.message}")
        }

        // 方法3：检查 /sys/kernel/tracing/events/ufs 目录
        try {
            val ufsTracingDir = File("/sys/kernel/tracing/events/ufs")
            if (ufsTracingDir.exists() && ufsTracingDir.isDirectory) {
                Log.d(TAG, "UFS detected via /sys/kernel/tracing/events/ufs")
                return StorageType.UFS
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check /sys/kernel/tracing/events/ufs: ${e.message}")
        }

        // 方法4：检查硬件属性
        val hardware = Build.HARDWARE.lowercase()
        val board = Build.BOARD.lowercase()
        val device = Build.DEVICE.lowercase()
        val product = Build.PRODUCT.lowercase()
        val manufacturer = Build.MANUFACTURER.lowercase()

        Log.d(TAG, "Hardware: $hardware, Board: $board, Device: $device, Mfg: $manufacturer")

        // 小米设备特殊处理
        if (manufacturer.contains("xiaomi") || product.contains("mi")) {
            val miDevicesWithUFS = listOf("venus", "star", "mars", "cetus", "umi", "cmi", "cas")
            if (miDevicesWithUFS.any { device.contains(it) || product.contains(it) }) {
                return StorageType.UFS
            }
        }

        // 检查硬件字符串
        val checkString = "$hardware $board $device $product"

        return when {
            checkString.contains("ufs") -> StorageType.UFS
            checkString.contains("emmc") || checkString.contains("mmc") -> StorageType.EMMC
            checkString.contains("nvme") -> StorageType.NVME
            else -> inferStorageTypeFromSoC()
        }
    }

    /**
     * 读取系统属性
     */
    private fun getSystemProperty(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine() ?: ""
            reader.close()
            process.waitFor()
            result.trim()
        } catch (e: Exception) {
            ""
        }
    }
    
    /**
     * 根据 SoC 平台推断存储类型
     * 较新的旗舰芯片通常搭配 UFS
     */
    private fun inferStorageTypeFromSoC(): StorageType {
        val board = Build.BOARD.lowercase()
        val hardware = Build.HARDWARE.lowercase()
        
        // 高通旗舰平台（骁龙 835 及以后通常用 UFS）
        val snapdragonPattern = Regex("msm\\d+|sdm\\d+|sm\\d+|kona|lahaina|taro|kalama")
        val isRecentSnapdragon = snapdragonPattern.matches(hardware) || 
                                  snapdragonPattern.matches(board)
        
        // 联发科平台
        val mtkPattern = Regex("mt\\d+|dimensity")
        val isMtk = mtkPattern.matches(hardware) || mtkPattern.matches(board)
        
        // 麒麟芯片
        val kirinPattern = Regex("kirin|hi\\d+")
        val isKirin = kirinPattern.matches(hardware) || kirinPattern.matches(board)
        
        // 三星 Exynos
        val exynosPattern = Regex("exynos|universal")
        val isExynos = exynosPattern.matches(hardware) || exynosPattern.matches(board)
        
        return when {
            isRecentSnapdragon -> {
                // 较新的骁龙用 UFS
                val chipModel = hardware.replace(Regex("[^0-9]"), "").toIntOrNull()
                if (chipModel != null && chipModel >= 8996) { // 骁龙 820 及以上
                    StorageType.UFS
                } else {
                    StorageType.EMMC // 老款可能是 eMMC
                }
            }
            isKirin -> {
                // 麒麟 970 及以后通常用 UFS
                if (board.contains("970") || board.contains("980") || 
                    board.contains("990") || board.contains("9000")) {
                    StorageType.UFS
                } else {
                    StorageType.EMMC
                }
            }
            isExynos -> {
                // Exynos 8890 及以后通常用 UFS
                if (board.contains("8890") || board.contains("8895") ||
                    board.contains("9810") || board.contains("9820") ||
                    board.contains("990") || board.contains("2100")) {
                    StorageType.UFS
                } else {
                    StorageType.EMMC
                }
            }
            isMtk -> {
                // 联发科中高端用 UFS
                val mtkModel = hardware.replace(Regex("[^0-9]"), "").toIntOrNull()
                if (mtkModel != null && mtkModel >= 6797) { // Helio X20 及以上
                    StorageType.UFS
                } else {
                    StorageType.EMMC
                }
            }
            else -> StorageType.UNKNOWN
        }
    }
    
    /**
     * 获取存储容量信息（无需 Root）
     */
    fun getStorageCapacity(context: Context): StorageCapacityInfo {
        return try {
            val statFs = StatFs(context.filesDir.path)
            StorageCapacityInfo(
                totalBytes = statFs.totalBytes,
                availableBytes = statFs.availableBytes,
                usedBytes = statFs.totalBytes - statFs.availableBytes,
                blockSize = statFs.blockSizeLong,
                blockCount = statFs.blockCountLong
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage capacity", e)
            StorageCapacityInfo()
        }
    }
    
    /**
     * 通过 StorageManager 获取存储卷信息
     */
    fun getStorageVolumes(context: Context): List<StorageVolumeInfo> {
        val volumes = mutableListOf<StorageVolumeInfo>()
        
        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                storageManager.storageVolumes.forEach { volume ->
                    volumes.add(
                        StorageVolumeInfo(
                            description = volume.getDescription(context),
                            state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                volume.state
                            } else {
                                "unknown"
                            },
                            isPrimary = volume.isPrimary,
                            isEmulated = volume.isEmulated,
                            uuid = volume.uuid
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage volumes", e)
        }
        
        return volumes
    }
    
    /**
     * 使用 StorageStatsManager 获取应用存储统计（Android O+）
     */
    fun queryStorageStats(context: Context, packageName: String = context.packageName): AppStorageStats? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return null
        }
        
        return try {
            val statsManager = context.getSystemService(Context.STORAGE_STATS_SERVICE) 
                as StorageStatsManager
            val uuid = StorageManager.UUID_DEFAULT
            
            // 查询指定包的存储统计
            val stats = statsManager.queryStatsForPackage(uuid, packageName, android.os.Process.myUserHandle())
            
            AppStorageStats(
                appBytes = stats.appBytes,
                dataBytes = stats.dataBytes,
                cacheBytes = stats.cacheBytes
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query storage stats", e)
            null
        }
    }
    
    /**
     * 尝试读取 /proc/diskstats（某些设备允许）
     */
    fun readDiskStats(): List<DiskStat> {
        val stats = mutableListOf<DiskStat>()
        
        try {
            BufferedReader(FileReader("/proc/diskstats")).use { reader ->
                reader.lineSequence().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 14) {
                        val device = parts[2]
                        // 只关注物理存储设备
                        if (isPhysicalDevice(device)) {
                            stats.add(
                                DiskStat(
                                    device = device,
                                    readsCompleted = parts[3].toLongOrNull() ?: 0,
                                    sectorsRead = parts[5].toLongOrNull() ?: 0,
                                    writesCompleted = parts[7].toLongOrNull() ?: 0,
                                    sectorsWritten = parts[9].toLongOrNull() ?: 0
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read /proc/diskstats: ${e.message}")
        }
        
        return stats
    }
    
    /**
     * 尝试读取 /proc/partitions
     */
    fun readPartitions(): List<PartitionInfo> {
        val partitions = mutableListOf<PartitionInfo>()
        
        try {
            BufferedReader(FileReader("/proc/partitions")).use { reader ->
                reader.lineSequence().drop(2).forEach { line -> // 跳过表头
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 4) {
                        val name = parts[3]
                        if (isPhysicalDevice(name)) {
                            partitions.add(
                                PartitionInfo(
                                    major = parts[0].toIntOrNull() ?: 0,
                                    minor = parts[1].toIntOrNull() ?: 0,
                                    blocks = parts[2].toLongOrNull() ?: 0,
                                    name = name
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Cannot read /proc/partitions: ${e.message}")
        }
        
        return partitions
    }
    
    /**
     * 尝试读取 /sys/fs/ext4 或 /sys/fs/f2fs 下的信息
     */
    fun readFsStats(): Map<String, String> {
        val info = mutableMapOf<String, String>()
        
        // 尝试读取 f2fs 统计（小米常用 f2fs 文件系统）
        val f2fsPaths = listOf(
            "/sys/fs/f2fs",
            "/sys/fs/f2fs_stats",
            "/sys/fs/f2fs/userdata"
        )
        
        for (basePath in f2fsPaths) {
            try {
                val dir = File(basePath)
                if (dir.exists() && dir.canRead()) {
                    dir.listFiles()?.forEach { file ->
                        if (file.canRead() && file.isFile) {
                            val content = file.readText().trim()
                            if (content.isNotEmpty()) {
                                info["${basePath}/${file.name}"] = content.take(100)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cannot read $basePath: ${e.message}")
            }
        }
        
        return info
    }
    
    /**
     * 尝试通过 dumpsys 获取存储信息
     */
    fun getDumpsysDiskStats(): DumpsysDiskInfo? {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys diskstats")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            parseDumpsysOutput(output)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot run dumpsys diskstats: ${e.message}")
            null
        }
    }
    
    /**
     * 解析 dumpsys diskstats 输出
     */
    private fun parseDumpsysOutput(output: String): DumpsysDiskInfo? {
        // 查找关键信息
        var totalWrites: Long? = null
        var fsType: String? = null
        
        output.lines().forEach { line ->
            when {
                line.contains("writes:", ignoreCase = true) -> {
                    val match = Regex("writes:\\s*(\\d+)").find(line)
                    totalWrites = match?.groupValues?.get(1)?.toLongOrNull()
                }
                line.contains("f2fs", ignoreCase = true) -> fsType = "F2FS"
                line.contains("ext4", ignoreCase = true) -> fsType = "ext4"
            }
        }
        
        return if (totalWrites != null || fsType != null) {
            DumpsysDiskInfo(
                totalWrites = totalWrites ?: 0,
                fsType = fsType ?: "unknown"
            )
        } else null
    }
    
    /**
     * 尝试通过 df 命令获取磁盘使用信息
     */
    fun getDfInfo(): List<DfEntry> {
        val entries = mutableListOf<DfEntry>()
        
        try {
            val process = Runtime.getRuntime().exec("df")
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            
            output.lines().drop(1).forEach { line -> // 跳过表头
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 6) {
                    entries.add(
                        DfEntry(
                            filesystem = parts[0],
                            size = parts[1],
                            used = parts[2],
                            available = parts[3],
                            usePercent = parts[4],
                            mountedOn = parts[5]
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot run df: ${e.message}")
        }
        
        return entries
    }
    
    /**
     * 检查是否是物理存储设备（非 loop/ram 等虚拟设备）
     */
    private fun isPhysicalDevice(name: String): Boolean {
        return name.startsWith("mmcblk") || 
               name.startsWith("sda") || 
               name.startsWith("nvme") ||
               name.startsWith("dm-") || // device mapper
               (name.startsWith("sd") && !name.contains("/")) // SCSI/SATA 设备
    }
    
    /**
     * 尝试读取 /sys/block 下的设备信息（部分设备可读）
     */
    fun readSysBlockInfo(): List<SysBlockInfo> {
        val infos = mutableListOf<SysBlockInfo>()
        
        try {
            val blockDir = File("/sys/block")
            if (blockDir.exists() && blockDir.canRead()) {
                blockDir.listFiles()?.forEach { device ->
                    val name = device.name
                    if (isPhysicalDevice(name)) {
                        val size = readSysFile("${device.path}/size")
                        val model = readSysFile("${device.path}/device/model")
                        val removable = readSysFile("${device.path}/removable")
                        
                        infos.add(
                            SysBlockInfo(
                                name = name,
                                size = size?.toLongOrNull()?.times(512) ?: 0, // 扇区数 * 512
                                model = model ?: "Unknown",
                                isRemovable = removable == "1"
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read /sys/block: ${e.message}")
        }
        
        return infos
    }
    
    /**
     * 读取 sysfs 文件
     */
    private fun readSysFile(path: String): String? {
        return try {
            File(path).takeIf { it.canRead() }?.readText()?.trim()
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 估算健康度（基于写入量和预估 TBW）
     * 这是一个非常粗略的估算
     */
    fun estimateHealth(diskStats: List<DiskStat>, totalCapacity: Long): Int {
        // 找到主存储设备的写入统计
        val mainDevice = diskStats.find { 
            it.device == "mmcblk0" || it.device == "sda" || it.device == "nvme0n1" 
        } ?: diskStats.firstOrNull() ?: return -1
        
        // 估算写入量（扇区 * 512）
        val bytesWritten = mainDevice.sectorsWritten * 512
        
        Log.d(TAG, "Estimated bytes written: $bytesWritten for device ${mainDevice.device}")
        
        // 获取预估 TBW
        val peCycles = when (detectStorageType()) {
            StorageType.EMMC -> 3000
            StorageType.UFS -> 3000
            StorageType.NVME -> 600
            else -> 1000
        }
        
        // 简化的 TBW 估算 (考虑写放大系数 2.0)
        val writeAmplification = 2.0
        val estimatedTBW = (totalCapacity * peCycles / writeAmplification)
        
        Log.d(TAG, "Estimated TBW: $estimatedTBW, PE cycles: $peCycles")
        
        return if (estimatedTBW > 0) {
            val health = ((1 - bytesWritten.toDouble() / estimatedTBW) * 100).toInt()
                .coerceIn(0, 100)
            Log.d(TAG, "Calculated health: $health%")
            health
        } else {
            -1
        }
    }
    
    /**
     * 估算写入量（基于 diskstats）
     */
    fun estimateBytesWritten(): Long {
        val diskStats = readDiskStats()
        val mainDevice = diskStats.find { 
            it.device == "mmcblk0" || it.device == "sda" || it.device == "nvme0n1" 
        } ?: diskStats.firstOrNull()
        
        val bytesWritten = mainDevice?.let { it.sectorsWritten * 512 } ?: 0L
        Log.d(TAG, "estimateBytesWritten: $bytesWritten from device ${mainDevice?.device}")
        return bytesWritten
    }
    
    // 数据类定义
    data class StorageCapacityInfo(
        val totalBytes: Long = 0,
        val availableBytes: Long = 0,
        val usedBytes: Long = 0,
        val blockSize: Long = 0,
        val blockCount: Long = 0
    )
    
    data class StorageVolumeInfo(
        val description: String,
        val state: String,
        val isPrimary: Boolean,
        val isEmulated: Boolean,
        val uuid: String?
    )
    
    data class AppStorageStats(
        val appBytes: Long,
        val dataBytes: Long,
        val cacheBytes: Long
    )
    
    data class DiskStat(
        val device: String,
        val readsCompleted: Long,
        val sectorsRead: Long,
        val writesCompleted: Long,
        val sectorsWritten: Long
    ) {
        val bytesRead: Long get() = sectorsRead * 512
        val bytesWritten: Long get() = sectorsWritten * 512
    }
    
    data class PartitionInfo(
        val major: Int,
        val minor: Int,
        val blocks: Long,
        val name: String
    )
    
    data class SysBlockInfo(
        val name: String,
        val size: Long,
        val model: String,
        val isRemovable: Boolean = false
    )
    
    data class DumpsysDiskInfo(
        val totalWrites: Long,
        val fsType: String
    )
    
    data class DfEntry(
        val filesystem: String,
        val size: String,
        val used: String,
        val available: String,
        val usePercent: String,
        val mountedOn: String
    )
}
