package com.personal.aicomputer.agent

import com.personal.aicomputer.security.SensitiveFilter
import com.personal.aicomputer.storage.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class MemoryCategory(val label: String) {
    USER_PREFERENCES("User Preferences"),
    USER_PROFILE("User Profile"),
    TASK_HISTORY("Task History"),
    WORKING_CONTEXT("Working Context"),
    SAVED_INSTRUCTIONS("Saved Instructions"),
    AUTOMATION_RULES("Automation Rules"),
    PROJECT_INFO("Project Information"),
}

data class MemoryEntry(val id: String, val category: MemoryCategory, val text: String, val createdAt: Long, val updatedAt: Long)

sealed class MemoryResult {
    data class Saved(val entry: MemoryEntry) : MemoryResult()
    data class Rejected(val reason: String) : MemoryResult()
}

/** Local, user-editable agent memory. Refuses anything that looks like a password, OTP, card or API key. */
class MemoryStore(private val store: JsonFileStore) {
    private val state = MutableStateFlow(load())
    val entries: StateFlow<List<MemoryEntry>> = state.asStateFlow()

    private fun load(): List<MemoryEntry> {
        val arr = store.readArray()
        val out = ArrayList<MemoryEntry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cat = runCatching { MemoryCategory.valueOf(o.optString("category")) }.getOrNull() ?: continue
            out += MemoryEntry(o.optString("id"), cat, o.optString("text"), o.optLong("createdAt"), o.optLong("updatedAt"))
        }
        return out
    }

    @Synchronized
    private fun persist() {
        val arr = JSONArray()
        state.value.forEach {
            arr.put(JSONObject().put("id", it.id).put("category", it.category.name).put("text", it.text).put("createdAt", it.createdAt).put("updatedAt", it.updatedAt))
        }
        store.writeArray(arr)
    }

    @Synchronized
    fun add(category: MemoryCategory, text: String): MemoryResult {
        val clean = text.trim()
        if (clean.isEmpty()) return MemoryResult.Rejected("Empty text.")
        if (clean.length > 2000) return MemoryResult.Rejected("Too long (max 2000 characters).")
        if (SensitiveFilter.looksSensitive(clean)) return MemoryResult.Rejected("This looks like a password, OTP, card number or API key. Such secrets are never stored in agent memory.")
        val now = System.currentTimeMillis()
        val e = MemoryEntry(UUID.randomUUID().toString(), category, clean, now, now)
        state.value = state.value + e
        persist()
        return MemoryResult.Saved(e)
    }

    @Synchronized
    fun update(id: String, text: String): MemoryResult {
        val clean = text.trim()
        if (clean.isEmpty()) return MemoryResult.Rejected("Empty text.")
        if (SensitiveFilter.looksSensitive(clean)) return MemoryResult.Rejected("This looks like a secret and cannot be stored.")
        val old = state.value.firstOrNull { it.id == id } ?: return MemoryResult.Rejected("Entry not found.")
        val updated = old.copy(text = clean, updatedAt = System.currentTimeMillis())
        state.value = state.value.map { if (it.id == id) updated else it }
        persist()
        return MemoryResult.Saved(updated)
    }

    @Synchronized
    fun delete(id: String) { state.value = state.value.filter { it.id != id }; persist() }

    @Synchronized
    fun clearCategory(category: MemoryCategory) { state.value = state.value.filter { it.category != category }; persist() }

    @Synchronized
    fun clearAll() { state.value = emptyList(); persist() }

    /** Keeps only the newest [keep] entries of a category (used for the automatic task-history log). */
    @Synchronized
    fun trimCategory(category: MemoryCategory, keep: Int) {
        val inCat = state.value.filter { it.category == category }.sortedBy { it.createdAt }
        if (inCat.size <= keep) return
        val drop = inCat.take(inCat.size - keep).map { it.id }.toSet()
        state.value = state.value.filter { it.id !in drop }
        persist()
    }

    fun search(query: String, limit: Int = 10): List<MemoryEntry> {
        val words = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (words.isEmpty()) return state.value.takeLast(limit)
        return state.value
            .map { e -> e to words.count { e.text.lowercase().contains(it) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Text injected into the agent's system prompt (never includes task history). */
    fun promptContext(maxChars: Int = 2000): String {
        val use = listOf(MemoryCategory.USER_PROFILE, MemoryCategory.USER_PREFERENCES, MemoryCategory.SAVED_INSTRUCTIONS, MemoryCategory.PROJECT_INFO, MemoryCategory.WORKING_CONTEXT)
        val sb = StringBuilder()
        for (c in use) {
            val items = state.value.filter { it.category == c }
            if (items.isEmpty()) continue
            sb.append(c.label).append(":\n")
            for (e in items) {
                val line = "- ${e.text}\n"
                if (sb.length + line.length > maxChars) return sb.toString()
                sb.append(line)
            }
        }
        return sb.toString()
    }
}
