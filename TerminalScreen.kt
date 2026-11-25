package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import com.personal.aicomputer.security.CommandGuard
import com.personal.aicomputer.security.Risk
import kotlinx.coroutines.launch

@Composable
fun TerminalScreen() {
    val sl = ServiceLocator
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf("Personal AI Computer terminal. Commands run inside the workspace sandbox.") }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val rt by sl.runtime.status.collectAsState()
    val state = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) state.animateScrollToItem(lines.size - 1) }
    LaunchedEffect(Unit) { sl.runtime.refresh() }

    fun run() {
        val cmd = input.trim()
        if (cmd.isEmpty() || running) return
        input = ""
        lines += "$ $cmd"
        val d = CommandGuard.classify(cmd)
        if (d.risk == Risk.RESTRICTED) { lines += "Blocked: ${d.reason}"; return }
        running = true
        scope.launch {
            try {
                val r = sl.shell.run(cmd, 120_000)
                r.render().lines().forEach { lines += it }
            } catch (e: Exception) {
                lines += "Error: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        SectionCard("Runtime: ${rt.mode.label}") {
            Text(rt.note, style = MaterialTheme.typography.bodySmall)
            rt.prootSelfTest?.let { Text("PRoot self-test: $it", style = MaterialTheme.typography.bodySmall) }
            rt.progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    enabled = rt.prootBinaryPresent && !rt.busy && !rt.rootfsInstalled,
                    onClick = { scope.launch { sl.runtime.installAlpine().fold({ msg = it }, { msg = "Install failed: ${it.message}" }) } },
                ) { Text("Install Linux (Alpine)") }
                OutlinedButton(enabled = rt.rootfsInstalled && !rt.busy, onClick = { scope.launch { msg = "Self-test: " + sl.runtime.selfTest() } }) { Text("Self-test") }
                OutlinedButton(enabled = rt.rootfsInstalled && !rt.busy, onClick = { sl.runtime.removeLinux(); msg = "Linux environment removed." }) { Text("Remove") }
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(vertical = 6.dp), state = state) {
            items(lines) { Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("command…") }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
            IconButton(onClick = { run() }, enabled = !running) { Icon(Icons.Filled.PlayArrow, "Run") }
        }
    }
}
