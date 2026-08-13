package com.portalremote.net

import android.os.SystemClock
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * What the paired PC is doing with itself, as of the last `sys` push — one sample a
 * second, and only while the Stats screen is open (the PC stops sampling the moment it
 * isn't; see the server's `SystemStats`).
 *
 * Everything is raw: bytes, milliseconds, percentages. The formatting lives in
 * [formatBytes] and friends at the bottom of this file, so the screen draws and this
 * layer only ever holds numbers.
 */
data class PcStats(
    /** Mean load across every logical processor, 0–100. */
    val cpu: Float,
    /** Per logical processor, 0–100 — the little bars. */
    val cores: List<Float>,
    /** "AMD Ryzen 9 5900X 12-Core Processor", as the CPU names itself. */
    val cpuName: String,
    val memUsed: Long,
    val memTotal: Long,
    /** Bytes per second out and in, across every real adapter. */
    val netUp: Long,
    val netDown: Long,
    val disks: List<DiskUsage>,
    val top: List<ProcessLoad>,
    /** How long the PC has been up. */
    val uptimeMs: Long,
    val os: String,
    /** Monotonic clock reading when this arrived. Only used to tell a live graph from
     *  one that has been sitting on a screen nobody was looking at — see
     *  `AppViewModel.watchStats`. */
    val receivedAt: Long,
) {
    /** Memory in use as a fraction, for the gauge. Zero rather than a divide-by-zero on
     *  a machine that wouldn't tell us its total. */
    val memFraction: Float get() = if (memTotal <= 0) 0f else memUsed.toFloat() / memTotal

    companion object {
        fun fromPush(json: JSONObject, receivedAt: Long = SystemClock.elapsedRealtime()): PcStats {
            val cores = json.optJSONArray("cores")
            val disks = json.optJSONArray("disks")
            val top = json.optJSONArray("top")
            val memory = json.optJSONObject("mem")
            val net = json.optJSONObject("net")
            return PcStats(
                cpu = json.optDouble("cpu", 0.0).toFloat(),
                cores = List(cores?.length() ?: 0) { cores!!.optDouble(it, 0.0).toFloat() },
                cpuName = json.optStringOrNull("cpuName") ?: "Processor",
                memUsed = memory?.optLong("used") ?: 0,
                memTotal = memory?.optLong("total") ?: 0,
                netUp = net?.optDouble("up")?.toLong() ?: 0,
                netDown = net?.optDouble("down")?.toLong() ?: 0,
                disks = List(disks?.length() ?: 0) { DiskUsage.fromJson(disks!!.getJSONObject(it)) },
                top = List(top?.length() ?: 0) { ProcessLoad.fromJson(top!!.getJSONObject(it)) },
                uptimeMs = json.optLong("uptimeMs"),
                os = json.optStringOrNull("os") ?: "Windows",
                receivedAt = receivedAt,
            )
        }
    }
}

/** One fixed drive. Network and removable drives are left out by the PC — someone
 *  else's free space isn't this machine's health. */
data class DiskUsage(val name: String, val label: String?, val used: Long, val total: Long) {
    val free: Long get() = (total - used).coerceAtLeast(0)
    val fraction: Float get() = if (total <= 0) 0f else used.toFloat() / total

    companion object {
        fun fromJson(json: JSONObject) = DiskUsage(
            name = json.optStringOrNull("name") ?: "?",
            label = json.optStringOrNull("label"),
            used = json.optLong("used"),
            total = json.optLong("total"),
        )
    }
}

/** A process near the top of the list. [cpu] is against one core, so it can exceed 100
 *  on a machine with several — the same number Task Manager's Details tab shows. */
data class ProcessLoad(val name: String, val cpu: Float, val mem: Long) {
    companion object {
        fun fromJson(json: JSONObject) = ProcessLoad(
            name = json.optStringOrNull("name") ?: "?",
            cpu = json.optDouble("cpu", 0.0).toFloat(),
            mem = json.optLong("mem"),
        )
    }
}

private const val KILO = 1024.0

/**
 * Bytes as a person reads them — "14.2 GB", "512 MB".
 *
 * Binary steps under decimal names, which is what Windows itself shows: a 16GB stick of
 * RAM reporting as "17.2 GB" next to the PC's own dialogs saying 16 is a bug report, not
 * a units debate.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < KILO) return "$bytes B"
    var value = bytes / KILO
    val units = listOf("KB", "MB", "GB", "TB", "PB")
    var unit = 0
    while (value >= KILO && unit < units.lastIndex) {
        value /= KILO
        unit++
    }
    // One decimal below ten, none above: "9.4 GB" is worth the character, "247.3 GB"
    // is three digits of noise on a number that moves in whole gigabytes.
    return if (value < 10) "${(value * 10).roundToInt() / 10.0} ${units[unit]}"
    else "${value.roundToInt()} ${units[unit]}"
}

/** Throughput, as it goes under the up/down arrows. Idle is a dash rather than "0 B/s":
 *  a column of zeroes is what a broken meter looks like. */
fun formatRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "—" else "${formatBytes(bytesPerSecond)}/s"

/** How long the PC has been up, at one step of precision — "6d 4h", "4h 12m", "3m". */
fun formatUptime(millis: Long): String {
    val minutes = millis / 60_000
    val days = minutes / 1_440
    val hours = (minutes % 1_440) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes % 60}m"
        else -> "${minutes}m"
    }
}
