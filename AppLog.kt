package com.personal.aicomputer.security

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory ring buffer of recent log lines (shown in the UI) that also writes to Logcat. All text is redacted first. */
class AppLog {
    private val state = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = state.asStateFlow()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val clean = Redactor.redact(msg)
        val line = "${fmt.format(Date())} $level/$tag: $clean"
        state.value = (state.value + line).takeLast(400)
    }

    fun i(tag: String, msg: String) { Log.i(tag, Redactor.redact(msg)); add("I", tag, msg) }
    fun w(tag: String, msg: String) { Log.w(tag, Redactor.redact(msg)); add("W", tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) {
        Log.e(tag, Redactor.redact(msg + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: "")))
        add("E", tag, msg + (t?.let { " :: ${it.javaClass.simpleName}: ${it.message}" } ?: ""))
    }

    fun clear() { state.value = emptyList() }
}
