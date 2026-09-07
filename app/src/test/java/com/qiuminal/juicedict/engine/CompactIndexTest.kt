package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

/**
 * v0.1.1 紧凑索引（StarDictIndex 重写）的行为回归：
 * 排序稳定性、大小写折叠、64 位偏移、未排序输入、截断容错、缓存往返。
 */
class CompactIndexTest {

    private fun entry(word: String, offset: Long, size: Int, bits64: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(word.toByteArray(Charsets.UTF_8))
        out.write(0)
        if (bits64) {
            for (i in 7 downTo 0) out.write(((offset ushr (i * 8)) and 0xff).toInt())
            for (i in 7 downTo 0) out.write(((size.toLong() ushr (i * 8)) and 0xff).toInt())
        } else {
            for (i in 3 downTo 0) out.write(((offset ushr (i * 8)) and 0xff).toInt())
            for (i in 3 downTo 0) out.write(((size ushr (i * 8)) and 0xff).toInt())
        }
        return out.toByteArray()
    }

    private fun idxBytes(bits64: Boolean = false, entries: List<Pair<String, Long>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((w, off) in entries) out.write(entry(w, off, w.length, bits64))
        return out.toByteArray()
    }

    private fun ifo(count: Int, idxSize: Long, bits64: Boolean = false) = Ifo(
        version = "2.4.2", bookName = "t", wordCount = count.toLong(), idxFileSize = idxSize,
        sameTypeSequence = "m", synWordCount = 0,
        idxOffsetBits = if (bits64) 64 else 32,
        dictType = null, author = null, email = null, website = null,
        date = null, description = null,
    )

    @Test
    fun `合成大词表的精确前缀与排序正确性`() {
        val n = 120_000
        val entries = ArrayList<Pair<String, Long>>(n + 8)
        // 乱序写入（i 反转）：解析器不能依赖 .idx 文件序
        for (i in n - 1 downTo 0) entries.add("w%05d".format(i) to i.toLong() * 10)
        entries.add("Hello" to 1_000_000L)
        entries.add("hello" to 1_000_001L)
        entries.add("HELLO" to 1_000_002L)
        entries.add("韭菜盒子" to 1_000_003L)
        entries.add("\uD882\uDDAD生僻" to 1_000_004L) // 增补平面代理对
        val bytes = idxBytes(entries = entries)
        val meta = ifo(entries.size, bytes.size.toLong())
        val idx = StarDictIndex.load(meta, bytes)
        assertEquals(entries.size, idx.size)

        // 搜索序整体有序
        var i = 0
        while (i < idx.size - 1) {
            assertTrue("at $i", foldCompare(idx.wordAt(i), idx.wordAt(i + 1)) <= 0)
            i += 997 // 抽查步进
        }
        // 大小写折叠：一次取到三个变体，且保持文件序（稳定排序）
        val matches = idx.exactMatches("hello")
        assertEquals(listOf("Hello", "hello", "HELLO"), matches.map { idx.wordAt(it) })
        // 前缀（w00000..w00099 恰好 100 词，limit=100 应取满）
        val prefix = idx.prefixMatches("w000", 100)
        assertTrue(prefix.size == 100)
        for (si in prefix) assertTrue(idx.wordAt(si).startsWith("w000"))
        // 偏移/长度
        assertEquals(1_000_003L, idx.offsetAt(idx.exactMatches("韭菜盒子").first()))
        assertEquals("\uD882\uDDAD生僻".length, idx.sizeAt(idx.exactMatches("\uD882\uDDAD生僻").first()))
        // 未排序输入也能精确命中
        assertEquals("w00000", idx.wordAt(idx.exactMatches("W00000").first()))
    }

