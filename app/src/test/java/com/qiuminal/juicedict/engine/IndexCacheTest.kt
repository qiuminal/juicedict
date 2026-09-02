package com.qiuminal.juicedict.engine

import com.qiuminal.juicedict.data.MatchRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/** 预建索引缓存（.jidx）与候选全局排序（精确 > 前缀 > 模糊）测试。 */
class IndexCacheTest {

    private fun ifo(wordCount: Long, synWordCount: Long = 0, idxFileSize: Long = 0) = Ifo(
        version = "3.0.0",
        bookName = "test",
        wordCount = wordCount,
        idxFileSize = idxFileSize,
        sameTypeSequence = "h",
        synWordCount = synWordCount,
        idxOffsetBits = 32,
        dictType = null,
        author = null,
        email = null,
        website = null,
        date = null,
        description = null,
    )

    private fun writeIntBE(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write(v and 0xff)
    }

    private fun idxBytes(vararg words: String): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        for (w in words) {
            val bytes = w.toByteArray(StandardCharsets.UTF_8)
            out.write(bytes)
            out.write(0)
            writeIntBE(out, offset)
            writeIntBE(out, bytes.size)
            offset += bytes.size + 1
        }
        return out.toByteArray()
    }

    private fun synBytes(vararg pairs: Pair<String, Int>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((alias, entry) in pairs) {
            out.write(alias.toByteArray(StandardCharsets.UTF_8))
            out.write(0)
            writeIntBE(out, entry)
        }
        return out.toByteArray()
    }

    @Test
    fun cacheRoundTripPreservesIndex() {
        val meta = ifo(2, 1, idxFileSize = 1234)
        val index = StarDictIndex.load(meta, idxBytes("三軍", "apple"), synBytes("三军" to 0))
        val cache = File.createTempFile("juicedict-cache", ".jidx")
        cache.deleteOnExit()
        StarDictIndex.writeCache(index, cache, meta)

        val loaded = StarDictIndex.loadCache(cache, meta)!!
        assertEquals(index.size, loaded.size)
        assertEquals(index.wordAt(0), loaded.wordAt(0))
        assertEquals(index.offsetAt(0), loaded.offsetAt(0))
        assertEquals(index.sizeAt(0), loaded.sizeAt(0))
        // 排序保持：二分查找行为一致
        assertEquals(index.lowerBound("apple"), loaded.lowerBound("apple"))
        assertEquals(index.exactMatches("apple"), loaded.exactMatches("apple"))
        // syn 别名行为一致
        assertEquals(index.synExactMatches("三军"), loaded.synExactMatches("三军"))
    }

    @Test
    fun cacheInvalidatedWhenMetadataChanges() {
        val meta = ifo(2, 1, idxFileSize = 1234)
        val index = StarDictIndex.load(meta, idxBytes("三軍", "apple"), synBytes("三军" to 0))
        val cache = File.createTempFile("juicedict-cache", ".jidx")
        cache.deleteOnExit()
        StarDictIndex.writeCache(index, cache, meta)

        // 词典文件变化（idxfilesize 不同）→ 缓存失效
        assertNull(StarDictIndex.loadCache(cache, ifo(2, 1, idxFileSize = 9999)))
        // 词条数变化 → 缓存失效
        assertNull(StarDictIndex.loadCache(cache, ifo(3, 1, idxFileSize = 1234)))
        // 别名数变化 → 缓存失效
        assertNull(StarDictIndex.loadCache(cache, ifo(2, 2, idxFileSize = 1234)))
    }

    @Test
    fun corruptCacheReturnsNull() {
        val cache = File.createTempFile("juicedict-cache", ".jidx")
        cache.deleteOnExit()
        cache.writeText("not a cache file")
        assertNull(StarDictIndex.loadCache(cache, ifo(2)))
        cache.writeBytes(ByteArray(16) { 0x7f })
        assertNull(StarDictIndex.loadCache(cache, ifo(2)))
    }

    @Test
    fun matchRankOrdersExactPrefixFuzzy() {
        assertEquals(MatchRank.EXACT, MatchRank.of("三军", "三军"))
        assertEquals(MatchRank.EXACT, MatchRank.of("hello", "HELLO"))
        assertEquals(MatchRank.EXACT, MatchRank.of("三军", "三军")) // .syn 别名命中 word == query
        assertEquals(MatchRank.PREFIX, MatchRank.of("三军", "三军用命"))
        assertEquals(MatchRank.PREFIX, MatchRank.of("hel", "hello"))
        assertEquals(MatchRank.FUZZY, MatchRank.of("一望际", "一望无际"))
        assertEquals(MatchRank.FUZZY, MatchRank.of("制AA", "AA制"))
    }

    /** 真实数据回归：chibigenc 预建缓存加载后行为与原解析一致，且“三军”仍可命中。 */
    @Test
    fun realChibigencCacheRoundTrip() {
        val dir = File("../internal-dicts/dict")
        if (!File(dir, "chibigenc.ifo").exists()) return
        val ifo = Ifo.parse(File(dir, "chibigenc.ifo").readText())
        val idx = StarDictIndex.load(
            ifo,
            File(dir, "chibigenc.idx").readBytes(),
            File(dir, "chibigenc.syn").readBytes(),
        )
        val cache = File.createTempFile("chibigenc-cache", ".jidx")
        cache.deleteOnExit()
        StarDictIndex.writeCache(idx, cache, ifo)
        val loaded = StarDictIndex.loadCache(cache, ifo)!!
        assertEquals(idx.size, loaded.size)
        assertEquals(idx.synExactMatches("三军"), loaded.synExactMatches("三军"))
        assertTrue(loaded.synExactMatches("三军").isNotEmpty())
        // 抽查几个位置的词条一致
        for (s in intArrayOf(0, 1000, 100000, idx.size - 1)) {
            assertEquals(idx.wordAt(s), loaded.wordAt(s))
            assertEquals(idx.offsetAt(s), loaded.offsetAt(s))
            assertEquals(idx.sizeAt(s), loaded.sizeAt(s))
        }
    }

    @Test
    fun cacheSizeStaysReasonable() {
        val dir = File("../internal-dicts/dict")
        if (!File(dir, "chibigenc.ifo").exists()) return
        val ifo = Ifo.parse(File(dir, "chibigenc.ifo").readText())
        val idx = StarDictIndex.load(
            ifo,
            File(dir, "chibigenc.idx").readBytes(),
            File(dir, "chibigenc.syn").readBytes(),
        )
        val cache = File.createTempFile("chibigenc-cache", ".jidx")
        cache.deleteOnExit()
        StarDictIndex.writeCache(idx, cache, ifo)
        // 含预排序数组与 UTF-16 词条，容量合理即可（远小于 .dict.dz 的 68MB）
        assertTrue("cache=${cache.length()}", cache.length() in 1..(20L * 1024 * 1024))
    }
}
