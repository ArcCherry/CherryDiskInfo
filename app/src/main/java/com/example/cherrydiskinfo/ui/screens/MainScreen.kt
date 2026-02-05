package com.example.cherrydiskinfo.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.cherrydiskinfo.data.model.*
import com.example.cherrydiskinfo.ui.theme.*
import com.example.cherrydiskinfo.util.NonRootStorageInfo
import com.example.cherrydiskinfo.util.StringUtils
import com.example.cherrydiskinfo.util.export.DiagnosticReportExporter
import com.example.cherrydiskinfo.viewmodel.ShizukuStatus
import com.example.cherrydiskinfo.viewmodel.StorageViewModel
import com.example.cherrydiskinfo.viewmodel.StorageViewModelFactory
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onNavigateToAbout: () -> Unit = {},
    viewModel: StorageViewModel = viewModel(
        factory = StorageViewModelFactory(
            LocalContext.current.applicationContext as android.app.Application
        )
    )
) {
    val storageInfo by viewModel.storageInfo.collectAsStateWithLifecycle()
    val rootStatus by viewModel.rootStatus.collectAsStateWithLifecycle()
    val dataSources by viewModel.dataSources.collectAsStateWithLifecycle()
    val shizukuStatus by viewModel.shizukuStatus.collectAsStateWithLifecycle()
    var showMenu by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // 导出结果 Snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Cherry Disk Info") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                ),
                actions = {
                    IconButton(onClick = { viewModel.loadStorageInfo() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "菜单",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("导出报告") },
                            leadingIcon = { Icon(Icons.Default.Share, null) },
                            onClick = {
                                showMenu = false
                                showExportDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("诊断信息") },
                            leadingIcon = { Icon(Icons.Default.Build, null) },
                            onClick = {
                                showMenu = false
                                showDiagnostics = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("关于") },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = {
                                showMenu = false
                                onNavigateToAbout()
                            }
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Root 状态提示
            RootStatusCard(rootStatus = rootStatus)

            // Shizuku 状态卡片
            ShizukuStatusCard(
                status = shizukuStatus,
                onRequestPermission = { viewModel.requestShizukuPermission() },
                onRefresh = {
                    viewModel.checkShizukuStatus()
                    viewModel.refreshDataSources()
                    viewModel.loadStorageInfo()
                }
            )

            // 存储信息展示
            when (val result = storageInfo) {
                is StorageInfoResult.Loading -> {
                    LoadingCard()
                }
                is StorageInfoResult.Error -> {
                    ErrorCard(message = result.message)
                }
                is StorageInfoResult.Success -> {
                    StorageInfoCard(
                        info = result.storageInfo,
                        getHealthColor = { viewModel.getHealthColor(it) }
                    )
                }
            }
            
            // 数据源信息
            DataSourceCard(dataSources = dataSources)
        }
    }
    
    // 诊断信息对话框
    if (showDiagnostics) {
        val currentStorageInfo = (storageInfo as? StorageInfoResult.Success)?.storageInfo
        DiagnosticsDialog(
            context = context,
            storageInfo = currentStorageInfo,
            onDismiss = { showDiagnostics = false }
        )
    }
    
    // 导出对话框
    if (showExportDialog) {
        ExportDialog(
            onDismiss = { showExportDialog = false },
            onExport = { format, shouldShare ->
                scope.launch {
                    val result = DiagnosticReportExporter.export(context, format)
                    when (result) {
                        is DiagnosticReportExporter.ExportResult.Success -> {
                            snackbarHostState.showSnackbar(
                                "报告已保存: ${result.filePath}",
                                actionLabel = if (shouldShare) "分享" else null
                            )
                            if (shouldShare) {
                                DiagnosticReportExporter.shareReport(context, result.uri, format)
                            }
                        }
                        is DiagnosticReportExporter.ExportResult.Error -> {
                            snackbarHostState.showSnackbar("导出失败: ${result.message}")
                        }
                    }
                }
                showExportDialog = false
            }
        )
    }
}