    @Test
    fun `缓存往返完全一致`() {
        val entries = listOf(
            "apple" to 5L, "Apple" to 6L, "APPLE" to 7L, "banana" to 8L,
            "三軍" to 9L, "\uD882\uDDAD" to 10L, "zebra" to 11L,
        )
        val bytes = idxBytes(entries = entries)
        val meta = ifo(entries.size, bytes.size.toLong())
        val idx = StarDictIndex.load(meta, bytes)
        val cache = File.createTempFile("compact", ".jidx")
        try {
            StarDictIndex.writeCache(idx, cache, meta)
            val back = StarDictIndex.loadCache(cache, meta)!!
            assertEquals(idx.size, back.size)
            for (i in 0 until idx.size) {
                assertEquals(idx.wordAt(i), back.wordAt(i))
                assertEquals(idx.offsetAt(i), back.offsetAt(i))
                assertEquals(idx.sizeAt(i), back.sizeAt(i))
            }
        } finally {
            cache.delete()
        }
    }

    @Test
    fun `64位偏移解析`() {
        val big = 0x1_0000_0000L // 大于 32 位
        val entries = listOf("aaa" to big, "bbb" to big + 123)
        val bytes = idxBytes(bits64 = true, entries = entries)
        val meta = ifo(entries.size, bytes.size.toLong(), bits64 = true)
        val idx = StarDictIndex.load(meta, bytes)
        assertEquals(big, idx.offsetAt(idx.exactMatches("aaa").first()))
        assertEquals(big + 123, idx.offsetAt(idx.exactMatches("bbb").first()))
    }

    @Test
    fun `截断的idx丢弃残词条`() {
        val full = idxBytes(entries = listOf("aa" to 1L, "bb" to 2L, "cc" to 3L))
        val truncated = full.copyOf(full.size - 4) // 最后一个词条的偏移+长度缺 4 字节
        val meta = ifo(3, full.size.toLong())
        val idx = StarDictIndex.load(meta, truncated)
        assertEquals(2, idx.size)
        assertTrue(idx.exactMatches("cc").isEmpty())
        assertEquals("bb", idx.wordAt(idx.exactMatches("bb").first()))
    }

    @Test
    fun `流式入口与字节入口等价`() {
        val entries = listOf("dog" to 4L, "cat" to 5L, "cow" to 6L)
        val bytes = idxBytes(entries = entries)
        val meta = ifo(entries.size, bytes.size.toLong())
        val a = StarDictIndex.load(meta, bytes)
        val b = StarDictIndex.load(meta, ByteArrayInputStream(bytes), null)
        assertEquals(a.size, b.size)
        for (i in 0 until a.size) {
            assertEquals(a.wordAt(i), b.wordAt(i))
            assertEquals(a.offsetAt(i), b.offsetAt(i))
        }
    }

    @Test
    fun `syn别名解析与命中`() {
        val entries = listOf("三軍" to 1L, "apple" to 2L)
        val idxBytesRaw = idxBytes(entries = entries)
        val synOut = ByteArrayOutputStream()
        synOut.write("三军".toByteArray(Charsets.UTF_8)); synOut.write(0)
        for (i in 3 downTo 0) synOut.write((0 ushr (i * 8)) and 0xff)
        val meta = ifo(entries.size, idxBytesRaw.size.toLong())
        val idx = StarDictIndex.load(meta, ByteArrayInputStream(idxBytesRaw), ByteArrayInputStream(synOut.toByteArray()))
        val hits = idx.synExactMatches("三军")
        assertEquals(1, hits.size)
        assertEquals("三軍", idx.wordAt(hits.first()))
    }

    @Test
    fun `ifo少报词条数时扩容解析`() {
        // .ifo 声称 2 条，实际 5 条：解析器应扩容而不是丢词
        val entries = listOf("a1" to 1L, "b1" to 2L, "c1" to 3L, "d1" to 4L, "e1" to 5L)
        val bytes = idxBytes(entries = entries)
        val meta = ifo(2, bytes.size.toLong())
        val idx = StarDictIndex.load(meta, bytes)
        assertEquals(5, idx.size)
        for ((w, _) in entries) assertTrue("missing $w", idx.exactMatches(w).isNotEmpty())
    }
}
