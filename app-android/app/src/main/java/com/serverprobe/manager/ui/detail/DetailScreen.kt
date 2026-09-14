package com.serverprobe.manager.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.probe.ProbeService
import com.serverprobe.manager.data.repo.ProbeRepository
import com.serverprobe.manager.ui.components.ConfirmDialog
import com.serverprobe.manager.ui.components.MeterBar
import com.serverprobe.manager.ui.components.MiniChart
import com.serverprobe.manager.ui.components.StatusDot
import com.serverprobe.manager.ui.components.formatBytes
import com.serverprobe.manager.ui.components.formatBps
import com.serverprobe.manager.ui.components.formatPct
import com.serverprobe.manager.ui.components.formatTrafficMB
import com.serverprobe.manager.ui.components.formatUptime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    hostId: Long,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onTerminal: (Long) -> Unit,
    onAddSsh: () -> Unit,
    vm: DetailViewModel = viewModel(key = "detail_$hostId", factory = viewModelFactory(hostId)),
) {
    val host by vm.host.collectAsState()
    val runtime by vm.runtime.collectAsState()
    val services by vm.services.collectAsState()
    val servicesLoading by vm.servicesLoading.collectAsState()
    val servicesError by vm.servicesError.collectAsState()
    val actionResult by vm.actionResult.collectAsState()
    val sshList by vm.sshHosts.collectAsState()

    val rt = runtime[hostId]
    val st = rt?.status
    val snackbar = remember { SnackbarHostState() }
    var showSshSheet by remember { mutableStateOf(false) }
    var serviceQuery by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<Pair<ProbeService, String>?>(null) }

    LaunchedEffect(host?.id) { vm.loadServices() }
    LaunchedEffect(actionResult) {
        actionResult?.let {
            snackbar.showSnackbar(it)
            vm.clearActionResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(host?.name ?: "主机详情", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        host?.let { Text("${it.host}:${it.port}", style = MaterialTheme.typography.bodySmall) }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = {
                    IconButton(onClick = { vm.refresh() }) { Icon(Icons.Default.Refresh, "刷新") }
                    host?.let { h -> IconButton(onClick = { onEdit(h.id) }) { Icon(Icons.Default.Edit, "编辑") } }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Button(
                onClick = { showSshSheet = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            ) {
                Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("SSH 快速连接")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 概览
            item {
                Card(shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(
                                online = when (rt?.state) {
                                    ProbeRepository.ConnState.ONLINE -> true
                                    ProbeRepository.ConnState.CHECKING -> null
                                    else -> false
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (rt?.state) {
                                    ProbeRepository.ConnState.ONLINE -> "在线"
                                    ProbeRepository.ConnState.CHECKING -> "检测中"
                                    ProbeRepository.ConnState.OFFLINE -> "离线"
                                    else -> "初始化"
                                },
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${st?.hostname ?: "-"} · ${st?.os ?: "-"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        MiniChart(values = rt?.cpuHistory ?: emptyList())
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            MeterBar("CPU", st?.cpuPercent ?: 0.0, Modifier.fillMaxWidth(),
                                detail = "核心 ${st?.cpuCores ?: 0} · ${formatPct(st?.cpuPercent ?: 0.0)}")
                            MeterBar("内存", st?.memPercent ?: 0.0, Modifier.fillMaxWidth(),
                                detail = "${formatBytes(st?.memUsedBytes ?: 0)} / ${formatBytes(st?.memTotalBytes ?: 0)}")
                        }
                        Text(
                            "运行 ${formatUptime(st?.uptimeSec ?: 0.0)} · 进程 ${st?.procs ?: 0} · " +
                                "负载 ${"%.2f".format(st?.load1 ?: 0.0)} / ${"%.2f".format(st?.load5 ?: 0.0)} / ${"%.2f".format(st?.load15 ?: 0.0)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        rt?.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // 每核 CPU
            item {
                SectionCard("CPU 每核心占用") {
                    val cores = st?.cpuPerCore.orEmpty()
                    if (cores.isEmpty()) {
                        Text("暂无数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        cores.mapIndexed { idx, v -> idx to v }.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { (idx, v) -> MeterBar("#${idx + 1}", v, Modifier.weight(1f)) }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // 内存/交换
            item {
                SectionCard("内存与交换") {
                    MeterBar("物理内存", st?.memPercent ?: 0.0,
                        detail = "${formatBytes(st?.memUsedBytes ?: 0)} / ${formatBytes(st?.memTotalBytes ?: 0)}")
                    st?.let { s ->
                        if (s.swapTotalBytes > 0) {
                            MeterBar(
                                "交换分区 Swap",
                                s.swapUsedBytes * 100.0 / s.swapTotalBytes,
                                detail = "${formatBytes(s.swapUsedBytes)} / ${formatBytes(s.swapTotalBytes)}",
                            )
                        }
                    }
                }
            }

            // 磁盘
            item {
                SectionCard("磁盘") {
                    val disks = st?.disks.orEmpty()
                    if (disks.isEmpty()) {
                        Text("暂无数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    disks.forEach { d ->
                        MeterBar(
                            "${d.mount} (${d.fs})",
                            d.usedPercent,
                            detail = "${formatBytes(d.usedBytes)} / ${formatBytes(d.totalBytes)}",
                        )
                    }
                }
            }

            // 网络
            item {
                SectionCard("网络") {
                    val nets = st?.net.orEmpty()
                    if (nets.isEmpty()) {
                        Text("暂无数据", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    nets.forEach { n ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(n.iface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                            Text(
                                "↓ ${formatBps(n.rxBps)}   ↑ ${formatBps(n.txBps)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // 流量统计（探针持久化累计；旧版探针无此数据时隐藏）
                    st?.let { s ->
                        val hasTraffic = s.netDay.total > 0 || s.netMonth.total > 0 || s.netTotal.total > 0
                        if (hasTraffic) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "流量统计（今日 / 本月 / 总计）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            TrafficStatRow("今日", s.netDay)
                            TrafficStatRow("本月", s.netMonth)
                            TrafficStatRow("总计", s.netTotal)
                        }
                    }
                }
            }

            // 服务
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("系统服务 (systemd)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (servicesLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }
                    OutlinedTextField(
                        value = serviceQuery,
                        onValueChange = { serviceQuery = it },
                        placeholder = { Text("搜索服务…") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            val filtered = services.orEmpty()
                .filter { it.unit.contains(serviceQuery, true) || it.description.contains(serviceQuery, true) }
            if (servicesError != null) {
                item {
                    Text(
                        "获取服务失败: $servicesError",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            items(filtered, key = { it.unit }) { svc ->
                ServiceRow(svc = svc, onAction = { action -> pendingAction = svc to action })
            }
            if (filtered.isEmpty() && services != null && servicesError == null) {
                item { Text("没有匹配的服务", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // SSH 快速连接菜单
    if (showSshSheet) {
        ModalBottomSheet(onDismissRequest = { showSshSheet = false }) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("SSH 快速连接", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    host?.let { h -> "选择一个 SSH 身份建立远程终端" } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                val ordered = host?.let { h ->
                    sshList.sortedByDescending { it.id == h.sshHostId }
                } ?: sshList
                ordered.forEach { ssh ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                ssh.alias.take(1).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(ssh.alias, style = MaterialTheme.typography.titleSmall)
                                if (host?.sshHostId == ssh.id) {
                                    Text(
                                        "已绑定",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                "${ssh.username}@${ssh.host}:${ssh.port} · ${if (ssh.authType == AUTH_KEY) "私钥" else "密码"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Button(onClick = {
                            showSshSheet = false
                            onTerminal(ssh.id)
                        }) { Text("连接") }
                    }
                }
                if (ordered.isEmpty()) {
                    Text(
                        "还没有 SSH 身份。添加后即可从本页一键连接远程终端。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { showSshSheet = false; onAddSsh() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加 SSH 身份")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    pendingAction?.let { (svc, action) ->
        ConfirmDialog(
            title = "${mapAction(action)}服务",
            text = "确定对 ${svc.unit} 执行 ${mapAction(action)} 操作吗？",
            confirmLabel = mapAction(action),
            danger = action == "stop",
            onConfirm = {
                vm.serviceAction(svc.unit, action)
                pendingAction = null
            },
            onDismiss = { pendingAction = null },
        )
    }
}

private fun mapAction(action: String): String = when (action) {
    "start" -> "启动"
    "stop" -> "停止"
    "restart" -> "重启"
    else -> action
}

@Composable
private fun SectionCard(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun TrafficStatRow(label: String, usage: com.serverprobe.manager.data.probe.TrafficUsage) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(52.dp))
        Text(
            "↓ ${formatTrafficMB(usage.rx)}   ↑ ${formatTrafficMB(usage.tx)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ServiceRow(svc: ProbeService, onAction: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(shape = RoundedCornerShape(12.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val active = svc.active == "active"
            StatusDot(online = active, size = 8.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(svc.unit, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    svc.description.ifEmpty { svc.sub },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                when {
                    svc.active == "active" -> "运行中"
                    svc.active == "failed" -> "失败"
                    svc.active == "inactive" -> "已停止"
                    else -> svc.active
                },
                style = MaterialTheme.typography.labelSmall,
                color = when (svc.active) {
                    "active" -> Color(0xFF34C759)
                    "failed" -> Color(0xFFFF5A5A)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Box {
                FilterChip(
                    selected = false,
                    onClick = { menu = true },
                    label = { Text("操作") },
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("启动") },
                        onClick = { menu = false; onAction("start") },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("停止") },
                        onClick = { menu = false; onAction("stop") },
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("重启") },
                        onClick = { menu = false; onAction("restart") },
                    )
                }
            }
        }
    }
}

private fun viewModelFactory(hostId: Long): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            DetailViewModel(com.serverprobe.manager.App.instance, hostId) as T
    }
