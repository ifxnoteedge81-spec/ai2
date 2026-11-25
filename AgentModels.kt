package com.personal.aicomputer.agent

import org.json.JSONArray
import org.json.JSONObject

enum class TaskState {
    PENDING, PLANNING, RUNNING, WAITING_PERMISSION, PAUSED, COMPLETED, FAILED, CANCELLED;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val isActive: Boolean get() = this == PLANNING || this == RUNNING || this == WAITING_PERMISSION || this == PAUSED
}

enum class StepStatus { OK, ERROR, DENIED, BLOCKED }

/** One real tool execution (or refusal). Everything in here happened; nothing is a model claim. */
data class StepRecord(
    val index: Int,
    val thought: String,
    val tool: String,
    val args: String,
    val status: StepStatus,
    val observation: String,
    val durationMs: Long,
    val at: Long,
)

data class AgentTask(
    val id: String,
    val goal: String,
    val state: TaskState,
    val createdAt: Long,
    val updatedAt: Long,
    val plan: List<String> = emptyList(),
    val steps: List<StepRecord> = emptyList(),
    val report: String? = null,
    /** True only when the final report is backed by at least one successful tool step (or is a pure answer). */
    val verified: Boolean = false,
    val error: String? = null,
    val model: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("goal", goal).put("state", state.name)
        .put("createdAt", createdAt).put("updatedAt", updatedAt)
        .put("plan", JSONArray(plan))
        .put("steps", JSONArray().also { arr ->
            steps.forEach { s ->
                arr.put(
                    JSONObject().put("i", s.index).put("thought", s.thought).put("tool", s.tool).put("args", s.args)
                        .put("status", s.status.name).put("obs", s.observation).put("ms", s.durationMs).put("at", s.at),
                )
            }
        })
        .put("report", report ?: JSONObject.NULL)
        .put("verified", verified)
        .put("error", error ?: JSONObject.NULL)
        .put("model", model ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): AgentTask {
            val steps = ArrayList<StepRecord>()
            o.optJSONArray("steps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    steps += StepRecord(
                        s.optInt("i"), s.optString("thought"), s.optString("tool"), s.optString("args"),
                        runCatching { StepStatus.valueOf(s.optString("status")) }.getOrDefault(StepStatus.ERROR),
                        s.optString("obs"), s.optLong("ms"), s.optLong("at"),
                    )
                }
            }
            val plan = ArrayList<String>()
            o.optJSONArray("plan")?.let { arr -> for (i in 0 until arr.length()) plan += arr.optString(i) }
            return AgentTask(
                id = o.getString("id"),
                goal = o.optString("goal"),
                state = runCatching { TaskState.valueOf(o.optString("state")) }.getOrDefault(TaskState.FAILED),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                plan = plan,
                steps = steps,
                report = if (o.isNull("report")) null else o.optString("report"),
                verified = o.optBoolean("verified"),
                error = if (o.isNull("error")) null else o.optString("error"),
                model = if (o.isNull("model")) null else o.optString("model"),
            )
        }
    }
}
