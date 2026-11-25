package com.personal.aicomputer.agent

import com.personal.aicomputer.security.Decision
import com.personal.aicomputer.security.Risk
import org.json.JSONObject

data class ToolResult(val ok: Boolean, val output: String)

class ToolContext(val taskId: String)

interface Tool {
    val name: String
    val description: String

    /** Human-readable parameter list shown to the model, e.g. `path (string), content (string)`. */
    val params: String
    val timeoutMs: Long get() = 60_000

    /** Called before execution. SAFE runs, CONFIRM asks the user, RESTRICTED is refused. */
    fun classify(args: JSONObject): Decision = Decision(Risk.SAFE)

    suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult
}

class SimpleTool(
    override val name: String,
    override val description: String,
    override val params: String,
    override val timeoutMs: Long = 60_000,
    private val classifier: (JSONObject) -> Decision = { Decision(Risk.SAFE) },
    private val body: suspend (JSONObject, ToolContext) -> ToolResult,
) : Tool {
    override fun classify(args: JSONObject): Decision = classifier(args)
    override suspend fun execute(args: JSONObject, ctx: ToolContext): ToolResult = body(args, ctx)
}

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) { tools[tool.name] = tool }
    fun registerAll(list: List<Tool>) = list.forEach { register(it) }
    fun get(name: String): Tool? = tools[name]
    fun all(): List<Tool> = tools.values.toList()
    fun names(): List<String> = tools.keys.toList()

    fun describeForPrompt(): String = tools.values.joinToString("\n") { "- ${it.name}(${it.params}): ${it.description}" }
}

// --- argument helpers shared by tool implementations ---

fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) opt(key)?.toString() else null
fun JSONObject.requireStr(key: String): String = str(key)?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("Missing required argument '$key'")
fun JSONObject.intOr(key: String, default: Int): Int = if (has(key) && !isNull(key)) optInt(key, default) else default
fun JSONObject.boolOr(key: String, default: Boolean): Boolean = if (has(key) && !isNull(key)) optBoolean(key, default) else default

fun ok(text: String) = ToolResult(true, text)
fun fail(text: String) = ToolResult(false, text)
