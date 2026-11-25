package com.personal.aicomputer.agent

import org.json.JSONException
import org.json.JSONObject

sealed interface AgentAction {
    val plan: List<String>?

    data class Call(val thought: String, val tool: String, val args: JSONObject, override val plan: List<String>?) : AgentAction
    data class Final(
        val report: String,
        val success: Boolean,
        val evidence: List<Int>,
        val answerOnly: Boolean,
        override val plan: List<String>?,
    ) : AgentAction
}

/** Parsing of the model's one-JSON-object-per-turn protocol. Tolerates code fences and stray prose. */
object AgentProtocol {
    /** Returns the first balanced `{...}` block in [text] (string-literal aware), or null. */
    fun extractJsonText(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                if (escape) escape = false
                else if (c == '\\') escape = true
                else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) return text.substring(start, i + 1) }
            }
        }
        return null
    }

    fun parse(text: String): AgentAction? {
        val raw = extractJsonText(text) ?: return null
        val obj = try { JSONObject(raw) } catch (_: JSONException) { return null }
        val plan = obj.optJSONArray("plan")?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() } }?.takeIf { it.isNotEmpty() }

        if (obj.has("final")) {
            val ev = ArrayList<Int>()
            obj.optJSONArray("evidence")?.let { a -> for (i in 0 until a.length()) a.optInt(i, -1).takeIf { it >= 0 }?.let { ev += it } }
            return AgentAction.Final(
                report = obj.opt("final")?.toString().orEmpty(),
                success = obj.optBoolean("success", true),
                evidence = ev,
                answerOnly = obj.optBoolean("answer_only", false),
                plan = plan,
            )
        }
        val tool = obj.optString("tool", "").trim()
        if (tool.isEmpty()) return null
        val argsAny = obj.opt("args")
        val args = when (argsAny) {
            is JSONObject -> argsAny
            is String -> try { JSONObject(argsAny) } catch (_: JSONException) { JSONObject() }
            else -> JSONObject()
        }
        return AgentAction.Call(obj.optString("thought", ""), tool, args, plan)
    }
}
