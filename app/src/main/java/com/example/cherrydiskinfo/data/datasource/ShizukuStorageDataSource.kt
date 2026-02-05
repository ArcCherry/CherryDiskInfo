package com.example.cherrydiskinfo.data.datasource

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.example.cherrydiskinfo.data.model.HealthStatus
import com.example.cherrydiskinfo.data.model.RootStatus
import com.example.cherrydiskinfo.data.model.StorageInfo
import com.example.cherrydiskinfo.data.model.StorageType
import com.example.cherrydiskinfo.util.NonRootStorageInfo
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

/**
 * Shizuku 数据源 - 通过 Shizuku 获取 ADB 级别权限来读取存储信息
 *
 * Shizuku 提供的能力：
 * - 执行需要 shell 权限的命令（dumpsys 等）
 * - 读取部分受限的系统文件
 */
class ShizukuStorageDataSource(private val context: Context) : StorageDataSource {

    companion object {
        private const val TAG = "ShizukuStorageDataSource"
    }


    override fun isAvailable(): Boolean {
        return try {
            val binderAlive = Shizuku.pingBinder()
            val hasPermission = checkPermission()
            Log.d(TAG, "isAvailable: binderAlive=$binderAlive, hasPermission=$hasPermission")
            binderAlive && hasPermission
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku not available: ${e.message}")
            false
        }
    }

    override fun checkRootStatus(): RootStatus = RootStatus.UNKNOWN

