package com.personal.aicomputer.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.personal.aicomputer.BuildConfig
import com.personal.aicomputer.ServiceLocator
import kotlinx.coroutines.delay

@Composable
fun PermissionsScreen() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(ServiceLocator.hands.isEnabled()) }
    val log by ServiceLocator.log.lines.collectAsState()
    LaunchedEffect(Unit) { while (true) { enabled = ServiceLocator.hands.isEnabled(); delay(1500) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Virtual Hands (Accessibility)") {
            KeyValue("Status", if (enabled) "ENABLED" else "disabled")
            Text(
                "Lets the agent read the screen and tap, swipe, scroll and type in other apps, but only for tools you approve per task. " +
                    "You enable it yourself in Android Settings and can turn it off there at any time.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.padding(top = 6.dp)) { Text("Open Accessibility settings") }
            Text("On Android 13+ with a sideloaded APK, you may first need App info → ⋮ → \"Allow restricted settings\".", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Permission levels") {
            Text("Level 1 – Safe (automatic): reading public web pages, creating files in the workspace, searching, reports.", style = MaterialTheme.typography.bodySmall)
            Text("Level 2 – Needs your confirmation: deleting/overwriting files, network downloads and installs in the shell, submit/send/buy/publish clicks, any device control, memory-low starts.", style = MaterialTheme.typography.bodySmall)
            Text("Level 3 – Never automatic: passwords/OTP/CAPTCHA/biometrics, banking & wallet apps, password managers, Android settings/installer screens, this app's own screens, Android system commands in the shell, private data of other apps, local-network requests.", style = MaterialTheme.typography.bodySmall)
        }
        SectionCard("Data & privacy") {
            Text("All tasks, memory and files stay on this phone. API keys are encrypted with the Android Keystore. AI requests go straight to the provider you chose and include your prompts and any tool output the agent sends along.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { ServiceLocator.browser.clearData() }) { Text("Clear browser cookies & cache") }
            OutlinedButton(onClick = { ServiceLocator.memory.clearAll() }) { Text("Delete all agent memory") }
        }
        SectionCard("Build") {
            KeyValue("Version", BuildConfig.VERSION_NAME)
            KeyValue("Release signed", if (BuildConfig.RELEASE_SIGNED) "yes (CI signing key)" else "no (debug/unsigned build)")
        }
        SectionCard("Recent log (secrets redacted)") {
            Text(log.takeLast(25).joinToString("\n").ifEmpty { "(empty)" }, style = MaterialTheme.typography.bodySmall)
        }
    }
}
