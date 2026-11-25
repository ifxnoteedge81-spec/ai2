package com.personal.aicomputer.resources

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import com.personal.aicomputer.runtime.Workspace
import com.personal.aicomputer.storage.SettingsStore

enum class Pressure { LOW, MEDIUM, HIGH }

data class ResourceSnapshot(
    val totalRamMb: Long,
    val availRamMb: Long,
    val thresholdMb: Long,
    val lowMemory: Boolean,
    val pressure: Pressure,
    val appPssMb: Long,
    val javaHeapUsedMb: Long,
    val cpuCores: Int,
    /** CPU used by THIS app process only (Android blocks reading system-wide /proc/stat for apps). */
    val appCpuPercent: Int,
    val storageTotalMb: Long,
    val storageFreeMb: Long,
    val workspaceMb: Long,
)

class ResourceMonitor(private val context: Context, private val workspace: Workspace) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private var lastCpu = Process.getElapsedCpuTime()
    private var lastWall = SystemClock.elapsedRealtime()
    private var lastCpuPercent = 0
    private var cached: ResourceSnapshot? = null
    private var cachedAt = 0L
    private var wsMb = 0L
    private var wsAt = 0L

    @Synchronized
    fun snapshot(maxAgeMs: Long = 2000): ResourceSnapshot {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - cachedAt < maxAgeMs) return it }

        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = mi.totalMem / MB
        val availMb = mi.availMem / MB
        val thresholdMb = mi.threshold / MB
        val pressure = when {
            mi.lowMemory || availMb < thresholdMb * 1.3 -> Pressure.HIGH
            availMb < totalMb * 0.2 -> Pressure.MEDIUM
            else -> Pressure.LOW
        }
        val pssKb = runCatching { am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss }.getOrDefault(0)
        val rt = Runtime.getRuntime()

        val cpu = Process.getElapsedCpuTime()
        val wallDelta = (now - lastWall).coerceAtLeast(1)
        if (now - lastWall >= 1000) {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            lastCpuPercent = (((cpu - lastCpu) * 100) / (wallDelta * cores)).toInt().coerceIn(0, 100)
            lastCpu = cpu
            lastWall = now
        }

        val data = StatFs(Environment.getDataDirectory().path)
        if (now - wsAt > 30_000) {
            wsMb = workspace.workspaceBytes() / MB
            wsAt = now
        }

        return ResourceSnapshot(
            totalRamMb = totalMb,
            availRamMb = availMb,
            thresholdMb = thresholdMb,
            lowMemory = mi.lowMemory,
            pressure = pressure,
            appPssMb = pssKb / 1024L,
            javaHeapUsedMb = (rt.totalMemory() - rt.freeMemory()) / MB,
            cpuCores = Runtime.getRuntime().availableProcessors(),
            appCpuPercent = lastCpuPercent,
            storageTotalMb = data.totalBytes / MB,
            storageFreeMb = data.availableBytes / MB,
            workspaceMb = wsMb,
        ).also { cached = it; cachedAt = now }
    }

    companion object {
        private const val MB = 1024L * 1024L
    }
}

/**
 * Turns the user's chosen "RAM budget" and mode into concrete limits. Android does not let an app reserve
 * physical RAM, so the budget is enforced as: context size, tool output caps, shell virtual-memory ulimit,
 * step limits, and a single-task queue (concurrency 1) that gets stricter under memory pressure.
 */
class ResourcePolicy(private val settings: SettingsStore, private val monitor: ResourceMonitor) {
    fun pressure(): Pressure = monitor.snapshot().pressure

    fun effectiveLowResource(): Boolean =
        settings.lowResourceMode.value || settings.performanceMode.value == "low_power" || pressure() == Pressure.HIGH

    fun contextChars(): Int {
        if (effectiveLowResource()) return 20_000
        return if (settings.performanceMode.value == "performance" && settings.ramBudgetMb.value >= 4096) 100_000 else 48_000
    }

    fun outputCapChars(): Int = if (effectiveLowResource()) 8_000 else 20_000

    fun shellVirtualMemoryKb(): Long = settings.ramBudgetMb.value.toLong() * 1024L

    fun maxSteps(): Int {
        val cfg = settings.maxSteps.value.coerceIn(3, 100)
        return if (effectiveLowResource()) minOf(cfg, 15) else cfg
    }

    /** Agent tasks always run one at a time in v1; this is the hard ceiling and it never grows. */
    fun maxConcurrentTasks(): Int = 1

    /** True when the chosen budget is larger than what the device can plausibly give. */
    fun budgetExceedsDevice(): Boolean = settings.ramBudgetMb.value > monitor.snapshot().totalRamMb * 0.75
}
