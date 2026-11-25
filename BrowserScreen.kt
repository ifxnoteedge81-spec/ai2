package com.personal.aicomputer.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.personal.aicomputer.ServiceLocator
import kotlinx.coroutines.launch

@Composable
fun BrowserScreen() {
    val b = ServiceLocator.browser
    val st by b.browserState.collectAsState()
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(st.url) { if (st.url.isNotEmpty()) address = st.url }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { b.back() } }, enabled = st.canGoBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            IconButton(onClick = { b.webView().goForward() }, enabled = st.canGoForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
            OutlinedTextField(
                value = address, onValueChange = { address = it }, modifier = Modifier.weight(1f),
                singleLine = true, placeholder = { Text("Search or enter address") },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = {
                    scope.launch { try { err = null; b.open(address) } catch (e: Exception) { err = e.message } }
                }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            )
            IconButton(onClick = { b.webView().reload() }) { Icon(Icons.Filled.Refresh, "Reload") }
        }
        if (st.loading) LinearProgressIndicator(progress = { st.progress / 100f }, modifier = Modifier.fillMaxWidth())
        (err ?: st.lastError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 10.dp)) }
        Text(
            "The agent can read and operate this browser (clicks that submit/buy/send need your approval; passwords, OTPs and CAPTCHAs are never filled by the agent).",
            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = {
                val w: WebView = b.webView()
                (w.parent as? ViewGroup)?.removeView(w)
                w
            },
        )
    }
}
