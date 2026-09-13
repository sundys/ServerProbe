package com.serverprobe.manager.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.repo.ProbeRepository
import com.serverprobe.manager.ui.components.ConfirmDialog
import com.serverprobe.manager.ui.components.MeterBar
import com.serverprobe.manager.ui.components.MiniChart
import com.serverprobe.manager.ui.components.StatusDot
import com.serverprobe.manager.ui.components.formatBps
import com.serverprobe.manager.ui.components.formatBytes
import com.serverprobe.manager.ui.components.formatPct
import com.serverprobe.manager.ui.components.formatTrafficMB
import com.serverprobe.manager.ui.components.formatUptime

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddProbe: () -> Unit,
    onAddSsh: () -> Unit,
    onProbeDetail: (Long) -> Unit,
    onEditProbe: (Long) -> Unit,
    onEditSsh: (Long) -> Unit,
    onTerminal: (Long) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val hosts by vm.hosts.collectAsState()
    val runtime by vm.runtime.collectAsState()
    val sshHosts by vm.sshHosts.collectAsState()

    var addMenu by remember { mutableStateOf(false) }
    var deleteProbe by remember { mutableStateOf<ProbeHostEntity?>(null) }
    var deleteSsh by remember { mutableStateOf<com.serverprobe.manager.data.db.SshHostEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("管理", fontWeight = FontWeight.Bold) },
                actions = {
                    // 右上角「＋」添加菜单（原右下角悬浮按钮）
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "添加") }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("添加探针主机") },
                                onClick = { addMenu = false; onAddProbe() },
                            )
                            DropdownMenuItem(
                                text = { Text("添加 SSH 主机") },
                                onClick = { addMenu = false; onAddSsh() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (hosts.isEmpty()) {
                EmptyHosts(onAddProbe = onAddProbe, onAddSsh = onAddSsh)
            } else {
                HostCardPager(
                    hosts = hosts,
                    runtime = runtime,
                    onDetail = onProbeDetail,
                    onEdit = onEditProbe,
                    onDelete = { deleteProbe = it },
                    onRefresh = vm::refreshNow,
                )
            }

            // SSH 快捷主机列表
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Terminal,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("SSH 主机", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onAddSsh) {
                    Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("添加")
                }
            }

            if (sshHosts.isEmpty()) {
                Text(
                    "还没有 SSH 主机，点击右上角「＋」保存服务器登录信息，点击即可一键连接终端。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(sshHosts, key = { it.id }) { ssh ->
                        SshQuickItem(
                            ssh = ssh,
                            onClick = { onTerminal(ssh.id) },
                            onEdit = { onEditSsh(ssh.id) },
                            onDelete = { deleteSsh = ssh },
                        )
                    }
                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }

    deleteProbe?.let { host ->
        ConfirmDialog(
            title = "删除探针主机",
            text = "确定删除「${host.name}」吗？仅移除 App 内记录，不影响服务器上的探针。",
            onConfirm = { vm.deleteProbe(host); deleteProbe = null },
            onDismiss = { deleteProbe = null },
        )
    }
    deleteSsh?.let { ssh ->
        ConfirmDialog(
            title = "删除 SSH 主机",
            text = "确定删除「${ssh.alias}」吗？",
            onConfirm = { vm.deleteSsh(ssh); deleteSsh = null },
            onDismiss = { deleteSsh = null },
        )
    }
}

@Composable
private fun EmptyHosts(onAddProbe: () -> Unit, onAddSsh: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("还没有受管主机", style = MaterialTheme.typography.titleMedium)
            Text(
                "1. 在服务器上安装探针 Agent（Go 单二进制 + systemd）\n" +
                    "2. 在 App 中添加探针主机（地址 + Token）\n" +
                    "3. 可选：绑定 SSH 身份，随时打开远程终端",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAddProbe) { Text("添加探针主机") }
                TextButton(onClick = onAddSsh) { Text("添加 SSH 主机") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCardPager(
    hosts: List<ProbeHostEntity>,
    runtime: Map<Long, ProbeRepository.HostRuntime>,
    onDetail: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (ProbeHostEntity) -> Unit,
    onRefresh: (ProbeHostEntity) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { hosts.size })
    Column {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) { index ->
            val host = hosts[index]
            HostCard(
                host = host,
                rt = runtime[host.id],
                onClick = { onDetail(host.id) },
                onEdit = { onEdit(host.id) },
                onDelete = { onDelete(host) },
                onRefresh = { onRefresh(host) },
            )
        }
        if (hosts.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(hosts.size) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (active) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostCard(
    host: ProbeHostEntity,
    rt: ProbeRepository.HostRuntime?,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val st = rt?.status
    val online = rt?.state == ProbeRepository.ConnState.ONLINE
    val checking = rt?.state == ProbeRepository.ConnState.CHECKING

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Dns,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            host.name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (st?.demo == true) {
                            Text(
                                "演示",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primaryContainer,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(
                        "${host.host}:${host.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusDot(online = if (checking) null else online)
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        checking -> "检测中"
                        online -> "在线"
                        else -> "离线"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "菜单") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("查看详情") }, onClick = { menu = false; onClick() })
                        DropdownMenuItem(
                            text = { Text("立即刷新") },
                            onClick = { menu = false; onRefresh() },
                            leadingIcon = { Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp)) },
                        )
                        DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                        DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                    }
                }
            }

            MiniChart(values = rt?.cpuHistory ?: emptyList())

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                MeterBar("CPU", st?.cpuPercent ?: 0.0, Modifier.weight(1f))
                MeterBar(
                    "内存",
                    st?.memPercent ?: 0.0,
                    Modifier.weight(1f),
                    detail = st?.let { "${formatBytes(it.memUsedBytes)} / ${formatBytes(it.memTotalBytes)}" },
                )
                MeterBar(
                    "磁盘",
                    st?.disks?.maxByOrNull { it.usedPercent }?.usedPercent ?: 0.0,
                    Modifier.weight(1f),
                    detail = st?.disks?.maxByOrNull { it.usedPercent }?.let { formatPct(it.usedPercent) },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    buildString {
                        val net = st?.net?.maxByOrNull { it.rxBps + it.txBps }
                        if (net != null) {
                            append("↓${formatBps(net.rxBps)} ↑${formatBps(net.txBps)}  ")
                        }
                        append("负载 ${"%.2f".format(st?.load1 ?: 0.0)}")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "运行 ${formatUptime(st?.uptimeSec ?: 0.0)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 流量统计（探针持久化累计；旧版探针无此数据时隐藏）
            st?.let { s ->
                if (s.netDay.total + s.netMonth.total + s.netTotal.total > 0) {
                    Text(
                        buildString {
                            append("流量 今日 ↓${formatTrafficMB(s.netDay.rx)} ↑${formatTrafficMB(s.netDay.tx)}")
                            append(" · 本月 ${formatTrafficMB(s.netMonth.total)}")
                            append(" · 总 ${formatTrafficMB(s.netTotal.total)}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            rt?.error?.let { err ->
                Text(
                    err,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SshQuickItem(
    ssh: com.serverprobe.manager.data.db.SshHostEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp)
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ssh.alias.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(ssh.alias, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${ssh.username}@${ssh.host}:${ssh.port} · ${if (ssh.authType == AUTH_KEY) "私钥" else "密码"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Terminal, "连接终端", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "菜单") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("连接终端") }, onClick = { menu = false; onClick() })
                    DropdownMenuItem(text = { Text("编辑") }, onClick = { menu = false; onEdit() })
                    DropdownMenuItem(text = { Text("删除") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}
