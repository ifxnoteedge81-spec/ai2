package com.personal.aicomputer.tools

import com.personal.aicomputer.agent.MemoryCategory
import com.personal.aicomputer.agent.MemoryResult
import com.personal.aicomputer.agent.MemoryStore
import com.personal.aicomputer.agent.SimpleTool
import com.personal.aicomputer.agent.Tool
import com.personal.aicomputer.agent.boolOr
import com.personal.aicomputer.agent.fail
import com.personal.aicomputer.agent.intOr
import com.personal.aicomputer.agent.ok
import com.personal.aicomputer.agent.requireStr
import com.personal.aicomputer.agent.str
import com.personal.aicomputer.resources.ResourceMonitor
import com.personal.aicomputer.resources.ResourcePolicy
import com.personal.aicomputer.runtime.RuntimeManager
import com.personal.aicomputer.runtime.ShellExecutor
import com.personal.aicomputer.runtime.Workspace
import com.personal.aicomputer.security.CommandGuard
import com.personal.aicomputer.security.Decision
import com.personal.aicomputer.security.Risk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** File tools. Everything is confined to the workspace by Workspace.resolve (symlink-safe). */
class FileTools(private val ws: Workspace, private val policy: ResourcePolicy) {
    fun all(): List<Tool> = listOf(list(), read(), write(), mkdir(), move(), delete())

    private fun list() = SimpleTool(
        "fs_list", "List files and folders in the workspace.", "path (string, default \".\")",
    ) { a, _ ->
        withContext(Dispatchers.IO) {
            val dir = ws.resolve(a.str("path") ?: ".")
            if (!dir.isDirectory) return@withContext fail("Not a directory: ${ws.relative(dir)}")
            val items = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
            if (items.isEmpty()) ok("(empty directory ${ws.relative(dir)})")
            else ok(items.take(200).joinToString("\n") { (if (it.isDirectory) "[dir]  " else "[file] ") + it.name + (if (it.isFile) "  (${it.length()} bytes)" else "") } +
                if (items.size > 200) "\n…and ${items.size - 200} more" else "")
        }
    }

    private fun read() = SimpleTool(
        "fs_read", "Read a text file from the workspace.", "path (string), offset (int, default 0), max_chars (int, default 8000)",
    ) { a, _ ->
        withContext(Dispatchers.IO) {
            val f = ws.resolve(a.requireStr("path"))
            if (!f.isFile) return@withContext fail("File not found: ${ws.relative(f)}")
            val head = ByteArray(1024)
            val headLen = f.inputStream().use { it.read(head).coerceAtLeast(0) }
            if ((0 until headLen).any { head[it].toInt() == 0 }) return@withContext fail("Binary file (${f.length()} bytes); cannot display as text.")
            val text = f.readText()
            val off = a.intOr("offset", 0).coerceIn(0, text.length)
            val max = a.intOr("max_chars", 8000).coerceIn(100, policy.outputCapChars())
            val slice = text.substring(off, minOf(text.length, off + max))
            ok(slice + if (off + max < text.length) "\n…[more: file has ${text.length} chars, continue with offset=${off + max}]" else "")
        }
    }

    private fun write() = SimpleTool(
        "fs_write", "Create or overwrite a text file in the workspace (overwriting needs user approval). Use append=true to add to a file.",
        "path (string), content (string), append (bool, default false)",
        classifier = { a ->
            val p = a.str("path")
            val exists = try { p != null && ws.resolve(p).let { it.isFile && it.length() > 0 } } catch (e: SecurityException) { false }
            if (exists && !a.boolOr("append", false)) Decision(Risk.CONFIRM, "overwrites an existing file", "file_overwrite") else Decision(Risk.SAFE)
        },
    ) { a, _ ->
        withContext(Dispatchers.IO) {
            val f = ws.resolve(a.requireStr("path"))
            val content = a.str("content") ?: ""
            if (content.length > 2_000_000) return@withContext fail("Content too large (max 2 MB).")
            f.parentFile?.mkdirs()
            if (a.boolOr("append", false)) f.appendText(content) else f.writeText(content)
            ok("Wrote ${content.length} chars to ${ws.relative(f)} (file is now ${f.length()} bytes).")
        }
    }

