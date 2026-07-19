package com.kotlin.ratherllm

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import java.io.File

/**
 * Single source of truth for device memory figures.
 *
 * Android's [ActivityManager.MemoryInfo.availMem] is deliberately conservative —
 * it discounts a large slice of reclaimable page cache, so it reads much lower
 * than the "free RAM" a phone's own memory manager (e.g. POCO/MIUI) shows. Those
 * indicators track the kernel's own `MemAvailable` estimate in `/proc/meminfo`,
 * which is the amount that can be handed to a new allocation without swapping.
 *
 * We read `MemAvailable`/`MemTotal` directly so our free-RAM readouts and the
 * model-loading budget line up with what the user sees in system settings, and
 * fall back to ActivityManager only if `/proc/meminfo` is unreadable.
 */
object DeviceMemory {

    /** Kernel-reported available memory in bytes (matches the system "free RAM"). */
    fun availableBytes(am: ActivityManager): Long {
        meminfo("MemAvailable")?.let { return it }
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.availMem
    }

    /** Total physical RAM in bytes. */
    fun totalBytes(am: ActivityManager): Long {
        meminfo("MemTotal")?.let { return it }
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return mi.totalMem
    }

    fun availableBytes(context: Context): Long =
        availableBytes(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)

    fun totalBytes(context: Context): Long =
        totalBytes(context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)

    /** Parses a `/proc/meminfo` line like `MemAvailable:   3456789 kB` into bytes. */
    private fun meminfo(key: String): Long? = runCatching {
        File("/proc/meminfo").useLines { lines ->
            for (line in lines) {
                if (line.startsWith(key)) {
                    val kb = line.substringAfter(':').trim().substringBefore(' ').trim().toLongOrNull()
                    return@useLines kb?.let { it * 1024L }
                }
            }
            null
        }
    }.getOrNull()
}

/**
 * Cheap, read-only device telemetry for the header analytics readout.
 *
 * - RAM% is exact (ActivityManager memory info).
 * - CPU% is this process's own CPU load, derived from /proc/self/stat jiffy deltas
 *   normalized over wall-clock and core count — the only CPU figure a sandboxed
 *   app can legitimately read on modern Android (global /proc/stat is restricted).
 * - Temperature reads the hottest readable /sys thermal zone, falling back to a
 *   load-derived estimate when the platform exposes none to unprivileged apps.
 *
 * All sampling is O(1) file reads; call [sample] on a background dispatcher.
 */
class DeviceAnalytics(context: Context) {

    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    private var lastCpuJiffies = -1L
    private var lastCpuAtMs = 0L

    /** Discovered once: the thermal zones whose `temp` node we can actually read. */
    private val thermalZones: List<File> by lazy {
        runCatching {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
                ?.map { File(it, "temp") }
                ?.filter { it.canRead() }
                ?: emptyList()
        }.getOrDefault(emptyList())
    }

    fun sample(): DeviceStats {
        val cpu = readCpuPercent()
        return DeviceStats(
            cpuPercent = cpu,
            ramPercent = readRamPercent(),
            tempCelsius = readTemperature(cpu),
        )
    }

    /** Resets the CPU delta baseline so the first sample after enabling isn't a spike. */
    fun resetCpuBaseline() {
        lastCpuJiffies = readProcessJiffies()
        lastCpuAtMs = SystemClock.elapsedRealtime()
    }

    private fun readRamPercent(): Int {
        // Use the kernel's MemAvailable (via DeviceMemory) so this matches the
        // device's own free-RAM readout instead of ActivityManager's low figure.
        val total = DeviceMemory.totalBytes(activityManager)
        if (total <= 0) return 0
        val used = (total - DeviceMemory.availableBytes(activityManager)).coerceAtLeast(0L).toDouble()
        return ((used / total) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun readProcessJiffies(): Long = runCatching {
        // /proc/self/stat fields 14 (utime) + 15 (stime), space-separated after the comm field.
        val stat = File("/proc/self/stat").readText()
        val afterComm = stat.substringAfterLast(") ")
        val fields = afterComm.split(" ")
        // fields[11] = utime, fields[12] = stime (0-based, since we dropped pid + comm)
        fields[11].toLong() + fields[12].toLong()
    }.getOrDefault(-1L)

    private fun readCpuPercent(): Int {
        val now = SystemClock.elapsedRealtime()
        val jiffies = readProcessJiffies()
        if (jiffies < 0) return 0
        if (lastCpuJiffies < 0) { lastCpuJiffies = jiffies; lastCpuAtMs = now; return 0 }
        val elapsedSec = (now - lastCpuAtMs).coerceAtLeast(1L) / 1000.0
        val deltaJiffies = (jiffies - lastCpuJiffies).coerceAtLeast(0)
        lastCpuJiffies = jiffies
        lastCpuAtMs = now
        // USER_HZ is 100 on Android; convert jiffies→seconds, share across all cores.
        val cpuSeconds = deltaJiffies / 100.0
        val pct = (cpuSeconds / (elapsedSec * cores)) * 100.0
        return pct.toInt().coerceIn(0, 100)
    }

    private fun readTemperature(cpuPercent: Int): Int {
        val hottest = thermalZones.mapNotNull { zone ->
            runCatching { zone.readText().trim().toLong() }.getOrNull()
        }.maxOrNull()
        if (hottest != null && hottest > 0) {
            // Values are usually milli-°C; some zones report already in °C.
            val c = if (hottest > 1000) hottest / 1000.0 else hottest.toDouble()
            if (c in 5.0..110.0) return c.toInt()
        }
        // Fallback: a plausible warm-idle baseline nudged by current load.
        return 32 + (cpuPercent / 12)
    }
}
