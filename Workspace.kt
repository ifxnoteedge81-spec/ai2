package com.personal.aicomputer.runtime

import java.io.File

/**
 * Layout under the app's private storage:
 *   base/workspace  - the ONLY tree agent file tools and shell commands can touch (cwd of the shell)
 *   base/workspace/{downloads,tmp,scripts,projects}
 *   base/memory     - agent memory + task history (app-managed, not exposed to agent file tools)
 *   base/linux      - optional Linux rootfs (managed by RuntimeManager)
 *   base/cache      - app cache
 */
class Workspace(val base: File) {
    val root: File = File(base, "workspace")
    val downloads: File = File(root, "downloads")
    val tmp: File = File(root, "tmp")
    val scripts: File = File(root, "scripts")
    val projects: File = File(root, "projects")
    val memoryDir: File = File(base, "memory")
    val linuxDir: File = File(base, "linux")
    val cacheDir: File = File(base, "cache")

    init {
        listOf(root, downloads, tmp, scripts, projects, memoryDir, linuxDir, cacheDir).forEach { it.mkdirs() }
    }

    private val rootCanonical: String get() = root.canonicalPath

    /**
     * Resolves a user/agent supplied path inside the workspace. Absolute paths are only accepted if they
     * already point inside the workspace. Symlinks are resolved first, so links cannot be used to escape.
     * @throws SecurityException when the result would leave the workspace.
     */
    fun resolve(path: String): File {
        val p = path.trim().ifEmpty { "." }
        val candidate = if (p.startsWith("/")) File(p) else File(root, p)
        val canonical = candidate.canonicalFile
        val rc = rootCanonical
        if (canonical.path != rc && !canonical.path.startsWith(rc + File.separator)) {
            throw SecurityException("Path is outside the workspace: $path")
        }
        return canonical
    }

    fun relative(file: File): String {
        val rc = rootCanonical
        val c = file.canonicalPath
        return when {
            c == rc -> "."
            c.startsWith(rc + File.separator) -> c.substring(rc.length + 1)
            else -> c
        }
    }

    fun sizeOf(dir: File): Long {
        if (!dir.exists()) return 0
        if (dir.isFile) return dir.length()
        var total = 0L
        dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { if (it.isFile) total += it.length() }
        return total
    }

    fun workspaceBytes(): Long = sizeOf(root)
    fun linuxBytes(): Long = sizeOf(linuxDir)

    /** Deletes only the contents of workspace/tmp. Never touches user files elsewhere. */
    fun clearTmp(): Long {
        val freed = sizeOf(tmp)
        tmp.listFiles()?.forEach { it.deleteRecursively() }
        return freed
    }

    fun clearCache(): Long {
        val freed = sizeOf(cacheDir)
        cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        return freed
    }
}