    private fun mkdir() = SimpleTool("fs_mkdir", "Create a folder (and parents) in the workspace.", "path (string)") { a, _ ->
        withContext(Dispatchers.IO) {
            val d = ws.resolve(a.requireStr("path"))
            if (d.mkdirs() || d.isDirectory) ok("Folder exists: ${ws.relative(d)}") else fail("Could not create ${ws.relative(d)}")
        }
    }

    private fun move() = SimpleTool(
        "fs_move", "Move or rename a file/folder inside the workspace (needs approval if the destination exists).", "from (string), to (string)",
        classifier = { a ->
            val to = a.str("to")
            val exists = try { to != null && ws.resolve(to).exists() } catch (e: SecurityException) { false }
            if (exists) Decision(Risk.CONFIRM, "overwrites an existing destination", "file_overwrite") else Decision(Risk.SAFE)
        },
    ) { a, _ ->
        withContext(Dispatchers.IO) {
            val from = ws.resolve(a.requireStr("from"))
            val to = ws.resolve(a.requireStr("to"))
            if (!from.exists()) return@withContext fail("Source not found: ${ws.relative(from)}")
            to.parentFile?.mkdirs()
            if (to.exists()) to.deleteRecursively()
            if (from.renameTo(to)) ok("Moved ${ws.relative(from)} → ${ws.relative(to)}") else fail("Move failed.")
        }
    }

    private fun delete() = SimpleTool(
        "fs_delete", "Delete a file or folder in the workspace. Always needs user approval.", "path (string)",
        classifier = { a ->
            val p = a.str("path")
            val isRoot = try { p != null && ws.resolve(p).canonicalPath == ws.root.canonicalPath } catch (e: SecurityException) { false }
            if (isRoot) Decision(Risk.RESTRICTED, "deleting the whole workspace", "file_delete") else Decision(Risk.CONFIRM, "permanently deletes ${p ?: "?"}", "file_delete")
        },
    ) { a, _ ->
        withContext(Dispatchers.IO) {
            val f = ws.resolve(a.requireStr("path"))
            if (!f.exists()) return@withContext fail("Not found: ${ws.relative(f)}")
            if (f.deleteRecursively()) ok("Deleted ${ws.relative(f)}") else fail("Could not delete ${ws.relative(f)}")
        }
    }
}

class ShellTools(private val shell: ShellExecutor) {
    fun all(): List<Tool> = listOf(run())

    private fun run() = SimpleTool(
        "shell_run",
        "Run a shell command inside the workspace sandbox (Linux userspace if installed, otherwise Android's built-in shell). Dangerous or networked commands need approval; Android system tools are blocked.",
        "command (string), cwd (string, default \".\"), timeout_sec (int, default 60, max 300)",
        timeoutMs = 320_000,
        classifier = { a -> CommandGuard.classify(a.str("command").orEmpty()) },
    ) { a, _ ->
        val cmd = a.requireStr("command")
        val timeout = a.intOr("timeout_sec", 60).coerceIn(1, 300) * 1000L
        val r = shell.run(cmd, timeout, a.str("cwd") ?: ".")
        if (r.ok) ok(r.render()) else fail(r.render())
    }
}

class WebTools(private val web: WebClient, private val ws: Workspace, private val policy: ResourcePolicy) {
    fun all(): List<Tool> = listOf(search(), fetch(), download())

    private fun search() = SimpleTool(
        "web_search", "Search the web (best effort, no API key). Returns titles, URLs and snippets.", "query (string), limit (int, default 8)",
    ) { a, _ ->
        val hits = web.search(a.requireStr("query"), a.intOr("limit", 8).coerceIn(1, 15))
        if (hits.isEmpty()) fail("No results. The search endpoint may be rate-limiting this device; try browser_open with a search URL instead.")
        else ok(hits.mapIndexed { i, h -> "${i + 1}. ${h.title}\n   ${h.url}\n   ${h.snippet}" }.joinToString("\n"))
    }

