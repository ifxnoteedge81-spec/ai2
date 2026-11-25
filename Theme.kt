package com.personal.aicomputer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF4DD0E1), onPrimary = Color(0xFF00363D),
    secondary = Color(0xFFB388FF), tertiary = Color(0xFFFFB74D),
    background = Color(0xFF0B0F14), surface = Color(0xFF121820), surfaceVariant = Color(0xFF1B2430),
    onBackground = Color(0xFFE3E8EF), onSurface = Color(0xFFE3E8EF), error = Color(0xFFFF6B6B),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF00838F), secondary = Color(0xFF6A40C9), tertiary = Color(0xFFB26A00),
    background = Color(0xFFF5F7FA), surface = Color(0xFFFFFFFF), surfaceVariant = Color(0xFFE6EBF2),
)

@Composable
fun PacTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f), modifier = Modifier.width(130.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatMb(mb: Long): String = if (mb >= 1024) "%.1f GB".format(mb / 1024.0) else "$mb MB"