@Composable
private fun DiagnosticsDialog(
    context: Context,
    storageInfo: StorageInfo?,
    onDismiss: () -> Unit
) {
    var diagnosis by remember { mutableStateOf<NonRootStorageInfo.DiagnosisInfo?>(null) }
    var diskStats by remember { mutableStateOf<List<NonRootStorageInfo.DiskStat>>(emptyList()) }
    var fsStats by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var dumpsysInfo by remember { mutableStateOf<NonRootStorageInfo.DumpsysDiskInfo?>(null) }
    var xiaomiInfo by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var storageTypeDebug by remember { mutableStateOf<NonRootStorageInfo.StorageTypeDebugInfo?>(null) }

    LaunchedEffect(Unit) {
        diagnosis = NonRootStorageInfo.diagnose(context)
        diskStats = NonRootStorageInfo.readDiskStats()
        fsStats = NonRootStorageInfo.readFsStats()
        dumpsysInfo = NonRootStorageInfo.getDumpsysDiskStats()
        xiaomiInfo = NonRootStorageInfo.getXiaomiStorageInfo()
        storageTypeDebug = NonRootStorageInfo.getStorageTypeDebugInfo()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("诊断信息") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ========== 存储类型检测（最重要，放在最前面） ==========
                Text("存储类型检测", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

                // 显示 Shizuku 检测结果（如果有）
                storageInfo?.let { info ->
                    Text(
                        "当前检测结果: ${StringUtils.getStorageTypeName(info.type)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (info.type != StorageType.UNKNOWN) HealthGood else HealthCaution
                    )
                    if (info.detectionMethod.isNotBlank()) {
                        info.detectionMethod.lines().forEach { line ->
                            Text(line, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // NonRoot 检测信息（作为补充）
                storageTypeDebug?.let { debug ->
                    if (storageInfo?.detectionMethod.isNullOrBlank()) {
                        Text("主设备: ${debug.mainDevice.ifBlank { "未检测到" }}", style = MaterialTheme.typography.bodySmall)
                        Text("检测结果: ${debug.detectedType}", style = MaterialTheme.typography.bodySmall)
                        Text("检测方式: ${debug.detectionMethod}", style = MaterialTheme.typography.bodySmall)
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                diagnosis?.let { diag ->
                    Text("系统访问能力", style = MaterialTheme.typography.titleSmall)
                    DiagnosticItem("/proc/diskstats 可读", diag.diskStatsReadable)
                    DiagnosticItem("/sys/block 可读", diag.sysBlockReadable)
                    DiagnosticItem("/proc/partitions 可读", diag.procPartitionsReadable)
                    DiagnosticItem("dumpsys 可用", diag.dumpsysAvailable)
                    DiagnosticItem("StorageManager 可用", diag.storageManagerAvailable)

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("设备信息", style = MaterialTheme.typography.titleSmall)
                    diag.buildProps.forEach { (key, value) ->
                        Text("$key: $value", style = MaterialTheme.typography.bodySmall)
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("磁盘统计 (${diskStats.size} 设备)", style = MaterialTheme.typography.titleSmall)
                    if (diskStats.isEmpty()) {
                        Text("无法读取 /proc/diskstats", style = MaterialTheme.typography.bodySmall, color = HealthBad)
                    } else {
                        diskStats.forEach { stat ->
                            Text(
                                "${stat.device}: 写入 ${formatBytes(stat.bytesWritten)}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    Text("Dumpsys 信息", style = MaterialTheme.typography.titleSmall)
                    if (dumpsysInfo == null) {
                        Text("无法获取 dumpsys 信息", style = MaterialTheme.typography.bodySmall, color = HealthBad)
                    } else {
                        Text("文件系统: ${dumpsysInfo?.fsType}", style = MaterialTheme.typography.bodySmall)
                        Text("总写入: ${dumpsysInfo?.totalWrites}", style = MaterialTheme.typography.bodySmall)
                    }

                    if (fsStats.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("文件系统统计 (${fsStats.size} 项)", style = MaterialTheme.typography.titleSmall)
                        fsStats.entries.take(5).forEach { (path, value) ->
                            Text("$path: ${value.take(30)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }

                    if (xiaomiInfo.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text("小米特定信息", style = MaterialTheme.typography.titleSmall)
                        xiaomiInfo.forEach { (key, value) ->
                            Text("$key: ${value.take(50)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } ?: run {
                    CircularProgressIndicator()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (DiagnosticReportExporter.ExportFormat, Boolean) -> Unit
) {
    var selectedFormat by remember { mutableStateOf(DiagnosticReportExporter.ExportFormat.MARKDOWN) }
    var shouldShare by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("导出诊断报告") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("选择导出格式：")
                
                // 格式选择
                Column {
                    ExportFormatOption(
                        text = "Markdown (.md)",
                        selected = selectedFormat == DiagnosticReportExporter.ExportFormat.MARKDOWN,
                        onClick = { selectedFormat = DiagnosticReportExporter.ExportFormat.MARKDOWN }
                    )
                    ExportFormatOption(
                        text = "JSON (.json)",
                        selected = selectedFormat == DiagnosticReportExporter.ExportFormat.JSON,
                        onClick = { selectedFormat = DiagnosticReportExporter.ExportFormat.JSON }
                    )
                    ExportFormatOption(
                        text = "纯文本 (.txt)",
                        selected = selectedFormat == DiagnosticReportExporter.ExportFormat.TXT,
                        onClick = { selectedFormat = DiagnosticReportExporter.ExportFormat.TXT }
                    )
                }
                
                HorizontalDivider()
                
                // 分享选项
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Checkbox(
                        checked = shouldShare,
                        onCheckedChange = { shouldShare = it }
                    )
                    Text("导出后分享")
                }
            }
        },
        confirmButton = {
            Button(onClick = { onExport(selectedFormat, shouldShare) }) {
                Text("导出")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun ExportFormatOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(text)
    }
}

@Composable
private fun DiagnosticItem(label: String, value: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            if (value) "✓" else "✗",
            color = if (value) HealthGood else HealthBad,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024L * 1024 * 1024 * 1024 -> String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024))
        bytes >= 1024L * 1024 * 1024 -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024))
        bytes >= 1024L -> String.format("%.2f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

@Composable
private fun RootStatusCard(rootStatus: RootStatus) {
    val (icon, color, title, message) = when (rootStatus) {
        RootStatus.GRANTED -> Quadruple(
            Icons.Default.Info,
            HealthGood,
            "已获取 Root 权限",
            "可以读取完整的 eMMC/UFS 存储健康信息"
        )
        RootStatus.DENIED -> Quadruple(
            Icons.Default.Info,
            HealthGood,
            "非 Root 模式运行中",
            "通过系统 API 估算存储信息，部分数据可能不够精确"
        )
        RootStatus.UNKNOWN -> Quadruple(
            Icons.Default.Warning,
            HealthUnknown,
            "正在检测权限...",
            "请稍候"
        )
    }
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = color.copy(alpha = 0.8f)
                )
            }
        }
    }
}

// 辅助类用于返回四个值
data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
private fun LoadingCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "正在获取存储信息...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = HealthBad.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = HealthBad,
                modifier = Modifier.size(48.dp)
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = HealthBad,
                textAlign = TextAlign.Center
            )
            Text(
                text = "提示：使用 Root 权限可以获得更详细的存储健康信息",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StorageInfoCard(
    info: StorageInfo,
    getHealthColor: (HealthStatus) -> androidx.compose.ui.graphics.Color
) {
    // 判断是否是非 Root 估算数据
    val isEstimated = info.healthPercentage < 0 || info.totalBytesWritten <= 0
    
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 设备基本信息
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (info.model != "Unknown") info.model else StringUtils.getStorageTypeName(info.type),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "${StringUtils.getStorageTypeName(info.type)} · ${info.getFormattedCapacity()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (info.model != "Unknown") {
                        Text(
                            text = StringUtils.getStorageTypeDescription(info.type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (isEstimated) {
                        Text(
                            text = "⚠ 估算模式：部分数据基于系统特征推断",
                            style = MaterialTheme.typography.bodySmall,
                            color = HealthCaution
                        )
                    }
                }
                
                // 健康度圆形指示器
                HealthIndicator(
                    percentage = info.healthPercentage,
                    status = info.healthStatus,
                    color = getHealthColor(info.healthStatus)
                )
            }
            
            HorizontalDivider()
            
            // 健康建议
            if (info.healthPercentage >= 0) {
                Text(
                    text = StringUtils.getHealthAdvice(info.healthPercentage),
                    style = MaterialTheme.typography.bodySmall,
                    color = getHealthColor(info.healthStatus),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                HorizontalDivider()
            }
            
            // 详细信息网格
            InfoGrid(info = info)
        }
    }
}

@Composable
private fun HealthIndicator(
    percentage: Int,
    status: HealthStatus,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                progress = { if (percentage >= 0) percentage / 100f else 0f },
                modifier = Modifier.size(72.dp),
                color = color,
                strokeWidth = 8.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (percentage >= 0) "$percentage%" else "N/A",
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = StringUtils.getHealthStatusName(status),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun InfoGrid(info: StorageInfo) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 设备标识信息
        InfoRow("设备路径", info.name)
        InfoRow("固件版本", info.firmwareVersion)
        InfoRow("序列号", if (info.serialNumber != "Unknown" && !info.serialNumber.contains("需 Root")) info.serialNumber else "读取受限")
        
        HorizontalDivider()
        
        // ===== 容量信息（重点显示） =====
        Text(
            text = "存储容量",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        
        // 总容量 - 大字体突出显示
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = info.getFormattedCapacity(),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "总容量",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 容量使用进度条
        val usedPercent = info.getUsedPercentage()
        if (usedPercent > 0) {
            LinearProgressIndicator(
                progress = { usedPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = when {
                    usedPercent >= 90 -> HealthBad
                    usedPercent >= 70 -> HealthCaution
                    else -> HealthGood
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        
        // 详细容量信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            CapacityItem("已使用", info.getFormattedUsedCapacity(), usedPercent)
            CapacityItem("可用", info.getFormattedAvailableCapacity(), null)
            if (info.totalBytesWritten > 0) {
                CapacityItem("总写入", "~${info.getFormattedBytesWritten()}", null)
            }
        }
        
        HorizontalDivider()
        
        // 使用情况统计
        if (info.totalBytesWritten > 0) {
            InfoRow("总写入量 (估算)", "~${info.getFormattedBytesWritten()}")
        }
        
        // 通电时间
        if (info.powerOnHours > 0) {
            InfoRow("通电时间", StringUtils.formatHours(info.powerOnHours))
        }
        
        // 通电次数
        if (info.powerCycleCount > 0) {
            InfoRow("通电次数", StringUtils.formatCount(info.powerCycleCount))
        }
        
        HorizontalDivider()
        
        // 健康相关
        if (info.temperature >= 0) {
            InfoRow("当前温度", StringUtils.formatTemperature(info.temperature))
        }
        
        // 磨损水平（显示精确小数）
        if (info.healthPercentageExact >= 0) {
            InfoRow("磨损水平", String.format("%.2f%%", info.healthPercentageExact))
        } else if (info.wearLevel >= 0) {
            InfoRow("磨损水平", "${info.wearLevel}%")
        } else {
            InfoRow("磨损水平", "需 Root 读取寿命计数器")
        }

        // 预估剩余寿命（显示精确小数）
        if (info.healthPercentageExact >= 0) {
            InfoRow("预估剩余寿命", String.format("%.2f%%", info.healthPercentageExact))
        } else if (info.estimatedLifePercent >= 0) {
            InfoRow("预估剩余寿命", "${info.estimatedLifePercent}%")
        } else {
            InfoRow("预估剩余寿命", "无法估算 (需写入统计)")
        }
        
        // 预估 TBW
        val estimatedTBW = info.getEstimatedTBW()
        if (estimatedTBW > 0) {
            InfoRow("设计写入量(TBW)", "~${estimatedTBW} TB (估算)")
        }
    }
}

@Composable
private fun CapacityItem(label: String, value: String, percentage: Int?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        percentage?.let {
            Text(
                text = "$it%",
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    it >= 90 -> HealthBad
                    it >= 70 -> HealthCaution
                    else -> HealthGood
                }
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
        )
    }
}

@Composable
private fun DataSourceCard(dataSources: List<DataSourceInfo>) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "数据源",
                style = MaterialTheme.typography.titleMedium
            )
            
            dataSources.forEach { source ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = getDataSourceTypeName(source.type),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = source.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusChip(isAvailable = source.isAvailable)
                }
            }
        }
    }
}

@Composable
private fun getDataSourceTypeName(type: DataSourceType): String {
    return when (type) {
        DataSourceType.ROOT -> "Root 权限"
        DataSourceType.SHIZUKU -> "Shizuku"
        DataSourceType.SYSTEM_API -> "系统 API (Android 15+)"
        DataSourceType.ADB -> "ADB 调试"
        DataSourceType.ESTIMATED -> "系统估算"
    }
}

@Composable
private fun StatusChip(isAvailable: Boolean) {
    val color = if (isAvailable) HealthGood else HealthUnknown
    val text = if (isAvailable) "可用" else "不可用"

    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ShizukuStatusCard(
    status: ShizukuStatus,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit
) {
    val (icon, color, title, message, showButton) = when (status) {
        ShizukuStatus.AVAILABLE -> Quintuple(
            Icons.Default.Info,
            HealthGood,
            "Shizuku 已授权",
            "可通过 Shizuku 获取更多存储信息",
            false
        )
        ShizukuStatus.PERMISSION_DENIED -> Quintuple(
            Icons.Default.Warning,
            HealthCaution,
            "Shizuku 未授权",
            "点击授权按钮以获取更详细的存储信息",
            true
        )
        ShizukuStatus.NOT_INSTALLED -> Quintuple(
            Icons.Default.Info,
            HealthUnknown,
            "Shizuku 未运行",
            "安装并启动 Shizuku 可获取更多存储信息（无需 Root）",
            false
        )
        ShizukuStatus.CHECKING -> Quintuple(
            Icons.Default.Info,
            HealthUnknown,
            "检查 Shizuku 状态...",
            "请稍候",
            false
        )
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(24.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                    )
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = color.copy(alpha = 0.8f)
                    )
                }
            }

            if (showButton) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onRefresh) {
                        Text("刷新状态")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onRequestPermission,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = color
                        )
                    ) {
                        Text("授权 Shizuku")
                    }
                }
            }
        }
    }
}

// 辅助类用于返回五个值
private data class Quintuple<A, B, C, D, E>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E
)
