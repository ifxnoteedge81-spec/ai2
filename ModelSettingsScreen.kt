package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.ServiceLocator
import com.personal.aicomputer.ai.AiException
import com.personal.aicomputer.ai.AiProvider
import com.personal.aicomputer.ai.ChatMessage
import com.personal.aicomputer.ai.KeyNames
import com.personal.aicomputer.ai.ModelInfo
import com.personal.aicomputer.ai.Role
import kotlinx.coroutines.launch

@Composable
fun ModelSettingsScreen() {
    val sl = ServiceLocator
    val scope = rememberCoroutineScope()
    val provider by sl.settings.provider.flow.collectAsState()
    val gModel by sl.settings.geminiModel.flow.collectAsState()
    val orModel by sl.settings.openRouterModel.flow.collectAsState()
    val orFallbacks by sl.settings.openRouterFallbacks.flow.collectAsState()
    val autoFallback by sl.settings.autoFallback.flow.collectAsState()
    val maxSteps by sl.settings.maxSteps.flow.collectAsState()
    val language by sl.settings.language.flow.collectAsState()
    val theme by sl.settings.theme.flow.collectAsState()
    val usage by sl.settings.usage.collectAsState()
    var status by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<Pair<AiProvider, List<ModelInfo>>?>(null) }
    var keyTick by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Active provider") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(provider == "gemini", { sl.settings.provider.value = "gemini" }, label = { Text("Google Gemini") })
                FilterChip(provider == "openrouter", { sl.settings.provider.value = "openrouter" }, label = { Text("OpenRouter") })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Fall back to the other provider on failure", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(autoFallback, { sl.settings.autoFallback.value = it })
            }
            Text("Requests go directly from this phone to the provider you select. No server of ours is involved.", style = MaterialTheme.typography.bodySmall)
        }

        ProviderCard("Google Gemini", KeyNames.GEMINI, gModel, { sl.settings.geminiModel.value = it }, sl.gemini, keyTick, { keyTick++ }) { p, m -> picker = p to m }
        ProviderCard("OpenRouter", KeyNames.OPENROUTER, orModel, { sl.settings.openRouterModel.value = it }, sl.openRouter, keyTick, { keyTick++ }) { p, m -> picker = p to m }
        OutlinedTextField(
            orFallbacks, { sl.settings.openRouterFallbacks.value = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("OpenRouter fallback model ids (comma separated)") }, supportingText = { Text("Free models can be rate-limited or disappear at any time.") },
        )

        SectionCard("Test") {
            Button(onClick = {
                status = "Testing…"
                scope.launch {
                    status = try {
                        val r = sl.gateway.complete(listOf(ChatMessage(Role.USER, "Reply with the single word: pong")), maxOutputTokens = 20)
                        "OK via ${r.provider}/${r.model}: ${r.text.take(60)}"
                    } catch (e: AiException) { "Failed: ${e.message}" }
                }
            }) { Text("Send test request") }
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        }

        SectionCard("Agent & app") {
            Text("Max agent steps per task: $maxSteps", style = MaterialTheme.typography.bodyMedium)
            Slider(maxSteps.toFloat(), { sl.settings.maxSteps.value = it.toInt() }, valueRange = 5f..60f)
            Text("Language (voice input and reports)", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("auto" to "Auto", "bn" to "বাংলা", "en" to "English").forEach { (k, v) -> FilterChip(language == k, { sl.settings.language.value = k }, label = { Text(v) }) }
            }
            Spacer(Modifier.height(6.dp))
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "System", "dark" to "Dark", "light" to "Light").forEach { (k, v) -> FilterChip(theme == k, { sl.settings.theme.value = k }, label = { Text(v) }) }
            }
        }

        SectionCard("Usage on this device") {
            usage.forEach { (id, u) -> KeyValue(id, "${u.requests} requests · ${u.promptTokens} in / ${u.completionTokens} out tokens (as reported by the provider)") }
            OutlinedButton(onClick = { sl.settings.resetUsage() }) { Text("Reset counters") }
        }
    }

    picker?.let { (p, models) ->
        ModelPicker(p.displayName, models, onPick = { id ->
            if (p.id == "gemini") sl.settings.geminiModel.value = id else sl.settings.openRouterModel.value = id
            picker = null
        }, onDismiss = { picker = null })
    }
}

@Composable
private fun ProviderCard(
    title: String, keyName: String, model: String, setModel: (String) -> Unit, provider: AiProvider,
    keyTick: Int, bumpKey: () -> Unit, onModels: (AiProvider, List<ModelInfo>) -> Unit,
) {
    val sl = ServiceLocator
    val scope = rememberCoroutineScope()
    var keyInput by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val masked = remember(keyTick) { sl.keys.masked(keyName) }

    SectionCard(title) {
        Text(if (masked != null) "API key stored securely: $masked" else "No API key set", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            keyInput, { keyInput = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Paste your own API key") }, visualTransformation = PasswordVisualTransformation(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
            Button(enabled = keyInput.isNotBlank(), onClick = { sl.keys.put(keyName, keyInput); keyInput = ""; bumpKey(); msg = "Key saved (encrypted with Android Keystore)." }) { Text("Save key") }
            OutlinedButton(enabled = masked != null, onClick = { sl.keys.delete(keyName); bumpKey(); msg = "Key deleted." }) { Text("Delete key") }
        }
        OutlinedTextField(model, setModel, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Model id") })
        OutlinedButton(onClick = {
            msg = "Loading models…"
            scope.launch {
                msg = try {
                    val list = provider.listModels()
                    onModels(provider, list)
                    "${list.size} models found."
                } catch (e: AiException) { "Could not list models: ${e.message}" }
                catch (e: Exception) { "Could not list models: ${e.message}" }
            }
        }) { Text("Discover models") }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ModelPicker(title: String, models: List<ModelInfo>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var freeOnly by remember { mutableStateOf(false) }
    val shown = models.filter { (!freeOnly || it.free) && (query.isBlank() || it.id.contains(query, true) || it.name.contains(query, true)) }.take(150)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$title models") },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search") }, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(freeOnly, { freeOnly = it }); Text("  Free only", style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(Modifier.height(320.dp)) {
                    items(shown, key = { it.id }) { m ->
                        TextButton(onClick = { onPick(m.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text(m.id + (if (m.free) "  (free)" else "") + (m.contextLength?.let { "  · ${it / 1000}k ctx" } ?: ""), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
