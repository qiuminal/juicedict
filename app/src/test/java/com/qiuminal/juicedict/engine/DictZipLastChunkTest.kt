package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.Deflater

/**
 * dictzip（`.dict.dz`）末块解压回归。
 *
 * 背景：dictzip 的每个 chunk 是独立的 raw deflate 片段，**最后一个块**通常没有
 * 正常的 deflate 流结束标记。旧实现在 `while (!finished && n < buffer.size)`
 * 中只依赖 `finished()`，当末块解压长度不足一个 chunk（真实词典几乎总是如此）
 * 时，`inflate()` 持续返回 0 且 `finished()` 恒为 false，形成死循环，表现为
 * 该块内所有词条查询卡死。
 *
 * 实测案例：朗道汉英词典 langdao-ce-gb 的 chunk 225 只有 46876/58315 字节，
 * “鼎”“鼎力”“鼎沸”等词条全部落在该块。
 */
class DictZipLastChunkTest {

    /** 构造一个 dictzip 文件：defLevel = -1 时模拟末块无流结束标记的独立片段。 */
    private fun buildDictZip(chunks: List<ByteArray>, chunkLength: Int): File {
        val deflated = chunks.map { raw ->
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(raw)
            d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            d.end()
            out.toByteArray()
        }

        val out = ByteArrayOutputStream()
        // gzip 头：FEXTRA | FNAME
        out.write(0x1f)
        out.write(0x8b)
        out.write(8)
        out.write((0x04 or 0x08))
        out.write(ByteArray(6)) // mtime + xfl + os
        // extra field：RA 子字段，4 字节子字段头 + (version2 + chunkLen2 + chunkCount2) + 2*chunkCount 表
        val subLen = 6 + 2 * chunks.size
        val extraLen = 4 + subLen
        out.write(extraLen and 0xff)
        out.write((extraLen ushr 8) and 0xff)
        out.write('R'.code)
        out.write('A'.code)
        out.write(subLen and 0xff)
        out.write((subLen ushr 8) and 0xff)
        out.write(1) // version
        out.write(0)
        out.write(chunkLength and 0xff)
        out.write((chunkLength ushr 8) and 0xff)
        out.write(chunks.size and 0xff)
        out.write((chunks.size ushr 8) and 0xff)
        for (c in deflated) {
            out.write(c.size and 0xff)
            out.write((c.size ushr 8) and 0xff)
        }
        // FNAME
        for (b in "t.dict".toByteArray(Charsets.US_ASCII)) out.write(b.toInt())
        out.write(0)
        for (c in deflated) out.write(c)
        out.write(ByteArray(8)) // crc32 + isize 占位

        val f = Files.createTempFile("dictzip-last", ".dict.dz").toFile()
        f.writeBytes(out.toByteArray())
        return f
    }

    /**
     * 末块长度不足一个 chunk 时必须能正常返回（旧实现会在此死循环）。
     */
    @Test(timeout = 20_000)
    fun lastPartialChunkDoesNotHang() {
        val chunkLength = 64
        val full = ByteArray(chunkLength) { 'a'.toByte() }
        val tailText = "an ancient cooking vessel"
        val tail = tailText.toByteArray(Charsets.UTF_8)
        assertTrue("末块必须短于一个 chunk 才能覆盖该缺陷", tail.size < chunkLength)

        val file = buildDictZip(listOf(full, full, tail), chunkLength)
        try {
            DictZipReader(file).use { reader ->
                // 末块起点 = 2 个完整 chunk
                val offset = 2L * chunkLength
                val got = reader.read(offset, tail.size)
                assertEquals(tailText, String(got, Charsets.UTF_8))
            }
        } finally {
            file.delete()
        }
    }

    /** 完整块与末块连续读取都应正确。 */
    @Test(timeout = 20_000)
    fun readsAcrossFullAndPartialChunks() {
        val chunkLength = 32
        val c0 = ByteArray(chunkLength) { ('0'.code + (it % 10)).toByte() }
        val c1 = ByteArray(chunkLength) { ('A'.code + (it % 26)).toByte() }
        val tailText = "tail"
        val tail = tailText.toByteArray(Charsets.UTF_8)

        val file = buildDictZip(listOf(c0, c1, tail), chunkLength)
        try {
            DictZipReader(file).use { reader ->
                assertEquals(String(c0, Charsets.ISO_8859_1), String(reader.read(0, chunkLength), Charsets.ISO_8859_1))
                // 跨越 c0 末尾与 c1 开头
                val cross = reader.read(chunkLength - 4L, 8)
                assertEquals(8, cross.size)
                // 末块
                assertEquals(tailText, String(reader.read(2L * chunkLength, tail.size), Charsets.UTF_8))
            }
        } finally {
            file.delete()
        }
    }
}
