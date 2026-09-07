package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 真实大词典（stardict-ecdict-2.4.2：340 万词条，.idx 82MB / .dict 118MB）
 * 的全量回归。复现 v0.1.0 的线上问题：旧索引实现按「每词一个 String +
 * 装箱集合」存放，百万级词条在手机堆上必然 OOM 闪退。
 *
 * 通过环境变量 ECDICT_DIR 指向词典目录时才运行；其余环境（CI 等）静默跳过：
 *
 *     $env:ECDICT_DIR = 'E:\Kindle系统文件备份\词典\stardict-ecdict-2.4.2'
 *     .\gradlew testDebugUnitTest --tests "*.BigEcdictTest"
 */
class BigEcdictTest {

    private val dir: File? = System.getenv("ECDICT_DIR")
        ?.let(::File)
        ?.takeIf { it.isDirectory }

    @Test
    fun `ecdict全量解析排序查询与缓存往返`() {
        val d = dir ?: return // 未提供词典目录：跳过
        val ifoFile = d.listFiles { f -> f.name.endsWith(".ifo") }!!.first()
        val base = ifoFile.name.removeSuffix(".ifo")
        val ifo = Ifo.parse(ifoFile.readText())
        assertEquals(3_402_564L, ifo.wordCount)

        val t0 = System.nanoTime()
        val idx = StarDictIndex.load(ifo, File(d, "$base.idx").inputStream(), null)
        val parseMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(ifo.wordCount.toInt(), idx.size)

        // 搜索序整体有序（全量校验：fold 单调不减）
        var i = 0
        while (i < idx.size - 1) {
            assertTrue("order broken at $i", foldCompare(idx.wordAt(i), idx.wordAt(i + 1)) <= 0)
            i++
        }

        val sd = StarDict("ecdict", ifo, idx, PlainDictReader(File(d, "$base.dict")))
        for (word in listOf("hello", "world", "juice", "test")) {
            val hits = sd.lookupSmart(word, 60)
            assertTrue("no hit for $word", hits.isNotEmpty())
            val article = sd.article(hits.first())
            assertTrue("empty article for $word", article.preview(200).isNotEmpty())
        }

        // .jidx 缓存往返：逐项抽查 + 关键字段全等
        val cache = File.createTempFile("ecdict", ".jidx")
        try {
            val t1 = System.nanoTime()
            StarDictIndex.writeCache(idx, cache, ifo)
            val writeMs = (System.nanoTime() - t1) / 1_000_000
            val cacheLen = cache.length()
            val t2 = System.nanoTime()
            val back = StarDictIndex.loadCache(cache, ifo)!!
            val loadMs = (System.nanoTime() - t2) / 1_000_000
            assertEquals(idx.size, back.size)
            for (k in intArrayOf(0, 1, idx.size / 3, idx.size / 2, idx.size - 2, idx.size - 1)) {
                assertEquals(idx.wordAt(k), back.wordAt(k))
                assertEquals(idx.offsetAt(k), back.offsetAt(k))
                assertEquals(idx.sizeAt(k), back.sizeAt(k))
            }
            val sd2 = StarDict("ecdict", ifo, back, PlainDictReader(File(d, "$base.dict")))
            assertEquals(sd.lookupSmart("hello", 60).map { it.word },
                sd2.lookupSmart("hello", 60).map { it.word })
            println(
                "BigEcdictTest: words=${idx.size} parse=${parseMs}ms " +
                    "writeCache=${writeMs}ms(${cacheLen}B) loadCache=${loadMs}ms"
            )
        } finally {
            cache.delete()
        }
    }
}
