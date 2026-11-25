package com.personal.aicomputer.runtime

import com.personal.aicomputer.resources.ResourcePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

data class ShellResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val durationMs: Long,
    val truncated: Boolean,
    val mode: RuntimeMode,
) {
    val ok: Boolean get() = !timedOut && exitCode == 0

    fun render(): String = buildString {
        append("[mode=${mode.label} exit=${exitCode ?: "none"}${if (timedOut) " TIMED OUT" else ""} ${durationMs}ms]\n")
        if (stdout.isNotEmpty()) append(stdout.trimEnd()).append('\n')
        if (stderr.isNotEmpty()) append("[stderr]\n").append(stderr.trimEnd()).append('\n')
        if (truncated) append("[output truncated]\n")
    }
}

/** Runs shell commands inside the workspace with a timeout, output cap and cancellation. Callers must have passed CommandGuard. */
class ShellExecutor(
    private val runtime: RuntimeManager,
    private val workspace: Workspace,
    private val policy: ResourcePolicy,
) {
    suspend fun run(command: String, timeoutMs: Long = 60_000, cwdRelative: String = "."): ShellResult {
        val cwd = workspace.resolve(cwdRelative).also { it.mkdirs() }
        val cap = policy.outputCapChars()
        val started = System.currentTimeMillis()
        val mode = runtime.activeMode()
        val pb = runtime.buildProcess(command, cwd, policy.shellVirtualMemoryKb())

        return coroutineScope {
            val proc = try {
                withContext(Dispatchers.IO) { pb.start() }
            } catch (e: IOException) {
                return@coroutineScope ShellResult(null, "", "Could not start shell: ${e.message}", false, 0, false, mode)
            }
            runCatching { proc.outputStream.close() }
            val outD = async(Dispatchers.IO) { readCapped(proc.inputStream, cap) }
            val errD = async(Dispatchers.IO) { readCapped(proc.errorStream, cap) }
            var exit: Int? = null
            var timedOut = false
            try {
                exit = withTimeoutOrNull(timeoutMs) { runInterruptible(Dispatchers.IO) { proc.waitFor() } }
                if (exit == null) {
                    timedOut = true
                    proc.destroyForcibly()
                }
            } catch (e: CancellationException) {
                proc.destroyForcibly()
                runCatching { proc.inputStream.close() }
                runCatching { proc.errorStream.close() }
                throw e
            }
            if (timedOut) {
                // Grandchildren may keep the pipes open; closing our ends unblocks the readers.
                runCatching { proc.inputStream.close() }
                runCatching { proc.errorStream.close() }
            }
            val (out, tOut) = withTimeoutOrNull(5_000) { outD.await() } ?: ("" to true)
            val (err, tErr) = withTimeoutOrNull(5_000) { errD.await() } ?: ("" to true)
            ShellResult(exit, out, err, timedOut, System.currentTimeMillis() - started, tOut || tErr, mode)
        }
    }

    private fun readCapped(stream: InputStream, cap: Int): Pair<String, Boolean> {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var truncated = false
        try {
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                val room = cap - bos.size()
                if (room > 0) bos.write(buf, 0, minOf(n, room))
                if (n > room) truncated = true
            }
        } catch (_: IOException) { /* stream closed after kill */ }
        return bos.toString(Charsets.UTF_8.name()) to truncated
    }
}
