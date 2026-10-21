package com.serverprobe.manager.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.serverprobe.manager.data.db.AUTH_KEY
import com.serverprobe.manager.data.db.ProbeHostEntity
import com.serverprobe.manager.data.repo.ProbeRepository
import com.serverprobe.manager.ui.components.ConfirmDialog
import com.serverprobe.manager.ui.components.GaugeIndicator
import com.serverprobe.manager.ui.components.StatusDot
import com.serverprobe.manager.ui.components.formatBps
import com.serverprobe.manager.ui.components.formatBytes
import com.serverprobe.manager.ui.components.memDetail
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

    // 横屏手机（可用高度很矮、宽度足够）启用紧凑双栏：左卡片、右 SSH 列表。
    // 分屏等窄宽场景仍走单列滚动布局，避免两栏各自过窄。
    val configuration = LocalConfiguration.current
    val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenHeightDp < 500 &&
        configuration.screenWidthDp >= 600

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
        if (compact) {
            // 横屏双栏：左侧主机卡片，右侧 SSH 主机列表，各自独立滚动
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                ) {
                    if (hosts.isEmpty()) {
                        EmptyHosts(onAddProbe = onAddProbe, onAddSsh = onAddSsh)
                    } else {
                        HostCardPager(
                            hosts = hosts,
                            runtime = runtime,
                            compact = true,
                            onDetail = onProbeDetail,
                            onEdit = onEditProbe,
                            onDelete = { deleteProbe = it },
                            onRefresh = vm::refreshNow,
                        )
                    }
                }

                VerticalDivider(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    SshHeader(onAddSsh = onAddSsh)
                    if (sshHosts.isEmpty()) {
                        SshEmptyHint()
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
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
        } else {
            // 竖屏：整页纵向滚动
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    if (hosts.isEmpty()) {
                        EmptyHosts(onAddProbe = onAddProbe, onAddSsh = onAddSsh)
                    } else {
                        HostCardPager(
                            hosts = hosts,
                            runtime = runtime,
                            compact = false,
                            onDetail = onProbeDetail,
                            onEdit = onEditProbe,
                            onDelete = { deleteProbe = it },
                            onRefresh = vm::refreshNow,
                        )
                    }
                }

                item { SshHeader(onAddSsh = onAddSsh) }

                if (sshHosts.isEmpty()) {
                    item { SshEmptyHint() }
                } else {
                    items(sshHosts, key = { it.id }) { ssh ->
                        SshQuickItem(
                            ssh = ssh,
                            onClick = { onTerminal(ssh.id) },
                            onEdit = { onEditSsh(ssh.id) },
                            onDelete = { deleteSsh = ssh },
                        )
                    }
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
private fun SshHeader(onAddSsh: () -> Unit) {
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
}

@Composable
private fun SshEmptyHint() {
    Text(
        "还没有 SSH 主机，点击右上角「＋」保存服务器登录信息，点击即可一键连接终端。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp),
    )
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
    compact: Boolean,
    onDetail: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onDelete: (ProbeHostEntity) -> Unit,
    onRefresh: (ProbeHostEntity) -> Unit,
) {
    val pagerState = rememberPagerState(pageCount = { hosts.size })
    Column(Modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            pageSpacing = 12.dp,
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) { index ->
            val host = hosts[index]
            HostCard(
                host = host,
                rt = runtime[host.id],
                compact = compact,
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
                    .padding(top = if (compact) 6.dp else 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                repeat(hosts.size) { i ->
                    val active = pagerState.currentPage == i
                    val dotSize = when {
                        active && compact -> 7.dp
                        active -> 8.dp
                        compact -> 5.dp
                        else -> 6.dp
                    }
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(dotSize)
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
    compact: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRefresh: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val st = rt?.status
    val online = rt?.state == ProbeRepository.ConnState.ONLINE
    val checking = rt?.state == ProbeRepository.ConnState.CHECKING

    val netLoadText = buildString {
        val net = st?.net?.maxByOrNull { it.rxBps + it.txBps }
        if (net != null) {
            append("↓${formatBps(net.rxBps)} ↑${formatBps(net.txBps)}  ")
        }
        append("负载 ${"%.2f".format(st?.load1 ?: 0.0)}")
    }
    val uptimeText = "运行 ${formatUptime(st?.uptimeSec ?: 0.0)}"
    val hasTraffic = st?.let { it.netDay.total + it.netMonth.total + it.netTotal.total > 0 } == true
    val trafficToday = st?.let { "流量 今日 ↓${formatTrafficMB(it.netDay.rx)} ↑${formatTrafficMB(it.netDay.tx)}" }
    val trafficMonth = st?.let {
        "本月 ${formatTrafficMB(it.netMonth.rx)} ↓ / ${formatTrafficMB(it.netMonth.tx)} ↑ · 总 ${formatTrafficMB(it.netTotal.total)}"
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            Modifier.padding(if (compact) 12.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
        ) {
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

            if (compact) {
                // 横屏紧凑布局：左侧仪表，右侧统计/流量，充分利用横向空间
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.weight(1.15f),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        GaugeIndicator("CPU", st?.cpuPercent ?: 0.0, Modifier.weight(1f), compact = true)
                        GaugeIndicator(
                            "内存",
                            st?.memPercent ?: 0.0,
                            Modifier.weight(1f),
                            detail = st?.let { memDetail(it.memUsedBytes, it.memTotalBytes) },
                            compact = true,
                        )
                        GaugeIndicator(
                            "磁盘",
                            st?.disks?.maxByOrNull { it.usedPercent }?.usedPercent ?: 0.0,
                            Modifier.weight(1f),
                            detail = st?.disks?.maxByOrNull { it.usedPercent }?.let { formatPct(it.usedPercent) },
                            compact = true,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            netLoadText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            uptimeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hasTraffic && trafficToday != null && trafficMonth != null) {
                            Text(
                                trafficToday,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                trafficMonth,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        rt?.error?.let { err ->
                            Text(
                                err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GaugeIndicator("CPU", st?.cpuPercent ?: 0.0, Modifier.weight(1f))
                    GaugeIndicator(
                        "内存",
                        st?.memPercent ?: 0.0,
                        Modifier.weight(1f),
                        detail = st?.let { memDetail(it.memUsedBytes, it.memTotalBytes) },
                    )
                    GaugeIndicator(
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
                        netLoadText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        uptimeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // 流量统计（探针持久化累计；旧版探针无此数据时隐藏）
                if (hasTraffic && trafficToday != null && trafficMonth != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            trafficToday,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            trafficMonth,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
