package com.qiuminal.juicedict.engine

import org.junit.Test
import java.io.File

/**
 * 模糊查询性能基准（JVM）：
 * 对比 CC-CEDICT 与 chibigenc（汉语大词典）的 fuzzy 全索引扫描耗时，
 * 用于定位“输入后转圈”的瓶颈（词典规模 vs 匹配逻辑）。
 * chibigenc 仅在本机 internal-dicts/ 存在时参与基准，仓库内会自动跳过。
 */
class FuzzyPerfTest {

    @Test
    fun benchmarkFuzzyScan() {
        val cases = listOf("recieve", "helo", "一望际", "制AA", "一望际无")
        val dicts = listOf(
            "CC-CEDICT" to openDict("CC-CEDICT-20251102-stardict-mergesyns"),
            "chibigenc" to openDict("chibigenc"),
        )
        for ((name, sd) in dicts) {
            if (sd == null) {
                println("== $name: skipped (files not present) ==")
                continue
            }
            println("== $name (${sd.wordCount} words) ==")
            for (q in cases) {
                repeat(2) { sd.lookupFuzzy(q, 20) } // 预热
                val t0 = System.nanoTime()
                repeat(5) { sd.lookupFuzzy(q, 20) }
                val ms = (System.nanoTime() - t0) / 5.0 / 1_000_000.0
                println("  fuzzy(%-6s): %6.1f ms".format(q, ms))
            }
            sd.close()
        }
    }

    private fun findDictDir(base: String): File? {
        System.getProperty("test.dict.dir")?.let { root ->
            val f = File(root)
            if (File(f, "$base.ifo").exists()) return f
        }
        val candidates = listOf(
            File("src/main/assets/dict"),
            File("app/src/main/assets/dict"),
            File("../internal-dicts/dict"),
            File("internal-dicts/dict"),
        )
        return candidates.firstOrNull { File(it, "$base.ifo").exists() }
    }

    private fun openDict(base: String): StarDict? {
        val dir = findDictDir(base) ?: return null
        val ifoFile = File(dir, "$base.ifo")
        if (!ifoFile.exists()) return null
        val ifo = Ifo.parse(ifoFile.readText())
        val syn = File(dir, "$base.syn").takeIf { it.exists() }?.readBytes()
        val idx = StarDictIndex.load(ifo, File(dir, "$base.idx").readBytes(), syn)
        val data = when {
            File(dir, "$base.dict.dz").exists() -> DictZipReader(File(dir, "$base.dict.dz"))
            File(dir, "$base.dict").exists() -> PlainDictReader(File(dir, "$base.dict"))
            else -> return null
        }
        return StarDict(base, ifo, idx, data)
    }
}
