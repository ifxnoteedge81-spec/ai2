package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import com.personal.aicomputer.agent.MemoryCategory
import com.personal.aicomputer.agent.MemoryEntry
import com.personal.aicomputer.agent.MemoryResult

@Composable
fun MemoryScreen() {
    val mem = ServiceLocator.memory
    val entries by mem.entries.collectAsState()
    var cat by remember { mutableStateOf(MemoryCategory.USER_PREFERENCES) }
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<MemoryEntry?>(null) }
    var editText by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Everything here is stored only on this phone, can be edited or deleted, and is given to the agent as context. Do not save passwords, OTPs or card numbers (they are rejected).", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(MemoryCategory.values().toList()) { c -> FilterChip(selected = c == cat, onClick = { cat = c }, label = { Text(c.label) }) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Add to ${cat.label}…") }, maxLines = 3)
            Button(onClick = {
                when (val r = mem.add(cat, text)) {
                    is MemoryResult.Saved -> { text = ""; error = null }
                    is MemoryResult.Rejected -> error = r.reason
                }
            }, modifier = Modifier.padding(start = 6.dp)) { Text("Add") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val shown = entries.filter { it.category == cat }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} in ${cat.label}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            OutlinedButton(enabled = shown.isNotEmpty(), onClick = { mem.clearCategory(cat) }) { Text("Clear category") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { e ->
                Card {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(e.text, Modifier.weight(1f))
                        IconButton(onClick = { editing = e; editText = e.text }) { Icon(Icons.Filled.Edit, "Edit") }
                        IconButton(onClick = { mem.delete(e.id) }) { Icon(Icons.Filled.Delete, "Delete") }
                    }
                }
            }
        }
    }
    editing?.let { e ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit memory") },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(onClick = {
                    when (val r = mem.update(e.id, editText)) { is MemoryResult.Saved -> editing = null; is MemoryResult.Rejected -> error = r.reason }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}
