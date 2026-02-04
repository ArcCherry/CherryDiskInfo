package com.example.cherrydiskinfo.util.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import com.example.cherrydiskinfo.util.NonRootStorageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断报告导出器
 */
object DiagnosticReportExporter {
    
    private const val TAG = "DiagnosticReportExporter"
    private const val REPORTS_DIR = "CherryDiskInfo"
    private const val REPORT_PREFIX = "diagnostic_report_"
    
    /**
     * 导出格式
     */
    enum class ExportFormat {
        JSON,
        MARKDOWN,
        TXT
    }
    
    /**
     * 导出结果
     */
    sealed class ExportResult {
        data class Success(val uri: Uri, val filePath: String) : ExportResult()
        data class Error(val message: String) : ExportResult()
    }
    
    /**
     * 导出诊断报告
     * @param context 上下文
     * @param format 导出格式
     * @return 导出结果
     */
    suspend fun export(context: Context, format: ExportFormat = ExportFormat.MARKDOWN): ExportResult = 
        withContext(Dispatchers.IO) {
            try {
                // 收集诊断数据
                val diagnosis = NonRootStorageInfo.diagnose(context)
                val diskStats = NonRootStorageInfo.readDiskStats()
                val partitions = NonRootStorageInfo.readPartitions()
                val sysBlockInfo = NonRootStorageInfo.readSysBlockInfo()
                val fsStats = NonRootStorageInfo.readFsStats()
                val dumpsysInfo = NonRootStorageInfo.getDumpsysDiskStats()
                val dfInfo = NonRootStorageInfo.getDfInfo()
                val xiaomiInfo = NonRootStorageInfo.getXiaomiStorageInfo()
                
                // 生成报告内容
                val reportContent = when (format) {
                    ExportFormat.JSON -> generateJsonReport(
                        diagnosis, diskStats, partitions, sysBlockInfo,
                        fsStats, dumpsysInfo, dfInfo, xiaomiInfo
                    )
                    ExportFormat.MARKDOWN -> generateMarkdownReport(
                        diagnosis, diskStats, partitions, sysBlockInfo,
                        fsStats, dumpsysInfo, dfInfo, xiaomiInfo
                    )
                    ExportFormat.TXT -> generateTxtReport(
                        diagnosis, diskStats, partitions, sysBlockInfo,
                        fsStats, dumpsysInfo, dfInfo, xiaomiInfo
                    )
                }
                
                // 保存文件
                val fileName = generateFileName(format)
                val result = saveReport(context, fileName, reportContent, format)
                
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to export report", e)
                ExportResult.Error("导出失败: ${e.message}")
            }
        }
    
