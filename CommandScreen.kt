package com.personal.aicomputer.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.personal.aicomputer.agent.AgentTask
import com.personal.aicomputer.agent.StepRecord
import com.personal.aicomputer.agent.StepStatus
import com.personal.aicomputer.agent.TaskState
import com.personal.aicomputer.ai.AiException
import com.personal.aicomputer.ai.ChatMessage
import com.personal.aicomputer.ai.Role
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Locale

private class ChatLine(val fromUser: Boolean, text: String) {
    var text by mutableStateOf(text)
}

@Composable
fun CommandScreen() {
    val sl = ServiceLocator
    val scope = rememberCoroutineScope()
    var agentMode by remember { mutableStateOf(true) }
    var input by remember { mutableStateOf("") }
    val chat = remember { mutableStateListOf<ChatLine>() }
    var chatJob by remember { mutableStateOf<Job?>(null) }

    val tasks by sl.tasks.tasks.collectAsState()
    val selectedId by sl.controller.selectedTaskId.collectAsState()
    val activeId by sl.controller.activeTaskId.collectAsState()
    val paused by sl.controller.isPaused.collectAsState()
    val lang by sl.settings.language.flow.collectAsState()
    val task = tasks.firstOrNull { it.id == (selectedId ?: tasks.lastOrNull()?.id) }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val said = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!said.isNullOrBlank()) input = if (input.isBlank()) said else "$input $said"
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        input = ""
        if (agentMode) {
            sl.controller.submit(text)
        } else {
            chat += ChatLine(true, text)
            val reply = ChatLine(false, "")
            chat += reply
            val history = chat.dropLast(1).map { ChatMessage(if (it.fromUser) Role.USER else Role.ASSISTANT, it.text) }
            chatJob = scope.launch {
                try {
                    sl.gateway.stream(listOf(ChatMessage(Role.SYSTEM, "You are a helpful assistant inside Personal AI Computer. Reply in the user's language (Bangla or English). This is plain chat: you cannot run tools here.")) + history)
                        .catch { e -> reply.text += "\n[error] ${(e as? AiException)?.message ?: e.message}" }
                        .collect { reply.text += it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: AiException) {
                    reply.text = "[error] ${e.message}"
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = agentMode, onClick = { agentMode = true }, label = { Text("Agent (runs tools)") })
            FilterChip(selected = !agentMode, onClick = { agentMode = false }, label = { Text("Chat only (text)") })
        }
        if (agentMode && task != null && (task.state.isActive || task.id == activeId)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (paused) AssistChip(onClick = { sl.controller.resume() }, label = { Text("Resume") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) })
                else AssistChip(onClick = { sl.controller.pause() }, label = { Text("Pause (after current step)") }, leadingIcon = { Icon(Icons.Filled.Pause, null) })
                AssistChip(onClick = { sl.controller.cancel(task.id) }, label = { Text("Stop") }, leadingIcon = { Icon(Icons.Filled.Stop, null) })
            }
        }

        val listState = rememberLazyListState()
        val itemCount = if (agentMode) (task?.steps?.size ?: 0) else chat.size
        LaunchedEffect(itemCount, task?.state) { if (itemCount > 0) listState.animateScrollToItem(itemCount) }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (agentMode) {
                if (task == null) item { Hint() } else {
                    item { TaskHeader(task) }
                    items(task.steps, key = { it.index }) { StepCard(it) }
                    item { TaskFooter(task) }
                }
            } else {
                if (chat.isEmpty()) item { Text("Plain chat. The AI answers with text only; nothing is executed. Use Agent mode to run tools.", style = MaterialTheme.typography.bodySmall) }
                items(chat) { m ->
                    Card(colors = CardDefaults.cardColors(containerColor = if (m.fromUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)) {
                        Text((if (m.fromUser) "You: " else "AI (text only): ") + m.text, Modifier.padding(10.dp))
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                placeholder = { Text(if (agentMode) "Give the agent a goal… (বাংলা / English)" else "Ask anything…") },
                maxLines = 4,
            )
            IconButton(onClick = {
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (lang == "en") "en-US" else if (lang == "bn") "bn-BD" else Locale.getDefault().toLanguageTag())
                try { voice.launch(i) } catch (_: Exception) { input += "" }
            }) { Icon(Icons.Filled.Mic, "Voice input") }
            IconButton(onClick = { if (chatJob?.isActive == true && !agentMode) chatJob?.cancel() else send() }) {
                Icon(if (chatJob?.isActive == true && !agentMode) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Hint() {
    Text(
        "Give the agent a goal. It will plan, call real tools (files, shell, web, browser, device control), ask your permission for sensitive steps, " +
            "and report only what its tool results prove.\n\nSteps below marked ACTION EXECUTED really ran. Text marked AI REPORT is the model's summary.",
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun TaskHeader(t: AgentTask) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))) {
        Column(Modifier.padding(10.dp)) {
            Text("Goal: ${t.goal}", style = MaterialTheme.typography.titleSmall)
            Text("State: ${t.state.name.replace('_', ' ')}${t.model?.let { "  ·  model: $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
            if (t.plan.isNotEmpty()) {
                Text("Plan:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                t.plan.forEachIndexed { i, p -> Text("${i + 1}. $p", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun StepCard(s: StepRecord) {
    val color = when (s.status) {
        StepStatus.OK -> MaterialTheme.colorScheme.surfaceVariant
        StepStatus.ERROR -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
        StepStatus.DENIED, StepStatus.BLOCKED -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
    }
    Card(colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(10.dp)) {
            Text("ACTION EXECUTED #${s.index} · ${s.tool} · ${s.status} · ${s.durationMs} ms".let { if (s.status == StepStatus.DENIED || s.status == StepStatus.BLOCKED) it.replace("ACTION EXECUTED", "NOT EXECUTED") else it }, style = MaterialTheme.typography.labelMedium)
            if (s.thought.isNotBlank()) Text(s.thought, style = MaterialTheme.typography.bodySmall)
            Text(s.args, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(s.observation.take(700), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun TaskFooter(t: AgentTask) {
    if (t.state.isTerminal) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(10.dp)) {
                val label = when {
                    t.state == TaskState.COMPLETED && t.verified -> "RESULT: COMPLETED (backed by executed steps)"
                    t.state == TaskState.COMPLETED -> "RESULT: AI REPORT, NOT VERIFIED"
                    t.state == TaskState.CANCELLED -> "RESULT: CANCELLED"
                    else -> "RESULT: NOT COMPLETED"
                }
                Text(label, style = MaterialTheme.typography.titleSmall)
                t.report?.let { Text("AI REPORT:\n$it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
                t.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}
