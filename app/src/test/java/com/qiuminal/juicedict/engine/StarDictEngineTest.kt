package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Engine tests run on the JVM against the bundled CC-CEDICT dictionary,
 * verifying StarDict format parsing and the search behaviors mirrored from
 * sdcv/KOReader. Fuzzy 中英文统一：插入/删除/替换/相邻换位都按同一套编辑
 * 距离计分，乱序、缺字、错字都能命中。
 */
class StarDictEngineTest {

    private val dictDir: File = findDictDir()

    private fun openDict(): StarDict {
        val ifoFile = dictDir.listFiles { f -> f.name.endsWith(".ifo") }!!.first()
        val base = ifoFile.name.removeSuffix(".ifo")
        val ifo = Ifo.parse(ifoFile.readText())
        val idx = StarDictIndex.load(ifo, File(dictDir, "$base.idx").readBytes())
        val data = PlainDictReader(File(dictDir, "$base.dict"))
        return StarDict(base, ifo, idx, data)
    }

    @Test
    fun parsesCcCedict() {
        val sd = openDict()
        assertEquals(525037, sd.wordCount)
        assertEquals("h", sd.ifo.sameTypeSequence)
        sd.close()
    }

    @Test
    fun exactLookup() {
        val sd = openDict()
        val hits = sd.lookupExact("hello")
        assertTrue(hits.isNotEmpty())
        assertEquals("hello", hits.first().word)
        val article = sd.article(hits.first())
        assertTrue(article.toHtml().contains("hello"))
        assertTrue(article.preview().isNotEmpty())
        sd.close()
    }

    @Test
    fun caseInsensitiveLookup() {
        val sd = openDict()
        assertTrue(sd.lookupExact("HELLO").isNotEmpty())
        assertTrue(sd.lookupExact("Hello").isNotEmpty())
        sd.close()
    }

    @Test
    fun chineseLookup() {
        val sd = openDict()
        val hits = sd.lookupExact("你好")
        assertTrue(hits.isNotEmpty())
        val article = sd.article(hits.first())
        assertTrue(article.preview().isNotEmpty())
        sd.close()
    }

    @Test
    fun prefixLookup() {
        val sd = openDict()
        val hits = sd.lookupPrefix("hel", 30)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.word.startsWith("hel", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun smartFallsBackToFuzzy() {
        val sd = openDict()
        // recieve 前缀无命中，自动 fallback 到模糊层并命中 receive
        val hits = sd.lookupSmart("recieve", 60)
        assertTrue(hits.any { it.word.equals("receive", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun smartPrefixFirst() {
        val sd = openDict()
        val hits = sd.lookupSmart("hel", 60)
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.word.startsWith("hel", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun fuzzyFindsNearMiss() {
        val sd = openDict()
        val hits = sd.lookupFuzzy("helo", 30)
        assertTrue(hits.any { it.word.equals("hello", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun fuzzyFindsTransposition() {
        val sd = openDict()
        // recieve -> receive：相邻字母互换（i/e）只算一次换位
        val hits = sd.lookupFuzzy("recieve", 30)
        assertTrue(hits.any { it.word.equals("receive", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun fuzzyChineseMissingChar() {
        val sd = openDict()
        // 一望际 -> 一望无际：成语少打一个字，走插入路径
        val hits = sd.lookupFuzzy("一望际", 50)
        assertTrue(hits.any { it.word == "一望无际" })
        sd.close()
    }

    @Test
    fun fuzzyChineseHomophoneAsSubstitution() {
        val sd = openDict()
        // AA智 -> AA制：制/智 同为替换（1 次），与英文错字同一套逻辑
        val hits = sd.lookupFuzzy("AA智", 50)
        assertTrue(hits.any { it.word == "AA制" })
        sd.close()
    }

    @Test
    fun fuzzyChineseTransposition() {
        val sd = openDict()
        // 一望际无 -> 一望无际：汉字相邻乱序，一次换位
        val hits = sd.lookupFuzzy("一望际无", 50)
        assertTrue(hits.any { it.word == "一望无际" })
        sd.close()
    }

    @Test
    fun fuzzyChineseMixedTransposition() {
        val sd = openDict()
        // 制AA -> AA制：中英混排顺序打错，两次相邻换位
        val hits = sd.lookupFuzzy("制AA", 50)
        assertTrue(hits.any { it.word == "AA制" })
        sd.close()
    }

    @Test
    fun morphologySuffixes() {
        assertEquals(listOf("run", "runn", "runne"), Morphology.expand("running"))
        assertEquals(listOf("studie", "studi", "study"), Morphology.expand("studied"))
        assertEquals(listOf("stoppe", "stop", "stopp"), Morphology.expand("stopped"))
    }

    @Test
    fun articleParsesHtmlSections() {
        val sd = openDict()
        val hits = sd.lookupExact("hello")
        val article = sd.article(hits.first())
        assertEquals(1, article.sections.size)
        assertEquals('h', article.sections.first().type)
        sd.close()
    }


    @Test
    fun missingSpaceSmartLookup() {
        val sd = openDict()
        // 少打两个空格：assyeahright -> ass yeah right
        val hits = sd.lookupSmart("assyeahright", 60)
        assertTrue(hits.any { it.word.equals("ass yeah right", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun missingSpacePartialPrefixFallback() {
        val sd = openDict()
        // 输入到一半漏掉空格：assyeah -> ass yeah right（前缀与模糊层皆空，去空格前缀兜底命中）
        val hits = sd.lookupSmart("assyeah", 60)
        assertTrue(hits.any { it.word.equals("ass yeah right", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun missingSpaceWithTypoFallback() {
        val sd = openDict()
        // 缺空格 + 拼错：assyeahrigt -> ass yeah right（原始词头距离 3 超出模糊上限，
        // 去空格后距离 1，由兜底层的去空格模糊命中）
        val hits = sd.lookupSpaceFree("assyeahrigt", 60)
        assertTrue(hits.any { it.word.equals("ass yeah right", ignoreCase = true) })
        sd.close()
    }

    @Test
    fun spaceFreeSkipsPureChineseMisses() {
        val sd = openDict()
        // 纯中文无结果查询不走去空格扫描，仍返回空（生僻扩展字用例）
        assertTrue(sd.lookupSmart("\uD882\uDD21", 60).isEmpty())
        sd.close()
    }

    @Test
    fun spacedChineseQueryFindsWord() {
        val sd = openDict()
        // 输入里逐字带空格（如从文本复制/输入法误加）：此 话 怎 讲 -> 此话怎讲
        val hits = sd.lookupSmart("此 话 怎 讲", 60)
        assertTrue("spaced Chinese should find 此话怎讲, got " + hits.take(5).map { it.word },
            hits.any { it.word == "此话怎讲" })
        sd.close()
    }
    private companion object {
        fun findDictDir(): File {
            System.getProperty("test.dict.dir")?.let { return File(it) }
            val candidates = listOf(
                File("src/main/assets/dict"),
                File("app/src/main/assets/dict"),
            )
            return candidates.firstOrNull { it.exists() }
                ?: error("dict assets not found; set -Dtest.dict.dir")
        }
    }
}