    private fun fetch() = SimpleTool(
        "web_fetch", "Download a public web page and return its readable text (no JavaScript). Local/private addresses are blocked.", "url (string), max_chars (int, default 8000)",
        timeoutMs = 45_000,
    ) { a, _ ->
        val page = web.fetch(a.requireStr("url"))
        if (page.status !in 200..299) {
            fail("HTTP ${page.status} from ${page.finalUrl}")
        } else {
            val isHtml = page.contentType.contains("html", true) || page.body.trimStart().startsWith("<")
            val text = if (isHtml) "Title: ${HtmlText.title(page.body)}\n\n" + HtmlText.toText(page.body) else page.body
            val max = a.intOr("max_chars", 8000).coerceIn(500, policy.outputCapChars())
            ok("URL: ${page.finalUrl}\n" + text.take(max) + if (text.length > max) "\n…[truncated, ${text.length} chars total]" else "")
        }
    }

    private fun download() = SimpleTool(
        "download_file", "Download a public file (max 50 MB) into workspace/downloads. Never executes it.", "url (string), filename (string, optional)",
        timeoutMs = 180_000,
    ) { a, _ ->
        val url = a.requireStr("url")
        val name = (a.str("filename") ?: url.substringBefore('?').substringAfterLast('/')).ifBlank { "download.bin" }
            .replace(Regex("[^A-Za-z0-9._\\-]"), "_").take(80)
        val dest = ws.resolve("downloads/$name")
        val n = web.download(url, dest)
        ok("Downloaded $n bytes to ${ws.relative(dest)}")
    }
}

class MemoryTools(private val memory: MemoryStore) {
    fun all(): List<Tool> = listOf(save(), search())

    private fun parseCategory(s: String?): MemoryCategory? =
        MemoryCategory.values().firstOrNull { it.name.equals(s?.trim()?.replace(' ', '_'), true) || it.label.equals(s?.trim(), true) }

    private fun save() = SimpleTool(
        "memory_save", "Save a short note to the user's editable agent memory. Never store passwords, OTPs or card numbers.",
        "category (user_preferences|user_profile|working_context|saved_instructions|automation_rules|project_info), text (string)",
    ) { a, _ ->
        val cat = parseCategory(a.str("category")) ?: MemoryCategory.WORKING_CONTEXT
        when (val r = memory.add(cat, a.requireStr("text"))) {
            is MemoryResult.Saved -> ok("Saved to ${cat.label}.")
            is MemoryResult.Rejected -> fail(r.reason)
        }
    }

    private fun search() = SimpleTool(
        "memory_search", "Search the user's saved memory.", "query (string, empty = most recent)",
    ) { a, _ ->
        val hits = memory.search(a.str("query").orEmpty())
        if (hits.isEmpty()) ok("(no matching memory)") else ok(hits.joinToString("\n") { "[${it.category.label}] ${it.text}" })
    }
}

class SystemTools(
    private val runtime: RuntimeManager,
    private val monitor: ResourceMonitor,
    private val policy: ResourcePolicy,
) {
    fun all(): List<Tool> = listOf(runtimeInfo(), wait())

    private fun runtimeInfo() = SimpleTool("runtime_info", "Show the shell runtime mode and device resource status.", "(none)") { _, _ ->
        val st = runtime.status.value
        runtime.refresh()
        val r = monitor.snapshot()
        ok(
            "Runtime mode: ${runtime.activeMode().label}\nPRoot binary: ${st.prootBinaryPresent}, Linux rootfs installed: ${st.rootfsInstalled}\n${st.note}\n" +
                "RAM: ${r.availRamMb}/${r.totalRamMb} MB free, pressure ${r.pressure}; app uses ${r.appPssMb} MB\n" +
                "Storage free: ${r.storageFreeMb} MB; workspace ${r.workspaceMb} MB; low-resource mode: ${policy.effectiveLowResource()}",
        )
    }

    private fun wait() = SimpleTool("wait", "Pause for a few seconds (e.g. for a page or app to finish loading).", "seconds (int, 1-30)") { a, _ ->
        val s = a.intOr("seconds", 2).coerceIn(1, 30)
        delay(s * 1000L)
        ok("Waited $s s.")
    }
}
