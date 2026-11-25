package com.personal.aicomputer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import java.text.DateFormat
import java.util.Date

@Composable
fun HistoryScreen(onOpenTask: (String) -> Unit) {
    val tasks by ServiceLocator.tasks.tasks.collectAsState()
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${tasks.size} tasks stored locally", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick = { ServiceLocator.tasks.clearFinished() }) { Text("Clear finished") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(tasks.reversed(), key = { it.id }) { t ->
                Card(Modifier.clickable { onOpenTask(t.id) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.goal.take(100), style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${t.state.name.lowercase().replace('_', ' ')} · ${t.steps.size} steps${if (t.verified) " · verified" else ""} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(t.createdAt))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            t.error?.let { Text(it.take(120), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        IconButton(enabled = !t.state.isActive, onClick = { ServiceLocator.tasks.delete(t.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }
}
