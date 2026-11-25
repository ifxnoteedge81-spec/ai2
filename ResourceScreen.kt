package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ResourceScreen() {
    val sl = ServiceLocator
    var snap by remember { mutableStateOf(sl.monitor.snapshot()) }
    LaunchedEffect(Unit) { while (true) { snap = withContext(Dispatchers.Default) { sl.monitor.snapshot() }; delay(2000) } }
    val budget by sl.settings.ramBudgetMb.flow.collectAsState()
    val mode by sl.settings.performanceMode.flow.collectAsState()
    val low by sl.settings.lowResourceMode.flow.collectAsState()
    var custom by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val options = listOf(512, 1024, 2048, 3072, 4096, 6144, 8192)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Device memory") {
            KeyValue("Total RAM", formatMb(snap.totalRamMb))
            KeyValue("Available now", formatMb(snap.availRamMb))
            KeyValue("Pressure", snap.pressure.name + if (snap.lowMemory) " (system low-memory flag set)" else "")
            KeyValue("This app (PSS)", "${snap.appPssMb} MB (Java heap ${snap.javaHeapUsedMb} MB)")
            KeyValue("CPU", "${snap.cpuCores} cores · app uses ${snap.appCpuPercent}%")
        }
        SectionCard("RAM budget") {
            Text("Current: ${formatMb(budget.toLong())}", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(options) { mb -> FilterChip(budget == mb, { sl.settings.ramBudgetMb.value = mb }, label = { Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(5) }, label = { Text("Custom (MB)") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(enabled = (custom.toIntOrNull() ?: 0) >= 256, onClick = { sl.settings.ramBudgetMb.value = custom.toInt(); custom = "" }, modifier = Modifier.padding(start = 6.dp)) { Text("Set") }
            }
            if (sl.policy.budgetExceedsDevice()) Text("This budget is more than 75% of the device's RAM; Android may kill the app.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text(
                "Honest note: Android does not let an app reserve physical RAM. The budget is enforced as limits: shell virtual-memory ulimit (${formatMb(sl.policy.shellVirtualMemoryKb() / 1024)}), " +
                    "AI context size (${sl.policy.contextChars()} chars), tool output cap (${sl.policy.outputCapChars()} chars), step limit (${sl.policy.maxSteps()}), and one task at a time.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            )
        }
        SectionCard("Performance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("low_power" to "Low power", "balanced" to "Balanced", "performance" to "Performance").forEach { (k, v) -> FilterChip(mode == k, { sl.settings.performanceMode.value = k }, label = { Text(v) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Low Resource Mode", style = MaterialTheme.typography.bodyMedium)
                    Text("Smaller context, shorter outputs, max 15 steps. Turns on automatically under memory pressure.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(low, { sl.settings.lowResourceMode.value = it })
            }
            Text("Effective now: ${if (sl.policy.effectiveLowResource()) "LOW RESOURCE" else "normal"}", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Storage") {
            KeyValue("Free on device", "${formatMb(snap.storageFreeMb)} of ${formatMb(snap.storageTotalMb)}")
            KeyValue("Workspace", formatMb(snap.workspaceMb))
            KeyValue("Linux rootfs", formatMb(sl.workspace.linuxBytes() / (1024 * 1024)))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedButton(onClick = { note = "Freed ${formatMb(sl.workspace.clearTmp() / (1024 * 1024))} from workspace/tmp" }) { Text("Clear tmp") }
                OutlinedButton(onClick = { note = "Freed ${formatMb(sl.workspace.clearCache() / (1024 * 1024))} of cache" }) { Text("Clear cache") }
            }
            Text("Cleanup only touches tmp and cache. Your files are never deleted without confirmation.", style = MaterialTheme.typography.bodySmall)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
