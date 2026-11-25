package com.personal.aicomputer.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class StringSetting(private val prefs: SharedPreferences, private val key: String, default: String) {
    private val state = MutableStateFlow(prefs.getString(key, default) ?: default)
    val flow: StateFlow<String> = state.asStateFlow()
    var value: String
        get() = state.value
        set(v) { prefs.edit().putString(key, v).apply(); state.value = v }
}

class IntSetting(private val prefs: SharedPreferences, private val key: String, default: Int) {
    private val state = MutableStateFlow(prefs.getInt(key, default))
    val flow: StateFlow<Int> = state.asStateFlow()
    var value: Int
        get() = state.value
        set(v) { prefs.edit().putInt(key, v).apply(); state.value = v }
}

class BoolSetting(private val prefs: SharedPreferences, private val key: String, default: Boolean) {
    private val state = MutableStateFlow(prefs.getBoolean(key, default))
    val flow: StateFlow<Boolean> = state.asStateFlow()
    var value: Boolean
        get() = state.value
        set(v) { prefs.edit().putBoolean(key, v).apply(); state.value = v }
}

data class UsageStats(val requests: Int = 0, val promptTokens: Long = 0, val completionTokens: Long = 0)

/** Non-secret preferences. API keys are NOT here; see SecureKeyStore. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pac_settings", Context.MODE_PRIVATE)

    /** "gemini" or "openrouter" */
    val provider = StringSetting(prefs, "provider", "gemini")
    val geminiModel = StringSetting(prefs, "gemini_model", "gemini-2.5-flash")
    val openRouterModel = StringSetting(prefs, "openrouter_model", "")
    /** Comma-separated OpenRouter model ids tried after the primary one. */
    val openRouterFallbacks = StringSetting(prefs, "openrouter_fallbacks", "")
    val autoFallback = BoolSetting(prefs, "auto_fallback", true)

    /** "auto", "bn", "en" - used for voice input and as a hint to the agent. */
    val language = StringSetting(prefs, "language", "auto")
    /** "system", "dark", "light" */
    val theme = StringSetting(prefs, "theme", "system")

    val maxSteps = IntSetting(prefs, "max_steps", 25)

    // Resource manager
    val ramBudgetMb = IntSetting(prefs, "ram_budget_mb", 2048)
    /** "low_power", "balanced", "performance" */
    val performanceMode = StringSetting(prefs, "performance_mode", "balanced")
    val lowResourceMode = BoolSetting(prefs, "low_resource_mode", false)

    /** "auto" = use PRoot Linux when installed and working, otherwise the native sandbox. "native" forces the sandbox. */
    val runtimePreference = StringSetting(prefs, "runtime_preference", "auto")

    private val usageState = MutableStateFlow(loadUsage())
    val usage: StateFlow<Map<String, UsageStats>> = usageState.asStateFlow()

    private fun loadUsage(): Map<String, UsageStats> =
        listOf("gemini", "openrouter").associateWith { id ->
            val parts = prefs.getString("usage_$id", "")!!.split(',')
            UsageStats(parts.getOrNull(0)?.toIntOrNull() ?: 0, parts.getOrNull(1)?.toLongOrNull() ?: 0, parts.getOrNull(2)?.toLongOrNull() ?: 0)
        }

    @Synchronized
    fun recordUsage(providerId: String, promptTokens: Int?, completionTokens: Int?) {
        val cur = usageState.value[providerId] ?: UsageStats()
        val next = UsageStats(cur.requests + 1, cur.promptTokens + (promptTokens ?: 0), cur.completionTokens + (completionTokens ?: 0))
        prefs.edit().putString("usage_$providerId", "${next.requests},${next.promptTokens},${next.completionTokens}").apply()
        usageState.value = usageState.value + (providerId to next)
    }

    fun resetUsage() {
        prefs.edit().remove("usage_gemini").remove("usage_openrouter").apply()
        usageState.value = loadUsage()
    }

    fun modelFor(providerId: String): String = if (providerId == "gemini") geminiModel.value else openRouterModel.value
}
