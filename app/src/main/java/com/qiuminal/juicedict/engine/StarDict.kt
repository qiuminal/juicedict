package com.qiuminal.juicedict.engine

/**
 * Facade over one StarDict dictionary: metadata + index + data file.
 *
 * Search behaviors mirror sdcv (the engine behind KOReader's dictionary):
 * case-insensitive exact lookup, prefix ("starlist") lookup, and fuzzy lookup
 * (morphological suffixes first, then bounded edit distance). Fuzzy 中英文统一
 * 一套编辑距离（插入/删除/替换/相邻换位都算 1），不做拼音/模糊音特判，
 * 保证查询速度；乱序、缺字、错字都能命中。
 */
class StarDict(
    val id: String,
    val ifo: Ifo,
    private val index: StarDictIndex,
    private val data: DictDataReader,
) : AutoCloseable {

    data class Hit(val word: String, val offset: Long, val size: Int)

    val wordCount: Int get() = index.size

    fun lookupExact(query: String, limit: Int = 30): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val matches = index.exactMatches(q, limit * 2)
        // Exact (case-sensitive) matches first, then other case variants.
        val exact = ArrayList<Int>(matches.size)
        val variants = ArrayList<Int>(matches.size)
        for (i in matches) {
            if (index.wordAt(i) == q) exact.add(i) else variants.add(i)
        }
        val head = (exact + variants).take(limit).map { Hit(index.wordAt(it), index.offsetAt(it), index.sizeAt(it)) }
        if (head.isNotEmpty()) return head
        return lookupSynExact(q, limit)
    }

    fun lookupPrefix(query: String, limit: Int = 100): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return index.prefixMatches(q, limit).map { Hit(index.wordAt(it), index.offsetAt(it), index.sizeAt(it)) }
    }

    /**
     * 智能查询：前缀优先（前缀命中天然包含精确命中，且字典序下更短的精确词排最前），
     * 前缀无结果时自动 fallback 到模糊查询，用户无感。
     */
    fun lookupSmart(query: String, limit: Int = 60): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val prefix = lookupPrefix(q, limit)
        if (prefix.isNotEmpty()) return prefix
        val syn = lookupSynExact(q)
        if (syn.isNotEmpty()) return syn
        return lookupFuzzy(q, 20)
    }

    /**
     * 同义词别名精确命中（StarDict `.syn`）：chibigenc 词头为繁体，
     * 简体别名（如“三军 -> 三軍”）由 `.syn` 提供。命中时词条按用户输入
     * 的别名显示，内容指向目标词条。
     */
    fun lookupSynExact(query: String, limit: Int = 30): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return index.synExactMatches(q, limit).map { Hit(q, index.offsetAt(it), index.sizeAt(it)) }
    }

    /**
     * sdcv-style fuzzy：先词形还原（running -> run），再做有界编辑距离扫描。
     * 中英文统一：距离 ≤ 2，且不超过两串平均长度（避免“短词被长查询”污染）。
     * 扫描零分配，适合 30 万~50 万词条的全索引扫描。
     */
    fun lookupFuzzy(query: String, limit: Int = 20): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        for (candidate in Morphology.expand(q)) {
            val hits = lookupExact(candidate, 1)
            if (hits.isNotEmpty()) return hits
        }

        val qLen = q.length
        val maxScaled = 20 // 距离 ≤ 2
        val best = ArrayList<Pair<Int, Int>>(limit + 4) // (distance, searchIndex)
        // 复用工作区数组，避免每个候选词都分配 DP 矩阵
        val prevPrev = IntArray(qLen + 1)
        val prev = IntArray(qLen + 1)
        val cur = IntArray(qLen + 1)
        index.forEachWord { i, w ->
            if (kotlin.math.abs(w.length - qLen) <= 2) {
                val d = EditDistance.distance(w, q, maxScaled, prevPrev, prev, cur)
                if (d <= maxScaled && d < (qLen + w.length) * 5) {
                    best.add(d to i)
                    if (best.size > limit * 2) {
                        best.sortBy { it.first }
                        while (best.size > limit) best.removeAt(best.lastIndex)
                    }
                }
            }
        }
        best.sortWith(Comparator { x, y ->
            val byDistance = x.first.compareTo(y.first)
            if (byDistance != 0) return@Comparator byDistance
            // 公共前缀越长越像（helo -> hello）；再比公共后缀（一望际 -> 一望无际）
            val wx = index.wordAt(x.second)
            val wy = index.wordAt(y.second)
            val px = commonPrefixLen(wx, q)
            val py = commonPrefixLen(wy, q)
            if (px != py) return@Comparator py.compareTo(px)
            val sx = commonSuffixLen(wx, q)
            val sy = commonSuffixLen(wy, q)
            if (sx != sy) return@Comparator sy.compareTo(sx)
            stardictCompare(wx, wy)
        })
        return best.take(limit).map { Hit(index.wordAt(it.second), index.offsetAt(it.second), index.sizeAt(it.second)) }
    }

    private fun commonPrefixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && foldAscii(a[i]) == foldAscii(b[i])) i++
        return i
    }

    private fun commonSuffixLen(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && foldAscii(a[a.length - 1 - i]) == foldAscii(b[b.length - 1 - i])) i++
        return i
    }

    fun article(hit: Hit): Article {
        val raw = data.read(hit.offset, hit.size)
        return Article(hit.word, ifo.sameTypeSequence, ArticleParser.parse(ifo.sameTypeSequence, raw))
    }

    override fun close() {
        data.close()
    }
}
