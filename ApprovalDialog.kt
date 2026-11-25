package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import com.personal.aicomputer.agent.ApprovalAnswer

/** Level 2 permission prompt. Shown over every screen while the agent waits. */
@Composable
fun ApprovalDialog() {
    val req by ServiceLocator.approvals.pending.collectAsState()
    val r = req ?: return
    AlertDialog(
        onDismissRequest = { ServiceLocator.approvals.answer(r.id, ApprovalAnswer.DENY) },
        title = { Text("Agent needs your permission") },
        text = {
            Column {
                Text(r.reason.ifBlank { "This action needs confirmation." }, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Text("Action:", style = MaterialTheme.typography.labelMedium)
                Text(r.summary.take(500), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Column {
                TextButton(onClick = { ServiceLocator.approvals.answer(r.id, ApprovalAnswer.ALLOW_ONCE) }) { Text("Allow once") }
                TextButton(onClick = { ServiceLocator.approvals.answer(r.id, ApprovalAnswer.ALLOW_FOR_TASK) }) { Text("Allow this type for this task") }
            }
        },
        dismissButton = { TextButton(onClick = { ServiceLocator.approvals.answer(r.id, ApprovalAnswer.DENY) }) { Text("Deny") } },
    )
}
