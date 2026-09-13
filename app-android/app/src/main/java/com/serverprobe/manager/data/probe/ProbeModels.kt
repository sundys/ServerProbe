package com.serverprobe.manager.data.probe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProbeInfo(
    val hostname: String = "",
    val os: String = "",
    val kernel: String = "",
    val arch: String = "",
    @SerialName("cpu_model") val cpuModel: String = "",
    @SerialName("cpu_cores") val cpuCores: Int = 0,
    @SerialName("mem_total_bytes") val memTotalBytes: Long = 0,
    @SerialName("probe_version") val probeVersion: String = "",
    @SerialName("started_at") val startedAt: String = "",
    val demo: Boolean = false,
)

@Serializable
data class DiskInfo(
    val device: String = "",
    val mount: String = "",
    val fs: String = "",
    @SerialName("total_bytes") val totalBytes: Long = 0,
    @SerialName("used_bytes") val usedBytes: Long = 0,
    @SerialName("avail_bytes") val availBytes: Long = 0,
    @SerialName("used_percent") val usedPercent: Double = 0.0,
)

@Serializable
data class NetRate(
    val iface: String = "",
    @SerialName("rx_bps") val rxBps: Double = 0.0,
    @SerialName("tx_bps") val txBps: Double = 0.0,
    @SerialName("rx_total_bytes") val rxTotalBytes: Long = 0,
    @SerialName("tx_total_bytes") val txTotalBytes: Long = 0,
)

@Serializable
data class ProbeStatus(
    val hostname: String = "",
    val os: String = "",
    val kernel: String = "",
    val arch: String = "",
    @SerialName("cpu_model") val cpuModel: String = "",
    @SerialName("cpu_cores") val cpuCores: Int = 0,
    @SerialName("mem_total_bytes") val memTotalBytes: Long = 0,
    @SerialName("probe_version") val probeVersion: String = "",
    @SerialName("started_at") val startedAt: String = "",
    @SerialName("cpu_percent") val cpuPercent: Double = 0.0,
    @SerialName("cpu_per_core") val cpuPerCore: List<Double> = emptyList(),
    @SerialName("mem_used_bytes") val memUsedBytes: Long = 0,
    @SerialName("mem_available_bytes") val memAvailableBytes: Long = 0,
    @SerialName("mem_percent") val memPercent: Double = 0.0,
    @SerialName("swap_total_bytes") val swapTotalBytes: Long = 0,
    @SerialName("swap_used_bytes") val swapUsedBytes: Long = 0,
    val load1: Double = 0.0,
    val load5: Double = 0.0,
    val load15: Double = 0.0,
    val procs: Int = 0,
    @SerialName("uptime_sec") val uptimeSec: Double = 0.0,
    val disks: List<DiskInfo> = emptyList(),
    val net: List<NetRate> = emptyList(),
    val time: String = "",
    val demo: Boolean = false,
)

@Serializable
data class ProbeService(
    val unit: String = "",
    val load: String = "",
    val active: String = "",
    val sub: String = "",
    val description: String = "",
)

@Serializable
data class ServicesResponse(val services: List<ProbeService> = emptyList())

@Serializable
data class ServiceActionResponse(
    val ok: Boolean = false,
    val output: String = "",
    val error: String? = null,
)
