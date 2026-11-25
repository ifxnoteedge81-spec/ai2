package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.BuildConfig

@Composable
fun AboutScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Personal AI Computer ${BuildConfig.VERSION_NAME}") {
            Text("A local-first autonomous agent for Android. No backend server; AI calls go directly to Gemini or OpenRouter with your own keys.", style = MaterialTheme.typography.bodyMedium)
        }
        SectionCard("Implemented in v1 (focus areas)") {
            listOf(
                "AI brain: Gemini + OpenRouter, streaming chat, retry, model fallback, Keystore-encrypted keys",
                "Autonomous agent: plan → tool → permission → execute → verify → report; pause/resume/cancel; memory; history",
                "Local environment: sandboxed shell in a private workspace; optional Alpine Linux via PRoot (needs a PRoot binary bundled in the APK)",
                "Browser + Virtual Hands: in-app WebView automation, Android Accessibility tapping/typing/scrolling",
                "Resource manager: RAM budget as enforced limits, low-resource mode, storage cleanup",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("Known limitations (not hidden)") {
            listOf(
                "PRoot is a user-space chroot, not a virtual machine. It is NOT included by default; without it there is no Python/Node/gcc.",
                "Android cannot reserve RAM for an app; the budget is a set of limits.",
                "Playwright cannot run in an Android app; the browser uses WebView + injected JavaScript.",
                "Accessibility cannot see protected screens, and actions are only 'dispatched': the agent verifies by re-observing.",
                "Free AI models can be rate limited or removed at any time.",
                "Web search uses a best-effort public endpoint and may be blocked.",
                "Screenshots/vision, OCR and APK installation are not in v1.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
        SectionCard("Planned (not built yet)") {
            listOf(
                "Scheduler/automations (WorkManager), notifications for results",
                "Virtual Desktop view, App Manager, APK install flow",
                "Job-search, research, YouTube, file-assistant skills as packaged agent skills",
                "Screenshot + vision input, OCR, copy/paste and drag-and-drop gestures",
                "Room database migration (v1 stores tasks and memory as local JSON files)",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
