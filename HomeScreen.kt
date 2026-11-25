package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import com.personal.aicomputer.agent.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val sl = ServiceLocator
    var snap by remember { mutableStateOf(sl.monitor.snapshot()) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = withContext(Dispatchers.Default) { sl.monitor.snapshot() }
            delay(3000)
        }
    }
    val tasks by sl.tasks.tasks.collectAsState()
    val active by sl.controller.activeTaskId.collectAsState()
    val provider by sl.settings.provider.flow.collectAsState()
    val budget by sl.settings.ramBudgetMb.flow.collectAsState()
    val rt by sl.runtime.status.collectAsState()
    val activeTask = tasks.firstOrNull { it.id == active }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("AI Agent") {
            KeyValue("Status", if (activeTask != null) "${activeTask.state.name.lowercase().replace('_', ' ')}: ${activeTask.goal.take(60)}" else "Idle")
            KeyValue("Model", sl.gateway.describeSelection())
            KeyValue("API key", if (sl.keys.has(if (provider == "gemini") "gemini_api_key" else "openrouter_api_key")) "configured" else "NOT SET")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onOpen("agent") }) { Text("Open AI Command Center") }
        }
        SectionCard("Memory (RAM)") {
            val used = (snap.totalRamMb - snap.availRamMb).toFloat() / snap.totalRamMb.coerceAtLeast(1)
            LinearProgressIndicator(progress = { used.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(6.dp))
            Spacer(Modifier.height(6.dp))
            KeyValue("Device", "${formatMb(snap.availRamMb)} free of ${formatMb(snap.totalRamMb)}")
            KeyValue("Pressure", snap.pressure.name)
            KeyValue("Budget", "${formatMb(budget.toLong())} (limit, not a reservation)")
            KeyValue("This app", "${snap.appPssMb} MB")
        }
        SectionCard("CPU & Storage") {
            KeyValue("CPU cores", snap.cpuCores.toString())
            KeyValue("App CPU", "${snap.appCpuPercent}%")
            KeyValue("Storage free", "${formatMb(snap.storageFreeMb)} of ${formatMb(snap.storageTotalMb)}")
            KeyValue("Workspace", formatMb(snap.workspaceMb))
        }
        SectionCard("Environment") {
            KeyValue("Shell runtime", rt.mode.label)
            KeyValue("Linux (PRoot)", if (rt.rootfsInstalled && rt.prootBinaryPresent) "installed" else "not available")
            KeyValue("Device control", if (sl.hands.isEnabled()) "Virtual Hands enabled" else "disabled (enable in Permissions)")
        }
        SectionCard("Tasks") {
            KeyValue("Active", tasks.count { it.state.isActive || it.state == TaskState.PENDING }.toString())
            KeyValue("Completed", tasks.count { it.state == TaskState.COMPLETED }.toString())
            KeyValue("Failed/cancelled", tasks.count { it.state == TaskState.FAILED || it.state == TaskState.CANCELLED }.toString())
            Spacer(Modifier.height(6.dp))
            tasks.takeLast(5).reversed().forEach {
                Text("• ${it.state.name.lowercase()} – ${it.goal.take(70)}", style = MaterialTheme.typography.bodySmall)
            }
            if (tasks.isEmpty()) Text("No tasks yet.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
