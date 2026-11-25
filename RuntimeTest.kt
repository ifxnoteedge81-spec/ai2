package com.personal.aicomputer

import com.personal.aicomputer.runtime.RuntimeManager
import com.personal.aicomputer.runtime.TarExtractor
import com.personal.aicomputer.runtime.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class RuntimeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tarEntry(out: ByteArrayOutputStream, name: String, type: Char, content: ByteArray = ByteArray(0), link: String = "", mode: Int = 420) {
        val h = ByteArray(512)
        fun put(off: Int, s: String) { s.toByteArray().copyInto(h, off) }
        put(0, name)
        put(100, "%07o".format(mode))
        put(124, "%011o".format(content.size))
        h[156] = type.code.toByte()
        put(157, link)
        put(257, "ustar")
        out.write(h)
        out.write(content)
        val pad = (512 - content.size % 512) % 512
        out.write(ByteArray(pad))
    }

    private fun gz(block: (ByteArrayOutputStream) -> Unit): ByteArray {
        val raw = ByteArrayOutputStream().also(block)
        raw.write(ByteArray(1024))
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw.toByteArray()) }
        return bos.toByteArray()
    }

    @Test fun extractsFilesDirsAndSymlinks() {
        val data = gz {
            tarEntry(it, "etc/", '5')
            tarEntry(it, "etc/hello.txt", '0', "hi there".toByteArray())
            tarEntry(it, "bin/", '5')
            tarEntry(it, "bin/busybox", '0', "x".toByteArray(), mode = 493)
            tarEntry(it, "bin/sh", '2', link = "/bin/busybox")
        }
        val dest = tmp.newFolder("rootfs")
        TarExtractor.extractTarGz(ByteArrayInputStream(data), dest)
        assertEquals("hi there", dest.resolve("etc/hello.txt").readText())
        assertTrue(Files.isSymbolicLink(dest.resolve("bin/sh").toPath()))
        assertTrue(dest.resolve("bin/busybox").canExecute())
    }

    @Test fun blocksPathTraversal() {
        val data = gz { tarEntry(it, "../evil.txt", '0', "x".toByteArray()) }
        try {
            TarExtractor.extractTarGz(ByteArrayInputStream(data), tmp.newFolder("d"))
            fail("expected traversal to be rejected")
        } catch (e: TarExtractor.TarException) {
            assertTrue(e.message!!.contains("traversal"))
        }
    }

    @Test fun workspaceConfinesPaths() {
        val ws = Workspace(tmp.newFolder("base"))
        ws.resolve("a/b.txt")
        listOf("../x", "/etc/passwd", "a/../../x").forEach {
            try { ws.resolve(it); fail("should reject $it") } catch (_: SecurityException) { }
        }
    }

    @Test fun workspaceBlocksSymlinkEscape() {
        val base = tmp.newFolder("base2")
        val outside = tmp.newFolder("outside")
        val ws = Workspace(base)
        Files.createSymbolicLink(ws.root.toPath().resolve("link"), outside.toPath())
        try { ws.resolve("link/secret.txt"); fail("symlink escape must be rejected") } catch (_: SecurityException) { }
    }

    @Test fun parsesAlpineManifest() {
        val yaml = """
- title: Mini root filesystem
  desc: Minimal root filesystem
  file: alpine-minirootfs-3.22.2-aarch64.tar.gz
  sha256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
  sha512: ffff
- title: Netboot
  file: alpine-netboot-3.22.2-aarch64.tar.gz
  sha256: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
"""
        val (f, h) = RuntimeManager.parseMiniRootfs(yaml)!!
        assertEquals("alpine-minirootfs-3.22.2-aarch64.tar.gz", f)
        assertEquals(64, h.length)
    }
}
