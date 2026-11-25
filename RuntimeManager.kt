package com.personal.aicomputer.runtime

import android.content.Context
import android.os.Build
import com.personal.aicomputer.security.AppLog
import com.personal.aicomputer.storage.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class RuntimeMode(val label: String) {
    /** Android's own /system/bin/sh (toybox/mksh), confined to the app sandbox and the workspace directory. */
    NATIVE_SANDBOX("native-sandbox"),

    /** Alpine Linux userspace through PRoot (user-space chroot via ptrace). NOT a virtual machine. */
    PROOT_LINUX("proot-linux"),
}

data class RuntimeStatus(
    val mode: RuntimeMode = RuntimeMode.NATIVE_SANDBOX,
    val prootBinaryPresent: Boolean = false,
    val rootfsInstalled: Boolean = false,
    val prootSelfTest: String? = null,
    val busy: Boolean = false,
    val progress: String? = null,
    val abi: String = "",
    val note: String = "",
)

/**
 * Local runtime. Two honest levels:
 *  1. NATIVE_SANDBOX - always available. Android's built-in shell with core utilities. No Python/Node/gcc.
 *  2. PROOT_LINUX   - available only if (a) a PRoot binary was packaged as libproot.so in the APK and
 *     (b) the user installed the Alpine rootfs. Then `apk add python3 nodejs gcc` etc. work (with network).
 *
 * PRoot binaries cannot be downloaded and executed from app storage on Android 10+, because the OS blocks
 * executing writable app files; the executable must ship inside the APK's native library directory.
 */