    /**
     * 分享诊断报告
     */
    fun shareReport(context: Context, uri: Uri, format: ExportFormat) {
        val mimeType = when (format) {
            ExportFormat.JSON -> "application/json"
            ExportFormat.MARKDOWN -> "text/markdown"
            ExportFormat.TXT -> "text/plain"
        }
        
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cherry Disk Info 诊断报告")
            putExtra(Intent.EXTRA_TEXT, "附件是设备存储诊断报告")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(intent, "分享诊断报告"))
    }
    
    /**
     * 生成文件名
     */
    private fun generateFileName(format: ExportFormat): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val extension = when (format) {
            ExportFormat.JSON -> "json"
            ExportFormat.MARKDOWN -> "md"
            ExportFormat.TXT -> "txt"
        }
        return "${REPORT_PREFIX}${timestamp}.$extension"
    }
    
    /**
     * 保存报告到存储
     */
    private fun saveReport(
        context: Context,
        fileName: String,
        content: String,
        format: ExportFormat
    ): ExportResult {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 使用 MediaStore
            saveUsingMediaStore(context, fileName, content, format)
        } else {
            // Android 9 及以下使用传统方式
            saveToExternalStorageLegacy(context, fileName, content)
        }
    }
    
    /**
     * 使用 MediaStore 保存（Android 10+）
     */
    private fun saveUsingMediaStore(
        context: Context,
        fileName: String,
        content: String,
        format: ExportFormat
    ): ExportResult {
        val mimeType = when (format) {
            ExportFormat.JSON -> "application/json"
            ExportFormat.MARKDOWN -> "text/markdown"
            ExportFormat.TXT -> "text/plain"
        }
        
        val values = android.content.ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeType)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + REPORTS_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return ExportResult.Error("无法创建文件")
        
        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
            }
            
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            
            ExportResult.Success(uri, "Downloads/$REPORTS_DIR/$fileName")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            ExportResult.Error("写入文件失败: ${e.message}")
        }
    }
    
    /**
     * 传统方式保存到外部存储（Android 9 及以下）
     */
    private fun saveToExternalStorageLegacy(
        context: Context,
        fileName: String,
        content: String
    ): ExportResult {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val appDir = File(downloadsDir, REPORTS_DIR)
        
        if (!appDir.exists()) {
            appDir.mkdirs()
        }
        
        val file = File(appDir, fileName)
        
        return try {
            FileWriter(file).use { writer ->
                writer.write(content)
            }
            
            // 通知系统扫描新文件
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            ExportResult.Success(uri, file.absolutePath)
        } catch (e: Exception) {
            ExportResult.Error("写入文件失败: ${e.message}")
        }
    }
    
    /**
     * 生成 Markdown 格式报告
     */
    private fun generateMarkdownReport(
        diagnosis: NonRootStorageInfo.DiagnosisInfo,
        diskStats: List<NonRootStorageInfo.DiskStat>,
        partitions: List<NonRootStorageInfo.PartitionInfo>,
        sysBlockInfo: List<NonRootStorageInfo.SysBlockInfo>,
        fsStats: Map<String, String>,
        dumpsysInfo: NonRootStorageInfo.DumpsysDiskInfo?,
        dfInfo: List<NonRootStorageInfo.DfEntry>,
        xiaomiInfo: Map<String, String>
    ): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        sb.appendLine("# Cherry Disk Info 诊断报告")
        sb.appendLine()
        sb.appendLine("**生成时间**: $timestamp")
        sb.appendLine()
        
        // 系统访问能力
        sb.appendLine("## 系统访问能力")
        sb.appendLine()
        sb.appendLine("| 项目 | 状态 |")
        sb.appendLine("|------|------|")
        sb.appendLine("| /proc/diskstats 可读 | ${if (diagnosis.diskStatsReadable) "✓" else "✗"} |")
        sb.appendLine("| /sys/block 可读 | ${if (diagnosis.sysBlockReadable) "✓" else "✗"} |")
        sb.appendLine("| /proc/partitions 可读 | ${if (diagnosis.procPartitionsReadable) "✓" else "✗"} |")
        sb.appendLine("| dumpsys 可用 | ${if (diagnosis.dumpsysAvailable) "✓" else "✗"} |")
        sb.appendLine("| StorageManager 可用 | ${if (diagnosis.storageManagerAvailable) "✓" else "✗"} |")
        sb.appendLine()
        
        // 设备信息
        sb.appendLine("## 设备信息")
        sb.appendLine()
        sb.appendLine("```")
        diagnosis.buildProps.forEach { (key, value) ->
            sb.appendLine("$key: $value")
        }
        sb.appendLine("```")
        sb.appendLine()
        
        // 磁盘统计
        sb.appendLine("## 磁盘统计 (${diskStats.size} 设备)")
        sb.appendLine()
        if (diskStats.isEmpty()) {
            sb.appendLine("> ⚠️ 无法读取 /proc/diskstats")
        } else {
            sb.appendLine("| 设备 | 写入次数 | 写入扇区 | 写入字节 |")
            sb.appendLine("|------|----------|----------|----------|")
            diskStats.forEach { stat ->
                sb.appendLine("| ${stat.device} | ${stat.writesCompleted} | ${stat.sectorsWritten} | ${formatBytes(stat.bytesWritten)} |")
            }
        }
        sb.appendLine()
        
        // Dumpsys 信息
        sb.appendLine("## Dumpsys 信息")
        sb.appendLine()
        if (dumpsysInfo == null) {
            sb.appendLine("> ⚠️ 无法获取 dumpsys diskstats 信息")
        } else {
            sb.appendLine("- **文件系统类型**: ${dumpsysInfo.fsType}")
            sb.appendLine("- **总写入量**: ${dumpsysInfo.totalWrites}")
        }
        sb.appendLine()
        
        // 分区信息
        sb.appendLine("## 分区信息 (${partitions.size} 个)")
        sb.appendLine()
        if (partitions.isNotEmpty()) {
            sb.appendLine("| 主设备号 | 次设备号 | 块数 | 名称 |")
            sb.appendLine("|----------|----------|------|------|")
            partitions.take(20).forEach { part ->
                sb.appendLine("| ${part.major} | ${part.minor} | ${part.blocks} | ${part.name} |")
            }
            if (partitions.size > 20) {
                sb.appendLine("| ... | ... | ... | 还有 ${partitions.size - 20} 个分区 |")
            }
        }
        sb.appendLine()
        
        // 块设备信息
        sb.appendLine("## 块设备信息 (${sysBlockInfo.size} 个)")
        sb.appendLine()
        if (sysBlockInfo.isNotEmpty()) {
            sb.appendLine("| 名称 | 大小 | 型号 | 可移除 |")
            sb.appendLine("|------|------|------|--------|")
            sysBlockInfo.forEach { info ->
                sb.appendLine("| ${info.name} | ${formatBytes(info.size)} | ${info.model} | ${if (info.isRemovable) "是" else "否"} |")
            }
        }
        sb.appendLine()
        
        // df 信息
        sb.appendLine("## 磁盘使用情况")
        sb.appendLine()
        if (dfInfo.isNotEmpty()) {
            sb.appendLine("| 文件系统 | 大小 | 已用 | 可用 | 使用率 | 挂载点 |")
            sb.appendLine("|----------|------|------|------|--------|--------|")
            dfInfo.forEach { entry ->
                sb.appendLine("| ${entry.filesystem} | ${entry.size} | ${entry.used} | ${entry.available} | ${entry.usePercent} | ${entry.mountedOn} |")
            }
        }
        sb.appendLine()
        
        // 小米特定信息
        if (xiaomiInfo.isNotEmpty()) {
            sb.appendLine("## 小米特定信息")
            sb.appendLine()
            sb.appendLine("```")
            xiaomiInfo.forEach { (key, value) ->
                sb.appendLine("$key: $value")
            }
            sb.appendLine("```")
            sb.appendLine()
        }
        
        // 文件系统统计
        if (fsStats.isNotEmpty()) {
            sb.appendLine("## 文件系统统计")
            sb.appendLine()
            fsStats.forEach { (path, value) ->
                sb.appendLine("### $path")
                sb.appendLine("```")
                sb.appendLine(value)
                sb.appendLine("```")
                sb.appendLine()
            }
        }
        
        // 结尾
        sb.appendLine("---")
        sb.appendLine()
        sb.appendLine("*由 Cherry Disk Info 生成*")
        
        return sb.toString()
    }
    
    /**
     * 生成 JSON 格式报告
     */
    private fun generateJsonReport(
        diagnosis: NonRootStorageInfo.DiagnosisInfo,
        diskStats: List<NonRootStorageInfo.DiskStat>,
        partitions: List<NonRootStorageInfo.PartitionInfo>,
        sysBlockInfo: List<NonRootStorageInfo.SysBlockInfo>,
        fsStats: Map<String, String>,
        dumpsysInfo: NonRootStorageInfo.DumpsysDiskInfo?,
        dfInfo: List<NonRootStorageInfo.DfEntry>,
        xiaomiInfo: Map<String, String>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"timestamp\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\",")
        sb.appendLine("  \"diagnosis\": {")
        sb.appendLine("    \"diskStatsReadable\": ${diagnosis.diskStatsReadable},")
        sb.appendLine("    \"sysBlockReadable\": ${diagnosis.sysBlockReadable},")
        sb.appendLine("    \"procPartitionsReadable\": ${diagnosis.procPartitionsReadable},")
        sb.appendLine("    \"dumpsysAvailable\": ${diagnosis.dumpsysAvailable},")
        sb.appendLine("    \"storageManagerAvailable\": ${diagnosis.storageManagerAvailable},")
        sb.appendLine("    \"buildProps\": {")
        diagnosis.buildProps.entries.forEachIndexed { index, entry ->
            val comma = if (index < diagnosis.buildProps.size - 1) "," else ""
            sb.appendLine("      \"${entry.key}\": \"${entry.value.replace("\"", "\\\"")}\"$comma")
        }
        sb.appendLine("    }")
        sb.appendLine("  },")
        sb.appendLine("  \"diskStats\": [")
        diskStats.forEachIndexed { index, stat ->
            val comma = if (index < diskStats.size - 1) "," else ""
            sb.appendLine("    {")
            sb.appendLine("      \"device\": \"${stat.device}\",")
            sb.appendLine("      \"readsCompleted\": ${stat.readsCompleted},")
            sb.appendLine("      \"sectorsRead\": ${stat.sectorsRead},")
            sb.appendLine("      \"writesCompleted\": ${stat.writesCompleted},")
            sb.appendLine("      \"sectorsWritten\": ${stat.sectorsWritten}")
            sb.appendLine("    }$comma")
        }
        sb.appendLine("  ],")
        sb.appendLine("  \"partitions\": [")
        partitions.forEachIndexed { index, part ->
            val comma = if (index < partitions.size - 1) "," else ""
            sb.appendLine("    {")
            sb.appendLine("      \"major\": ${part.major},")
            sb.appendLine("      \"minor\": ${part.minor},")
            sb.appendLine("      \"blocks\": ${part.blocks},")
            sb.appendLine("      \"name\": \"${part.name}\"")
            sb.appendLine("    }$comma")
        }
        sb.appendLine("  ],")
        sb.appendLine("  \"sysBlockInfo\": [")
        sysBlockInfo.forEachIndexed { index, info ->
            val comma = if (index < sysBlockInfo.size - 1) "," else ""
            sb.appendLine("    {")
            sb.appendLine("      \"name\": \"${info.name}\",")
            sb.appendLine("      \"size\": ${info.size},")
            sb.appendLine("      \"model\": \"${info.model}\",")
            sb.appendLine("      \"isRemovable\": ${info.isRemovable}")
            sb.appendLine("    }$comma")
        }
        sb.appendLine("  ]")
        
        if (dumpsysInfo != null) {
            sb.appendLine(",")
            sb.appendLine("  \"dumpsysInfo\": {")
            sb.appendLine("    \"totalWrites\": ${dumpsysInfo.totalWrites},")
            sb.appendLine("    \"fsType\": \"${dumpsysInfo.fsType}\"")
            sb.appendLine("  }")
        }
        
        if (xiaomiInfo.isNotEmpty()) {
            sb.appendLine(",")
            sb.appendLine("  \"xiaomiInfo\": {")
            xiaomiInfo.entries.forEachIndexed { index, entry ->
                val comma = if (index < xiaomiInfo.size - 1) "," else ""
                sb.appendLine("    \"${entry.key}\": \"${entry.value.replace("\"", "\\\"")}\"$comma")
            }
            sb.appendLine("  }")
        }
        
        sb.appendLine("}")
        return sb.toString()
    }
    
    /**
     * 生成纯文本格式报告
     */
    private fun generateTxtReport(
        diagnosis: NonRootStorageInfo.DiagnosisInfo,
        diskStats: List<NonRootStorageInfo.DiskStat>,
        partitions: List<NonRootStorageInfo.PartitionInfo>,
        sysBlockInfo: List<NonRootStorageInfo.SysBlockInfo>,
        fsStats: Map<String, String>,
        dumpsysInfo: NonRootStorageInfo.DumpsysDiskInfo?,
        dfInfo: List<NonRootStorageInfo.DfEntry>,
        xiaomiInfo: Map<String, String>
    ): String {
        val sb = StringBuilder()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        
        sb.appendLine("=".repeat(50))
        sb.appendLine("Cherry Disk Info 诊断报告")
        sb.appendLine("=".repeat(50))
        sb.appendLine()
        sb.appendLine("生成时间: $timestamp")
        sb.appendLine()
        
        sb.appendLine("-".repeat(50))
        sb.appendLine("系统访问能力")
        sb.appendLine("-".repeat(50))
        sb.appendLine("/proc/diskstats 可读: ${if (diagnosis.diskStatsReadable) "是" else "否"}")
        sb.appendLine("/sys/block 可读: ${if (diagnosis.sysBlockReadable) "是" else "否"}")
        sb.appendLine("/proc/partitions 可读: ${if (diagnosis.procPartitionsReadable) "是" else "否"}")
        sb.appendLine("dumpsys 可用: ${if (diagnosis.dumpsysAvailable) "是" else "否"}")
        sb.appendLine("StorageManager 可用: ${if (diagnosis.storageManagerAvailable) "是" else "否"}")
        sb.appendLine()
        
        sb.appendLine("-".repeat(50))
        sb.appendLine("设备信息")
        sb.appendLine("-".repeat(50))
        diagnosis.buildProps.forEach { (key, value) ->
            sb.appendLine("$key: $value")
        }
        sb.appendLine()
        
        sb.appendLine("-".repeat(50))
        sb.appendLine("磁盘统计 (${diskStats.size} 设备)")
        sb.appendLine("-".repeat(50))
        if (diskStats.isEmpty()) {
            sb.appendLine("无法读取 /proc/diskstats")
        } else {
            diskStats.forEach { stat ->
                sb.appendLine("设备: ${stat.device}")
                sb.appendLine("  写入次数: ${stat.writesCompleted}")
                sb.appendLine("  写入字节: ${formatBytes(stat.bytesWritten)}")
                sb.appendLine()
            }
        }
        
        sb.appendLine("-".repeat(50))
        sb.appendLine("Dumpsys 信息")
        sb.appendLine("-".repeat(50))
        if (dumpsysInfo == null) {
            sb.appendLine("无法获取 dumpsys diskstats 信息")
        } else {
            sb.appendLine("文件系统类型: ${dumpsysInfo.fsType}")
            sb.appendLine("总写入量: ${dumpsysInfo.totalWrites}")
        }
        sb.appendLine()
        
        if (xiaomiInfo.isNotEmpty()) {
            sb.appendLine("-".repeat(50))
            sb.appendLine("小米特定信息")
            sb.appendLine("-".repeat(50))
            xiaomiInfo.forEach { (key, value) ->
                sb.appendLine("$key: $value")
            }
            sb.appendLine()
        }
        
        sb.appendLine("=".repeat(50))
        sb.appendLine("由 Cherry Disk Info 生成")
        sb.appendLine("=".repeat(50))
        
        return sb.toString()
    }
    
    /**
     * 格式化字节数
     */
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 * 1024 -> String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
            bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
