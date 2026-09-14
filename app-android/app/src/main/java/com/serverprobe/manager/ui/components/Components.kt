package com.serverprobe.manager.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.roundToInt

// ---- 数值格式化 ----

fun formatBytes(bytes: Long, decimals: Int = 1): String {
    if (bytes < 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "${bytes} B" else "%.${decimals}f %s".format(v, units[i])
}

fun formatBps(bps: Double): String {
    if (bps < 0) return "0 B/s"
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var v = bps
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    return if (i == 0) "${v.toLong()} B/s" else "%.1f %s".format(v, units[i])
}

fun formatUptime(seconds: Double): String {
    val s = seconds.toLong()
    val d = s / 86400
    val h = (s % 86400) / 3600
    val m = (s % 3600) / 60
    return when {
        d > 0 -> "${d}天${h}小时"
        h > 0 -> "${h}小时${m}分"
        else -> "${m}分钟"
    }
}

fun formatPct(p: Double): String = "%.1f%%".format(p)

/** 流量统计固定以 MB 显示 */
fun formatTrafficMB(bytes: Long): String {
    if (bytes <= 0) return "0.0 MB"
    return "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

// ---- 通用小组件 ----

@Composable
fun StatusDot(online: Boolean?, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val color = when (online) {
        true -> Color(0xFF34C759)
        false -> Color(0xFFFF5A5A)
        null -> Color(0xFFFFB340)
    }
    Canvas(modifier = modifier.size(size)) { drawCircle(color) }
}

@Composable
fun MeterBar(
    label: String,
    percent: Double,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                detail ?: formatPct(percent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape),
            color = when {
                percent >= 90 -> Color(0xFFFF5A5A)
                percent >= 70 -> Color(0xFFFFB340)
                else -> MaterialTheme.colorScheme.primary
            },
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** CPU 迷你走势图（Sparkline）：纵轴按窗口内最大值自适应缩放，空闲低占用时波动也可见 */
@Composable
fun MiniChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = Color(0xFF17B8A6)) {
    Canvas(modifier = modifier.fillMaxWidth().height(56.dp)) {
        if (values.size < 2) return@Canvas
        // 动态量程：至少 10%，避免 idle 时曲线贴底看似静止
        val yMax = maxOf(10.0, (values.maxOrNull() ?: 100.0) * 1.15)
        val stepX = size.width / max(values.size - 1, 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v.coerceIn(0.0, yMax) / yMax * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 3f))
    }
}

/** 半圆形进度仪表：弧在上、百分比居中、名称与可选详情在下方 */
@Composable
fun GaugeIndicator(
    label: String,
    percent: Double,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    val color = when {
        percent >= 90 -> Color(0xFFFF5A5A)
        percent >= 70 -> Color(0xFFFFB340)
        else -> Color(0xFF17B8A6)
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        val trackColor = MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 9.dp.toPx()
                val diameter = size.width - stroke
                if (diameter <= 0f) return@Canvas
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arcSize = Size(diameter, diameter)
                drawArc(trackColor, 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = (180f * (percent / 100.0).toFloat()).coerceIn(0f, 180f)
                drawArc(color, 180f, sweep, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text(
                "${percent.roundToInt()}%",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String = "删除",
    danger: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (danger) Color(0xFFE5484D) else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
