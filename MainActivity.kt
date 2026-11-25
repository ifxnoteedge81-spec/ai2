package com.personal.aicomputer

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.personal.aicomputer.ui.AppRoot
import com.personal.aicomputer.ui.PacTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themePref by ServiceLocator.settings.theme.flow.collectAsState()
            val dark = when (themePref) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            PacTheme(dark) {
                AskNotificationPermission()
                AppRoot()
            }
        }
    }

    @Composable
    private fun AskNotificationPermission() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
