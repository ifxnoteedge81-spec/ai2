package com.personal.aicomputer.agent

import com.personal.aicomputer.storage.JsonFileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import java.util.UUID

/** Persistent task history (local file). Tasks that were mid-flight when the app died are marked as failed. */
class TaskRepository(private val store: JsonFileStore) {
    private val state = MutableStateFlow(load())
    val tasks: StateFlow<List<AgentTask>> = state.asStateFlow()

    private fun load(): List<AgentTask> {
        val arr = store.readArray()
        val out = ArrayList<AgentTask>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = runCatching { AgentTask.fromJson(o) }.getOrNull() ?: continue
            out += if (t.state.isActive || t.state == TaskState.PENDING) {
                t.copy(state = TaskState.FAILED, error = "Interrupted: the app was closed or restarted while this task was running.")
            } else t
        }
        return out
    }

    @Synchronized
    private fun persist(list: List<AgentTask>) {
        val arr = JSONArray()
        list.takeLast(200).forEach { arr.put(it.toJson()) }
        store.writeArray(arr)
    }

    fun get(id: String): AgentTask? = state.value.firstOrNull { it.id == id }

    @Synchronized
    fun create(goal: String): AgentTask {
        val now = System.currentTimeMillis()
        val t = AgentTask(UUID.randomUUID().toString(), goal.trim(), TaskState.PENDING, now, now)
        state.value = (state.value + t).takeLast(200)
        persist(state.value)
        return t
    }

    @Synchronized
    fun update(id: String, change: (AgentTask) -> AgentTask) {
        val list = state.value.map { if (it.id == id) change(it).copy(updatedAt = System.currentTimeMillis()) else it }
        state.value = list
        persist(list)
    }

    @Synchronized
    fun delete(id: String) {
        state.value = state.value.filter { it.id != id }
        persist(state.value)
    }

    @Synchronized
    fun clearFinished() {
        state.value = state.value.filter { !it.state.isTerminal }
        persist(state.value)
    }
}