    private fun checkPermission(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Failed to check Shizuku permission: ${e.message}")
            false
        }
    }

    override suspend fun getStorageInfo(): StorageInfo? {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku not available")
            return null
        }

        return try {
            Log.i(TAG, "Getting storage info via Shizuku")

            // 1. 获取容量信息（先执行，后续需要）
            val capacityInfo = NonRootStorageInfo.getStorageCapacity(context)

            // 2. 读取 /sys/block 设备信息（需要先知道主设备）
            val sysBlockOutput = executeShellCommand("ls /sys/block/")
            val blockDevices = sysBlockOutput.lines().filter {
                it.isNotBlank() && !it.startsWith("loop") && !it.startsWith("ram") && !it.startsWith("dm-")
            }
            val mainDevice = blockDevices.find {
                it == "sda" || it == "mmcblk0" || it == "nvme0n1"
            } ?: blockDevices.firstOrNull() ?: "mmcblk0"
            Log.d(TAG, "Main device: $mainDevice, all devices: $blockDevices")

            // 3. 通过 Shizuku 检测实际存储类型
            val detectionResult = detectStorageTypeViaShizuku(mainDevice)
            val storageType = detectionResult.type
            Log.d(TAG, "Detected storage type: $storageType via ${detectionResult.method}")

            // 4. 执行 dumpsys diskstats
            val diskStatsOutput = executeShellCommand("dumpsys diskstats")
            val dumpsysInfo = parseDumpsysDiskStats(diskStatsOutput)
            Log.d(TAG, "Dumpsys diskstats result: ${dumpsysInfo != null}")

            // 5. 执行 dumpsys storaged
            val storagedOutput = executeShellCommand("dumpsys storaged")
            val storagedStats = parseStoragedOutput(storagedOutput)
            Log.d(TAG, "Storaged stats result: ${storagedStats != null}")

            // 6. 读取 /proc/diskstats
            val procDiskStats = executeShellCommand("cat /proc/diskstats")
            val diskStats = parseProcDiskStats(procDiskStats)
            Log.d(TAG, "Proc diskstats devices: ${diskStats.size}")

            // 7. 读取设备详细信息
            val deviceModel = executeShellCommand("cat /sys/block/$mainDevice/device/model 2>/dev/null").trim()
            val deviceName = executeShellCommand("cat /sys/block/$mainDevice/device/name 2>/dev/null").trim()
            val deviceSize = executeShellCommand("cat /sys/block/$mainDevice/size 2>/dev/null").trim().toLongOrNull() ?: 0

            // 8. 尝试读取寿命信息（可能需要 root）
            val lifeTime = executeShellCommand("cat /sys/block/$mainDevice/device/life_time 2>/dev/null").trim()
            Log.d(TAG, "Life time raw: '$lifeTime'")

            // 9. 计算写入量
            val mainDiskStat = diskStats.find { it.device == mainDevice }
            val bytesWritten = when {
                mainDiskStat != null && mainDiskStat.bytesWritten > 0 -> mainDiskStat.bytesWritten
                storagedStats?.writeBytes ?: 0 > 0 -> storagedStats?.writeBytes ?: 0
                dumpsysInfo?.totalWrites ?: 0 > 0 -> dumpsysInfo?.totalWrites ?: 0
                else -> 0L
            }

            // 10. 估算健康度（精确值）
            val healthPercentExact: Double = when {
                lifeTime.isNotEmpty() && lifeTime.matches(Regex("0x[0-9a-fA-F]+.*")) -> {
                    parseLifeTime(lifeTime).toDouble()
                }
                bytesWritten > 0 && capacityInfo.totalBytes > 0 -> {
                    estimateHealthFromWritesExact(bytesWritten, capacityInfo.totalBytes, storageType)
                }
                else -> -1.0
            }
            val healthPercent = if (healthPercentExact >= 0) healthPercentExact.roundToInt() else -1

            val healthStatus = when {
                healthPercent < 0 -> HealthStatus.UNKNOWN
                healthPercent >= 80 -> HealthStatus.GOOD
                healthPercent >= 50 -> HealthStatus.CAUTION
                else -> HealthStatus.BAD
            }

            val model = buildModelDescription(storageType, deviceModel, deviceName, dumpsysInfo)
            val totalCapacity = if (deviceSize > 0) deviceSize * 512 else capacityInfo.totalBytes

            // 构建检测方法描述
            val detectionMethodDesc = buildString {
                append("检测方法: ${detectionResult.method}")
                if (detectionResult.details.isNotBlank()) {
                    append("\n详情: ${detectionResult.details}")
                }
                append("\n主设备: $mainDevice")
                append("\n块设备: ${blockDevices.joinToString(", ")}")
            }

            StorageInfo(
                name = "/dev/block/$mainDevice",
                type = storageType,
                model = model,
                firmwareVersion = buildFirmwareDescription(dumpsysInfo),
                serialNumber = "需 Root 权限",
                totalCapacity = totalCapacity,
                availableBytes = capacityInfo.availableBytes,
                healthStatus = healthStatus,
                healthPercentage = healthPercent,
                healthPercentageExact = healthPercentExact,
                temperature = -1,
                totalBytesWritten = bytesWritten,
                powerOnHours = 0L,
                powerCycleCount = 0L,
                wearLevel = healthPercent,
                estimatedLifePercent = healthPercent,
                detectionMethod = detectionMethodDesc
            ).also {
                Log.i(TAG, "Shizuku storage info: type=${it.type}, health=${it.healthPercentage}%, written=${it.totalBytesWritten}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to get storage info via Shizuku", e)
            null
        }
    }

    /**
     * 通过 Shizuku 执行 shell 命令（ADB 权限级别）
     */
    private fun executeShellCommand(command: String): String {
        if (!isAvailable()) {
            Log.w(TAG, "Shizuku not available, falling back to normal exec")
            return executeNormalCommand(command)
        }

        var process: Process? = null
        return try {
            // 使用反射调用 Shizuku.newProcess()
            val shizukuClass = Shizuku::class.java
            val newProcessMethod = shizukuClass.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true

            process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            Log.d(TAG, "Shizuku command '$command' exit code: $exitCode")
            output
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute via Shizuku: $command - ${e.message}")
            executeNormalCommand(command)
        } finally {
            process?.destroy()
        }
    }

    /**
     * 普通 shell 命令执行（无特殊权限）
     */
    private fun executeNormalCommand(command: String): String {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output
        } catch (e: Exception) {
            Log.w(TAG, "Failed to execute command: $command - ${e.message}")
            ""
        } finally {
            process?.destroy()
        }
    }

    private fun parseDumpsysDiskStats(output: String): DumpsysDiskInfo? {
        if (output.isBlank()) return null

        var totalWrites: Long? = null
        var fsType: String? = null

        output.lines().forEach { line ->
            when {
                line.contains("writes:", ignoreCase = true) -> {
                    Regex("writes:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { totalWrites = it }
                }
                line.contains("f2fs", ignoreCase = true) -> fsType = "F2FS"
                line.contains("ext4", ignoreCase = true) -> fsType = "ext4"
            }
        }

        return if (totalWrites != null || fsType != null) {
            DumpsysDiskInfo(totalWrites ?: 0, fsType ?: "unknown")
        } else null
    }

    private fun parseStoragedOutput(output: String): StoragedStats? {
        if (output.isBlank()) return null

        var writeBytes: Long? = null
        var readBytes: Long? = null

        output.lines().forEach { line ->
            when {
                line.contains("Write bytes:", ignoreCase = true) -> {
                    Regex("Write bytes:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { writeBytes = it }
                }
                line.contains("Read bytes:", ignoreCase = true) -> {
                    Regex("Read bytes:\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                        ?.let { readBytes = it }
                }
            }
        }

        return if (writeBytes != null || readBytes != null) {
            StoragedStats(writeBytes ?: 0, readBytes ?: 0)
        } else null
    }

    private fun parseProcDiskStats(output: String): List<DiskStat> {
        if (output.isBlank()) return emptyList()

        val stats = mutableListOf<DiskStat>()
        output.lines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 14) {
                val device = parts[2]
                if (device.startsWith("mmcblk") || device.startsWith("sd") || device.startsWith("nvme")) {
                    val sectorsWritten = parts[9].toLongOrNull() ?: 0
                    stats.add(DiskStat(device, sectorsWritten * 512))
                }
            }
        }
        return stats
    }

    /**
     * 通过 Shizuku 检测实际存储类型
     *
     * 检测优先级（从最可靠到最不可靠）：
     * 1. 设备名直接判断：nvme* → NVMe, mmcblk* → eMMC
     * 2. ro.boot.bootdevice 系统属性（包含 ufshc = UFS）
     * 3. /dev/block/bootdevice 符号链接（指向 ufshc = UFS）
     * 4. /sys/kernel/tracing/events/ufs 目录存在
     * 5. dmesg 中的 ufshcd 驱动日志
     * 6. /sys/class/ufs 或 /sys/bus/ufs 目录
     * 7. SCSI vendor 信息
     * 8. sd* 设备名（最不可靠）
     *
     * 注意：sda 不一定是 UFS！可能是 USB 存储
     */
    private fun detectStorageTypeViaShizuku(mainDevice: String): DetectionResult {
        Log.d(TAG, "Detecting storage type for device: $mainDevice")

        return try {
            // ========== 优先级 1：设备名直接判断（最可靠） ==========
            when {
                mainDevice.startsWith("nvme") -> {
                    Log.d(TAG, "NVMe detected by device name")
                    return DetectionResult(StorageType.NVME, "设备名", "nvme* → NVMe")
                }
                mainDevice.startsWith("mmcblk") -> {
                    Log.d(TAG, "eMMC detected by device name (mmcblk*)")
                    return DetectionResult(StorageType.EMMC, "设备名", "mmcblk* → eMMC")
                }
            }

            // ========== 优先级 2：ro.boot.bootdevice 系统属性（非常可靠） ==========
            val bootDevice = executeShellCommand("getprop ro.boot.bootdevice 2>/dev/null").trim()
            if (bootDevice.contains("ufshc", ignoreCase = true)) {
                Log.d(TAG, "UFS detected via ro.boot.bootdevice: $bootDevice")
                return DetectionResult(StorageType.UFS, "ro.boot.bootdevice", bootDevice)
            }
            if (bootDevice.contains("mmc", ignoreCase = true)) {
                Log.d(TAG, "eMMC detected via ro.boot.bootdevice: $bootDevice")
                return DetectionResult(StorageType.EMMC, "ro.boot.bootdevice", bootDevice)
            }

            // ========== 优先级 3：/dev/block/bootdevice 符号链接 ==========
            val bootDeviceLink = executeShellCommand("readlink /dev/block/bootdevice 2>/dev/null").trim()
            if (bootDeviceLink.contains("ufshc", ignoreCase = true)) {
                Log.d(TAG, "UFS detected via /dev/block/bootdevice symlink: $bootDeviceLink")
                return DetectionResult(StorageType.UFS, "/dev/block/bootdevice", bootDeviceLink)
            }
            if (bootDeviceLink.contains("mmc", ignoreCase = true)) {
                Log.d(TAG, "eMMC detected via /dev/block/bootdevice symlink: $bootDeviceLink")
                return DetectionResult(StorageType.EMMC, "/dev/block/bootdevice", bootDeviceLink)
            }

            // ========== 优先级 4：/sys/kernel/tracing/events/ufs 目录 ==========
            val ufsTracingCheck = executeShellCommand("ls /sys/kernel/tracing/events/ufs 2>/dev/null | head -3").trim()
            if (ufsTracingCheck.isNotBlank() && ufsTracingCheck.contains("ufshcd")) {
                Log.d(TAG, "UFS detected via /sys/kernel/tracing/events/ufs")
                return DetectionResult(StorageType.UFS, "/sys/kernel/tracing/events/ufs", "ufshcd tracing events exist")
            }

            // ========== 优先级 5：dmesg 中的 ufshcd 驱动日志 ==========
            val dmesgUfs = executeShellCommand("dmesg 2>/dev/null | grep -i ufshcd | head -3").trim()
            if (dmesgUfs.isNotBlank() && dmesgUfs.contains("ufshcd", ignoreCase = true)) {
                Log.d(TAG, "UFS detected via dmesg (ufshcd driver)")
                val firstLine = dmesgUfs.lines().firstOrNull()?.take(80) ?: "ufshcd detected"
                return DetectionResult(StorageType.UFS, "dmesg ufshcd", firstLine)
            }

            // ========== 优先级 6：/sys/class/ufs 或 /sys/bus/ufs 目录 ==========
            val ufsClassCheck = executeShellCommand("ls /sys/class/ufs 2>/dev/null").trim()
            if (ufsClassCheck.isNotBlank() && !ufsClassCheck.contains("No such file")) {
                Log.d(TAG, "UFS detected via /sys/class/ufs")
                return DetectionResult(StorageType.UFS, "/sys/class/ufs", ufsClassCheck.lines().firstOrNull() ?: "exists")
            }

            val ufsBusCheck = executeShellCommand("ls /sys/bus/ufs/devices 2>/dev/null").trim()
            if (ufsBusCheck.isNotBlank() && !ufsBusCheck.contains("No such file")) {
                Log.d(TAG, "UFS detected via /sys/bus/ufs")
                return DetectionResult(StorageType.UFS, "/sys/bus/ufs", ufsBusCheck.lines().firstOrNull() ?: "exists")
            }

            // ========== 优先级 7：检查 SCSI 设备的 vendor/model 信息 ==========
            if (mainDevice.startsWith("sd")) {
                val vendor = executeShellCommand("cat /sys/block/$mainDevice/device/vendor 2>/dev/null").trim()
                val model = executeShellCommand("cat /sys/block/$mainDevice/device/model 2>/dev/null").trim()

                Log.d(TAG, "SCSI device info: vendor='$vendor', model='$model'")

                // 检查是否是 USB 存储
                val removable = executeShellCommand("cat /sys/block/$mainDevice/removable 2>/dev/null").trim()
                if (removable == "1") {
                    Log.d(TAG, "Device is removable, likely USB storage")
                    return DetectionResult(StorageType.UNKNOWN, "SCSI (可移动)", "vendor=$vendor, removable=1")
                }

                // 已知的 UFS vendor
                val ufsVendors = listOf("SAMSUNG", "SKhynix", "KIOXIA", "WDC", "TOSHIBA", "MICRON", "SANDISK")
                val vendorUpper = vendor.uppercase()
                if (ufsVendors.any { vendorUpper.contains(it) }) {
                    Log.d(TAG, "UFS likely based on vendor: $vendor")
                    return DetectionResult(StorageType.UFS, "SCSI vendor", "vendor=$vendor, model=$model")
                }
            }

            // ========== 优先级 8（最后）：sd* 设备但无法确认类型 ==========
            if (mainDevice.startsWith("sd")) {
                Log.d(TAG, "sd* device but cannot confirm UFS, marking as UNKNOWN")
                return DetectionResult(StorageType.UNKNOWN, "SCSI (未确认)", "sd* 设备，无法确认是 UFS 还是 USB 存储")
            }

            // ========== 回退：NonRootStorageInfo 检测 ==========
            Log.d(TAG, "Falling back to NonRootStorageInfo detection")
            val fallbackType = NonRootStorageInfo.detectStorageType()
            DetectionResult(fallbackType, "NonRoot 回退", "无法通过 Shizuku 检测")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to detect storage type via Shizuku: ${e.message}")
            DetectionResult(NonRootStorageInfo.detectStorageType(), "异常回退", e.message ?: "unknown error")
        }
    }

    private fun parseLifeTime(lifeTime: String): Int {
        return try {
            val values = lifeTime.split(Regex("\\s+"))
                .mapNotNull { it.removePrefix("0x").toIntOrNull(16) }

            if (values.isNotEmpty()) {
                val maxUsage = values.maxOrNull() ?: 0
                when {
                    maxUsage == 0 -> 100
                    maxUsage in 1..10 -> 100 - (maxUsage * 10)
                    maxUsage == 11 -> 0  // 超过预期寿命
                    else -> -1
                }
            } else {
                -1
            }
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * 估算健康度（返回精确 Double 值）
     */
    private fun estimateHealthFromWritesExact(
        bytesWritten: Long,
        totalCapacity: Long,
        storageType: StorageType
    ): Double {
        val peCycles = when (storageType) {
            StorageType.EMMC -> 3000
            StorageType.UFS -> 3000
            StorageType.NVME -> 600
            else -> 1000
        }
        val writeAmplification = 2.0
        val estimatedTBW = (totalCapacity * peCycles / writeAmplification)

        return if (estimatedTBW > 0) {
            ((1 - bytesWritten.toDouble() / estimatedTBW) * 100).coerceIn(0.0, 100.0)
        } else {
            -1.0
        }
    }

    private fun buildModelDescription(
        type: StorageType,
        deviceModel: String?,
        deviceName: String?,
        dumpsysInfo: DumpsysDiskInfo?
    ): String {
        val parts = mutableListOf<String>()

        parts.add(
            when (type) {
                StorageType.EMMC -> "eMMC"
                StorageType.UFS -> "UFS"
                StorageType.NVME -> "NVMe"
                StorageType.UNKNOWN -> "Flash Storage"
            }
        )

        val model = deviceModel?.takeIf { it.isNotBlank() }
        val name = deviceName?.takeIf { it.isNotBlank() }

        if (!model.isNullOrBlank() && model != "Unknown") {
            parts.add(model)
        } else if (!name.isNullOrBlank()) {
            parts.add(name)
        }

        if (dumpsysInfo?.fsType != null && dumpsysInfo.fsType != "unknown") {
            parts.add("(${dumpsysInfo.fsType})")
        }

        parts.add("[Shizuku]")
        return parts.joinToString(" ")
    }

    private fun buildFirmwareDescription(dumpsysInfo: DumpsysDiskInfo?): String {
        val info = mutableListOf<String>()
        info.add("Android ${android.os.Build.VERSION.RELEASE}")
        info.add("API ${android.os.Build.VERSION.SDK_INT}")
        if (dumpsysInfo?.fsType != null && dumpsysInfo.fsType != "unknown") {
            info.add(dumpsysInfo.fsType.uppercase())
        }
        info.add("Shizuku")
        return info.joinToString(" | ")
    }

    private data class DumpsysDiskInfo(val totalWrites: Long, val fsType: String)
    private data class StoragedStats(val writeBytes: Long, val readBytes: Long)
    private data class DiskStat(val device: String, val bytesWritten: Long)

    /**
     * 存储类型检测结果
     */
    private data class DetectionResult(
        val type: StorageType,
        val method: String,
        val details: String = ""
    )
}
