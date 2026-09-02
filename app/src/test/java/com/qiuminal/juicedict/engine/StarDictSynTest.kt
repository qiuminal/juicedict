package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * StarDict `.syn`（同义词别名）支持测试：
 * chibigenc 等词库词头为繁体，简体别名（如“三军 -> 三軍”）由 `.syn` 提供；
 * 正式词头无命中时，精确/智能查询应回退到 `.syn` 别名。
 */
class StarDictSynTest {

    private fun ifo(wordCount: Long, synWordCount: Long = 0) = Ifo(
        version = "3.0.0",
        bookName = "test",
        wordCount = wordCount,
        idxFileSize = 0,
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

    /** 构造 .idx 字节：每词条 word\0 + u32 BE 偏移 + u32 BE 长度。 */
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

    /** 构造 .syn 字节：别名\0 + u32 BE 词条序号。 */
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
    fun synAliasResolvesToTargetEntry() {
        val idx = StarDictIndex.load(ifo(2, 1), idxBytes("三軍", "apple"), synBytes("三军" to 0))
        val hits = idx.synExactMatches("三军")
        assertEquals(1, hits.size)
        assertEquals("三軍", idx.wordAt(hits.first()))
        assertTrue(idx.synExactMatches("不存在的词").isEmpty())
    }

    @Test
    fun smartLookupFallsBackToSynAlias() {
        val tmp = File.createTempFile("juicedict-test", ".dict")
        tmp.deleteOnExit()
        tmp.writeText("三軍：军队。")
        try {
            val sd = StarDict(
                "test",
                ifo(2, 1),
                StarDictIndex.load(ifo(2, 1), idxBytes("三軍", "apple"), synBytes("三军" to 0)),
                PlainDictReader(tmp),
            )
            // 前缀无命中（词头是繁体“三軍”），应回退到 .syn 别名命中
            val hits = sd.lookupSmart("三军", 60)
            assertEquals(1, hits.size)
            assertEquals("三军", hits.first().word)
            assertTrue(sd.article(hits.first()).preview().isNotEmpty())
            // 精确查询同样回退到别名
            assertEquals(1, sd.lookupExact("三军").size)
            // 正式词头命中时优先于别名
            assertEquals("三軍", sd.lookupSmart("三軍", 60).first().word)
            sd.close()
        } finally {
            tmp.delete()
        }
    }

    /** 真实数据回归：chibigenc 词头为繁体，简体“三军”应经 .syn 命中“三軍”词条。 */
    @Test
    fun realChibigencSynAliasHitsTraditionalHeadword() {
        val dir = findChibigencDir() ?: return
        val ifo = Ifo.parse(File(dir, "chibigenc.ifo").readText())
        val syn = File(dir, "chibigenc.syn").takeIf { it.exists() }?.readBytes() ?: return
        val idx = StarDictIndex.load(ifo, File(dir, "chibigenc.idx").readBytes(), syn)
        val data = DictZipReader(File(dir, "chibigenc.dict.dz"))
        val sd = StarDict("chibigenc", ifo, idx, data)
        try {
            val hits = sd.lookupSmart("三军", 60)
            assertTrue("chibigenc 应经 .syn 命中繁体词头“三軍”", hits.any { it.word == "三军" })
            assertTrue(sd.article(hits.first()).preview().isNotEmpty())
        } finally {
            sd.close()
        }
    }

    private fun findChibigencDir(): File? {
        val candidates = listOf(
            File("../internal-dicts/dict"),
            File("internal-dicts/dict"),
        )
        return candidates.firstOrNull { File(it, "chibigenc.ifo").exists() }
    }
}
