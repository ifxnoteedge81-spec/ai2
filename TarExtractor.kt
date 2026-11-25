package com.personal.aicomputer.runtime

import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

/**
 * Minimal, dependency-free .tar.gz extractor (ustar + GNU long names + PAX path/linkpath), enough for
 * Alpine's minirootfs. Includes zip-slip protection and refuses to write through symlinked directories.
 */
object TarExtractor {
    class TarException(message: String) : IOException(message)

    fun extractTarGz(input: InputStream, destDir: File, onEntry: (Int) -> Unit = {}): Int {
        destDir.mkdirs()
        val destPath: Path = destDir.toPath().toAbsolutePath().normalize()
        val tar = GZIPInputStream(BufferedInputStream(input, 64 * 1024), 64 * 1024)
        val header = ByteArray(512)
        var longName: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var count = 0

        while (true) {
            if (!readFully(tar, header)) break
            if (header.all { it.toInt() == 0 }) break

            val name = cString(header, 0, 100)
            val mode = octal(header, 100, 8).toInt()
            val size = octal(header, 124, 12)
            val type = header[156].toInt().toChar()
            val linkName = cString(header, 157, 100)
            val isUstar = String(header, 257, 5, Charsets.US_ASCII) == "ustar"
            val prefix = if (isUstar) cString(header, 345, 155) else ""

            when (type) {
                'L' -> { longName = readString(tar, size); skipPadding(tar, size); continue }
                'K' -> { skipExact(tar, size); skipPadding(tar, size); continue }
                'x' -> {
                    val rec = readString(tar, size); skipPadding(tar, size)
                    val (p, l) = parsePax(rec)
                    paxPath = p ?: paxPath
                    paxLink = l ?: paxLink
                    continue
                }
                'g' -> { skipExact(tar, size); skipPadding(tar, size); continue }
            }

            val fullName = (longName ?: paxPath ?: if (prefix.isNotEmpty()) "$prefix/$name" else name)
                .trimStart('/').removePrefix("./")
            val effectiveLink = paxLink ?: linkName
            longName = null; paxPath = null; paxLink = null

            if (fullName.isEmpty() || fullName == ".") { skipExact(tar, size); skipPadding(tar, size); continue }

            val target = destPath.resolve(fullName).normalize()
            if (!target.startsWith(destPath)) throw TarException("Blocked path traversal entry: $fullName")
            if (parentChainHasSymlink(destPath, target)) {
                skipExact(tar, size); skipPadding(tar, size); continue
            }

            when (type) {
                '5' -> {
                    Files.createDirectories(target)
                    val f = target.toFile()
                    f.setReadable(true, false); f.setWritable(true, true); f.setExecutable(true, false)
                }
                '2' -> {
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    Files.createSymbolicLink(target, Paths.get(effectiveLink))
                }
                '1' -> {
                    Files.createDirectories(target.parent)
                    val src = destPath.resolve(effectiveLink.trimStart('/')).normalize()
                    if (src.startsWith(destPath) && Files.isRegularFile(src)) {
                        Files.copy(src, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
                '0', '\u0000', '7' -> {
                    Files.createDirectories(target.parent)
                    Files.deleteIfExists(target)
                    target.toFile().outputStream().use { out ->
                        var left = size
                        val buf = ByteArray(64 * 1024)
                        while (left > 0) {
                            val n = tar.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw TarException("Unexpected end of archive in $fullName")
                            out.write(buf, 0, n)
                            left -= n
                        }
                    }
                    val f = target.toFile()
                    f.setReadable(true, false)
                    if (mode and 0b001_001_001 != 0) f.setExecutable(true, false)
                    f.setWritable(true, true)
                    skipPadding(tar, size)
                    count++
                    onEntry(count)
                    continue
                }
                else -> { /* devices, fifos etc. are skipped */ }
            }
            skipExact(tar, if (type == '5' || type == '2' || type == '1') 0 else size)
            skipPadding(tar, if (type == '5' || type == '2' || type == '1') 0 else size)
            count++
            onEntry(count)
        }
        return count
    }

    private fun parentChainHasSymlink(root: Path, target: Path): Boolean {
        var p: Path? = target.parent
        while (p != null && p.startsWith(root) && p != root) {
            if (Files.isSymbolicLink(p)) return true
            p = p.parent
        }
        return false
    }

    private fun readFully(i: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = i.read(buf, off, buf.size - off)
            if (n < 0) return off != 0 && throw TarException("Truncated tar header")
            off += n
        }
        return true
    }

    private fun skipExact(i: InputStream, n: Long) {
        var left = n
        val buf = ByteArray(8192)
        while (left > 0) {
            val r = i.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (r < 0) throw TarException("Unexpected end of archive")
            left -= r
        }
    }

    private fun skipPadding(i: InputStream, size: Long) {
        val rem = (512 - (size % 512)) % 512
        if (rem > 0) skipExact(i, rem)
    }

    private fun readString(i: InputStream, size: Long): String {
        val b = ByteArray(size.toInt())
        var off = 0
        while (off < b.size) {
            val n = i.read(b, off, b.size - off)
            if (n < 0) throw TarException("Unexpected end of archive")
            off += n
        }
        return String(b, Charsets.UTF_8).trimEnd('\u0000', '\n')
    }

    private fun cString(b: ByteArray, off: Int, len: Int): String {
        var end = off
        while (end < off + len && b[end].toInt() != 0) end++
        return String(b, off, end - off, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, off: Int, len: Int): Long {
        var v = 0L
        var seen = false
        for (k in off until off + len) {
            val c = b[k].toInt()
            if (c in '0'.code..'7'.code) { v = v * 8 + (c - '0'.code); seen = true }
            else if (seen) break
        }
        return v
    }

    private fun parsePax(rec: String): Pair<String?, String?> {
        var path: String? = null
        var link: String? = null
        for (line in rec.split('\n')) {
            val sp = line.indexOf(' ')
            val eq = line.indexOf('=')
            if (sp < 0 || eq < sp) continue
            val key = line.substring(sp + 1, eq)
            val value = line.substring(eq + 1)
            if (key == "path") path = value
            if (key == "linkpath") link = value
        }
        return path to link
    }
}
