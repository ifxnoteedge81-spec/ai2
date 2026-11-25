package com.personal.aicomputer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import java.io.File

@Composable
fun FilesScreen() {
    val ws = ServiceLocator.workspace
    var dir by remember { mutableStateOf(ws.root) }
    var tick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<File?>(null) }
    var editText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<File?>(null) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val items = remember(dir, tick) { dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty() }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text("workspace/" + ws.relative(dir).let { if (it == ".") "" else it }, style = MaterialTheme.typography.titleSmall)
        Text("Workspace: ${formatMb(ws.workspaceBytes() / (1024 * 1024))}. Only this folder is visible to the agent.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            OutlinedButton(enabled = dir != ws.root.canonicalFile && dir != ws.root, onClick = { dir = dir.parentFile ?: ws.root }) { Text("Up") }
            IconButton(onClick = { newFolder = "" }) { Icon(Icons.Filled.CreateNewFolder, "New folder") }
            IconButton(onClick = { tick++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            OutlinedButton(onClick = { note = "Cleared tmp: freed ${formatMb(ws.clearTmp() / (1024 * 1024))}"; tick++ }) { Text("Clear tmp") }
        }
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.path }) { f ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (f.isDirectory) dir = f
                        else if (f.length() < 500_000) { editText = runCatching { f.readText() }.getOrDefault(""); editing = f }
                        else note = "File too large to edit here (${f.length()} bytes)."
                    }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (f.isDirectory) Icons.Filled.Folder else Icons.Filled.Description, null)
                    Text("  ${f.name}", Modifier.weight(1f))
                    if (f.isFile) Text("${f.length()} B", style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { deleting = f }) { Icon(Icons.Filled.Delete, "Delete") }
                }
            }
        }
    }

    editing?.let { f ->
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(f.name) },
            text = { OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)) },
            confirmButton = { TextButton(onClick = { runCatching { f.writeText(editText) }; editing = null; tick++ }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${f.name}?") },
            text = { Text("This permanently deletes ${if (f.isDirectory) "the folder and everything in it" else "the file"}.") },
            confirmButton = { TextButton(onClick = { f.deleteRecursively(); deleting = null; tick++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
    newFolder?.let { name ->
        AlertDialog(
            onDismissRequest = { newFolder = null },
            title = { Text("New folder") },
            text = { OutlinedTextField(name, { newFolder = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    val clean = name.trim().replace(Regex("[/\\\\]"), "_")
                    if (clean.isNotEmpty() && clean != "." && clean != "..") File(dir, clean).mkdirs()
                    newFolder = null; tick++
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { newFolder = null }) { Text("Cancel") } },
        )
    }
}