class RuntimeManager(
    private val context: Context,
    private val workspace: Workspace,
    private val settings: SettingsStore,
    private val log: AppLog,
    private val http: OkHttpClient,
) {
    private val state = MutableStateFlow(RuntimeStatus())
    val status: StateFlow<RuntimeStatus> = state.asStateFlow()

    private val nativeDir: File get() = File(context.applicationInfo.nativeLibraryDir)
    fun prootFile(): File = File(nativeDir, "libproot.so")
    private fun loaderFile(): File = File(nativeDir, "libproot-loader.so")
    fun rootfsDir(): File = File(workspace.linuxDir, "rootfs")

    private fun rootfsReady(): Boolean = File(rootfsDir(), "bin/sh").exists() || java.nio.file.Files.isSymbolicLink(File(rootfsDir(), "bin/sh").toPath())

    fun abi(): String = Build.SUPPORTED_ABIS.firstOrNull().orEmpty()

    private fun alpineArch(): String? = when (abi()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "armv7"
        "x86_64" -> "x86_64"
        else -> null
    }

    fun refresh() {
        val proot = prootFile().exists()
        val rootfs = rootfsReady()
        val wantProot = settings.runtimePreference.value != "native"
        val mode = if (proot && rootfs && wantProot && state.value.prootSelfTest?.startsWith("OK") != false) RuntimeMode.PROOT_LINUX else RuntimeMode.NATIVE_SANDBOX
        val note = when {
            !proot -> "No PRoot binary is bundled in this build, so only the native sandbox is available. See docs/RUNTIME.md."
            !rootfs -> "PRoot is present. Install the Linux environment to enable Python, Node.js and compilers."
            mode == RuntimeMode.PROOT_LINUX -> "Alpine Linux via PRoot is active."
            else -> "Linux environment installed but not active (preference or failed self-test)."
        }
        state.value = state.value.copy(mode = mode, prootBinaryPresent = proot, rootfsInstalled = rootfs, abi = abi(), note = note)
    }

    fun activeMode(): RuntimeMode {
        refresh()
        return state.value.mode
    }

    fun buildProcess(command: String, cwd: File, memoryKb: Long): ProcessBuilder {
        return if (activeMode() == RuntimeMode.PROOT_LINUX) buildProot(command, cwd) else buildNative(command, cwd, memoryKb)
    }

    private fun buildNative(command: String, cwd: File, memoryKb: Long): ProcessBuilder {
        val wrapped = "ulimit -v $memoryKb 2>/dev/null; $command"
        val pb = ProcessBuilder("/system/bin/sh", "-c", wrapped).directory(cwd).redirectErrorStream(false)
        pb.environment().apply {
            clear()
            put("HOME", workspace.root.path)
            put("PATH", "/system/bin:/system/xbin")
            put("TMPDIR", workspace.tmp.path)
            put("TERM", "dumb")
            put("LANG", "C.UTF-8")
        }
        return pb
    }

    private fun buildProot(command: String, cwd: File): ProcessBuilder {
        val rel = workspace.relative(cwd).let { if (it == ".") "" else "/$it" }
        val args = mutableListOf(
            prootFile().path, "--kill-on-exit", "--link2symlink", "-0",
            "-r", rootfsDir().path,
            "-b", "/dev", "-b", "/proc",
            "-b", "${workspace.root.path}:/workspace",
            "-w", "/workspace$rel",
            "/usr/bin/env", "-i",
            "HOME=/root", "TERM=xterm-256color", "LANG=C.UTF-8", "TMPDIR=/workspace/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "/bin/sh", "-c", command,
        )
        val pb = ProcessBuilder(args).directory(workspace.root).redirectErrorStream(false)
        pb.environment().apply {
            clear()
            put("PROOT_TMP_DIR", workspace.tmp.path)
            put("PROOT_NO_SECCOMP", "1")
            if (loaderFile().exists()) put("PROOT_LOADER", loaderFile().path)
        }
        return pb
    }

    /** Runs a tiny command through PRoot and records whether the Linux environment really works on this device. */
    suspend fun selfTest(): String = withContext(Dispatchers.IO) {
        if (!prootFile().exists() || !rootfsReady()) return@withContext "Not installed"
        val pb = buildProot("echo pac-ok && cat /etc/os-release | head -n 1", workspace.root)
        val result = try {
            val p = pb.start()
            val finished = p.waitFor(20, TimeUnit.SECONDS)
            if (!finished) { p.destroyForcibly(); "FAILED: timed out" }
            else {
                val out = p.inputStream.bufferedReader().readText()
                val err = p.errorStream.bufferedReader().readText().take(300)
                if (out.contains("pac-ok")) "OK: ${out.lineSequence().drop(1).firstOrNull().orEmpty()}" else "FAILED: exit=${p.exitValue()} $err"
            }
        } catch (e: IOException) {
            "FAILED: ${e.message}"
        }
        log.i("Runtime", "PRoot self-test: $result")
        state.value = state.value.copy(prootSelfTest = result)
        refresh()
        result
    }

    /** User-initiated. Downloads Alpine minirootfs, verifies its SHA-256 from Alpine's release manifest, extracts it. */
    suspend fun installAlpine(): Result<String> = withContext(Dispatchers.IO) {
        val arch = alpineArch() ?: return@withContext Result.failure(IllegalStateException("Unsupported CPU ABI: ${abi()}"))
        if (!prootFile().exists()) return@withContext Result.failure(IllegalStateException("This build has no PRoot binary (libproot.so). See docs/RUNTIME.md."))
        fun progress(msg: String) { state.value = state.value.copy(busy = true, progress = msg) }
        try {
            progress("Reading Alpine release manifest…")
            val baseUrl = "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$arch/"
            val manifest = httpGetText(baseUrl + "latest-releases.yaml")
            val (fileName, sha256) = parseMiniRootfs(manifest)
                ?: return@withContext Result.failure(IllegalStateException("Could not find alpine-minirootfs (with SHA-256) in the manifest."))

            val tmpFile = File(workspace.cacheDir, fileName)
            progress("Downloading $fileName…")
            download(baseUrl + fileName, tmpFile) { mb -> progress("Downloading $fileName… $mb MB") }

            progress("Verifying checksum…")
            val actual = sha256Hex(tmpFile)
            if (!actual.equals(sha256, ignoreCase = true)) {
                tmpFile.delete()
                return@withContext Result.failure(IllegalStateException("Checksum mismatch. Expected $sha256 but got $actual. Download discarded."))
            }

            progress("Extracting root filesystem…")
            val target = rootfsDir()
            if (target.exists()) target.deleteRecursively()
            val count = tmpFile.inputStream().use { TarExtractor.extractTarGz(it, target) { n -> if (n % 200 == 0) progress("Extracting… $n entries") } }
            tmpFile.delete()

            File(target, "etc").mkdirs()
            File(target, "etc/resolv.conf").writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            listOf("tmp", "proc", "dev", "sys", "workspace", "root").forEach { File(target, it).mkdirs() }
            progress("Running self-test…")
            state.value = state.value.copy(rootfsInstalled = true)
            val test = selfTest()
            state.value = state.value.copy(busy = false, progress = null)
            refresh()
            Result.success("Installed $fileName ($count files). Self-test: $test")
        } catch (e: Exception) {
            log.e("Runtime", "Alpine install failed", e)
            state.value = state.value.copy(busy = false, progress = null)
            Result.failure(e)
        }
    }

    fun removeLinux() {
        rootfsDir().deleteRecursively()
        state.value = state.value.copy(prootSelfTest = null)
        refresh()
    }

    private fun httpGetText(url: String): String {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            return r.body?.string().orEmpty()
        }
    }

    private fun download(url: String, dest: File, onMb: (Long) -> Unit) {
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val body = r.body ?: throw IOException("Empty download")
            var total = 0L
            var lastMb = -1L
            dest.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total > 150L * 1024 * 1024) throw IOException("Download larger than 150 MB, aborting.")
                        val mb = total / (1024 * 1024)
                        if (mb != lastMb) { lastMb = mb; onMb(mb) }
                    }
                }
            }
        }
    }

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { i ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Finds the alpine-minirootfs entry in Alpine's latest-releases.yaml and returns (file, sha256). */
        fun parseMiniRootfs(yaml: String): Pair<String, String>? {
            var file: String? = null
            for (raw in yaml.lineSequence()) {
                val line = raw.trim().removePrefix("-").trim()
                if (line.startsWith("file:")) {
                    val f = line.removePrefix("file:").trim()
                    file = if (f.startsWith("alpine-minirootfs-") && f.endsWith(".tar.gz")) f else null
                } else if (file != null && line.startsWith("sha256:")) {
                    val h = line.removePrefix("sha256:").trim()
                    if (h.length == 64) return file to h
                }
            }
            return null
        }
    }
}
