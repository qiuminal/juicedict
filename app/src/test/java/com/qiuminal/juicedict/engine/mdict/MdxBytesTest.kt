package com.qiuminal.juicedict.engine.mdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pins the byte-order helpers. MDict mixes endianness inside one container: the header
 * length and every section/index integer are big-endian, while each block's
 * `compression_type` is little-endian. Reading the block type big-endian yields
 * `0x02000000` instead of `2` and makes every compressed block look corrupt, so the
 * distinction is asserted rather than assumed.
 */
class MdxBytesTest {

    private val sample = byteArrayOf(
        0x12, 0x34, 0x56, 0x78, // 0..3
        0x00, 0x00, 0x00, 0x02, // 4..7
        0xFF.toByte(), 0x01, 0x02, 0x03, // 8..11
    )

    @Test
    fun `reads big endian`() {
        assertEquals(0x12345678, MdxBytes.u32be(sample, 0))
        assertEquals(0x02, MdxBytes.u32be(sample, 4))
        assertEquals(2, MdxBytes.u16be(sample, 6))
    }

    @Test
    fun `reads little endian`() {
        assertEquals(0x78563412, MdxBytes.u32le(sample, 0))
        // Bytes 00 00 00 02 are 2 big-endian but 0x02000000 little-endian, which is exactly
        // the confusion that makes a zlib block look like an unknown compression type.
        assertEquals(0x02000000, MdxBytes.u32le(sample, 4))
    }

    @Test
    fun `little and big endian differ on the same bytes`() {
        val bytes = byteArrayOf(0x00, 0x00, 0x00, 0x02)
        assertEquals(2, MdxBytes.u32be(bytes, 0))
        assertEquals(0x02000000, MdxBytes.u32le(bytes, 0))
    }

    @Test
    fun `reads 64 bit big endian`() {
        val bytes = byteArrayOf(0, 0, 0, 0, 0, 0, 0x01, 0x00)
        assertEquals(256L, MdxBytes.u64be(bytes, 0))
    }

    @Test
    fun `uBe agrees with the typed readers`() {
        assertEquals(MdxBytes.u32be(sample, 0).toLong(), MdxBytes.uBe(sample, 0, 4))
        assertEquals(MdxBytes.u16be(sample, 6).toLong(), MdxBytes.uBe(sample, 6, 2))
        assertEquals(MdxBytes.u8(sample, 0).toLong(), MdxBytes.uBe(sample, 0, 1))
    }
}

/**
 * Pins how companion `.mdd` files are matched to an `.mdx`. Multi-part resource packs are
 * the norm in this corpus — 《字源》 keeps 43MB of scans in `.1.mdd` and 《新华字典12》
 * keeps its audio in `.1.mdd` — and a resource lookup that misses the later parts silently
 * shows a broken image instead of failing loudly.
 */
class MdxResourceFilesTest {

    private fun tempDir(): File =
        Files.createTempDirectory("mdd-pair").toFile().also { it.deleteOnExit() }

    private fun touch(dir: File, vararg names: String) {
        for (name in names) {
            File(dir, name).writeBytes(byteArrayOf(1))
        }
    }

    private fun names(files: List<File>) = files.map { it.name }

    @Test
    fun `pairs the plain mdd first then numbered parts in order`() {
        val dir = tempDir()
        touch(dir, "字源 (2012).mdx", "字源 (2012).mdd", "字源 (2012).1.mdd", "字源 (2012).2.mdd")
        assertEquals(
            listOf("字源 (2012).mdd", "字源 (2012).1.mdd", "字源 (2012).2.mdd"),
            names(MdxResourceFiles.pairsFor(dir, "字源 (2012)")),
        )
    }

    @Test
    fun `orders numbered parts numerically not lexically`() {
        val dir = tempDir()
        touch(dir, "d.mdd", "d.2.mdd", "d.10.mdd", "d.1.mdd")
        // Lexical order would place d.10.mdd before d.2.mdd and break resource offsets.
        assertEquals(
            listOf("d.mdd", "d.1.mdd", "d.2.mdd", "d.10.mdd"),
            names(MdxResourceFiles.pairsFor(dir, "d")),
        )
    }

    @Test
    fun `works when only numbered parts exist`() {
        val dir = tempDir()
        touch(dir, "d.1.mdd", "d.2.mdd")
        assertEquals(listOf("d.1.mdd", "d.2.mdd"), names(MdxResourceFiles.pairsFor(dir, "d")))
    }

    @Test
    fun `ignores non numeric middle segments`() {
        val dir = tempDir()
        touch(dir, "d.mdd", "d.images.mdd", "d.extra.mdd")
        // Only a purely numeric ordinal counts as a continuation part.
        assertEquals(listOf("d.mdd"), names(MdxResourceFiles.pairsFor(dir, "d")))
    }

    @Test
    fun `does not pick up another dictionary's mdd`() {
        val dir = tempDir()
        touch(dir, "a.mdd", "a.1.mdd", "ab.mdd", "b.mdd", "b.1.mdd")
        assertEquals(listOf("a.mdd", "a.1.mdd"), names(MdxResourceFiles.pairsFor(dir, "a")))
    }

    @Test
    fun `returns empty when there is no mdd`() {
        val dir = tempDir()
        touch(dir, "d.mdx")
        assertTrue(MdxResourceFiles.pairsFor(dir, "d").isEmpty())
    }

    @Test
    fun `returns empty for a missing directory`() {
        assertTrue(MdxResourceFiles.pairsFor(File("Z:\\no-such-dir-xyz"), "d").isEmpty())
    }

    @Test
    fun `skips directories named like a resource pack`() {
        val dir = tempDir()
        File(dir, "d.mdd").mkdirs()
        assertTrue(MdxResourceFiles.pairsFor(dir, "d").isEmpty())
    }

    @Test
    fun `matches the real corpus pairing`() {
        val root = File("E:\\dictionary\\MDict")
        if (!root.isDirectory) return
        val mdx = root.walkTopDown()
            .firstOrNull { it.isFile && it.name == "新华字典12.mdx" } ?: return
        val base = mdx.nameWithoutExtension
        val parts = names(MdxResourceFiles.pairsFor(mdx.parentFile!!, base))
        assertTrue("expected at least one part, got $parts", parts.isNotEmpty())
        assertEquals(base + ".mdd", parts.first())
    }

    @Test
    fun `resource pack open rejects a non resource file`() {
        // An .mdx opened as a resource pack must be refused rather than misparsed: the two
        // differ in key encoding unit and in whether the root tag is Library_Data.
        val root = File("E:\\dictionary\\MDict")
        if (!root.isDirectory) return
        val mdx = root.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".mdx") } ?: return
        val thrown = runCatching { MdxResourcePack(mdx).use { } }.exceptionOrNull()
        assertTrue("expected a rejection, got ${thrown?.message}", thrown is IllegalArgumentException)
    }

    @Test
    fun `missing resource returns null rather than throwing`() {
        val root = File("E:\\dictionary\\MDict")
        if (!root.isDirectory) return
        val mdd = root.walkTopDown().firstOrNull { it.isFile && it.name.endsWith(".mdd") } ?: return
        MdxResourcePack(mdd).use { pack ->
            assertNull(pack.read("\\definitely-not-here.png"))
        }
    }
}
