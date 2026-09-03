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
        val fuzzy = lookupFuzzy(q, 20)
        if (fuzzy.isNotEmpty()) return fuzzy
        // 缺空格兜底：标准链（前缀→同义→模糊）全空时，去空格后再查一遍。
        return lookupSpaceFree(q, limit)
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
     * 中英文统一：距离 ≤ 2，且不超过两串的平均码位长度（单字对不相关的单字
     * 因此不会命中——距离 1 不小于平均长度 1）。长度与距离都按 Unicode 码位
     * 计算，代理对（生僻扩展字）整体算 1 个字符。扫描零分配。
     */
    fun lookupFuzzy(query: String, limit: Int = 20): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()

        for (candidate in Morphology.expand(q)) {
            val hits = lookupExact(candidate, 1)
            if (hits.isNotEmpty()) return hits
        }

        val qCp = q.codePointCount(0, q.length) // 码位数：代理对算 1 个字符
        val maxScaled = 20 // 距离 ≤ 2
        val best = ArrayList<Pair<Int, Int>>(limit + 4) // (distance, searchIndex)
        // 复用工作区数组，避免每个候选词都分配 DP 矩阵（按查询码位数计长）
        val prevPrev = IntArray(qCp + 1)
        val prev = IntArray(qCp + 1)
        val cur = IntArray(qCp + 1)
        index.forEachWord { i, w ->
            val wl = w.length
            // 快速必要性过滤：码位数 wCp 满足 ceil(wl/2) ≤ wCp ≤ wl，
            // 仅 wl ∈ [qCp-2, 2(qCp+2)] 的词才可能落在 ±2 码位内。
            if (wl < qCp - 2 || wl > (qCp + 2) * 2) return@forEachWord
            val wCp = w.codePointCount(0, wl)
            if (kotlin.math.abs(wCp - qCp) > 2) return@forEachWord
            val d = EditDistance.distance(w, q, maxScaled, prevPrev, prev, cur)
            if (d <= maxScaled && d < (qCp + wCp) * 5) {
                best.add(d to i)
                if (best.size > limit * 2) {
                    best.sortBy { it.first }
                    while (best.size > limit) best.removeAt(best.lastIndex)
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

    /**
     * 缺空格兜底（懒加载，最后一级）：当 前缀 → 同义 → 模糊 全链皆空时调用。
     * 查询与含空格词头都去掉空格后，按 精确 → 前缀 → 模糊 再查一遍：
     * 英文词组漏打空格（assyeahright -> ass yeah right、assyeah -> ass yeah
     * right），以及输入里逐字带空格的纯中文（此 话 怎 讲 -> 此话怎讲）都能命中。
     * 纯中文且无空格的漏查不做无谓扫描。比较走字符级去空格（零分配），
     * 仅在进入模糊窗口的少量候选上构造去空格字符串，兜底开销很小。
     */
    fun lookupSpaceFree(query: String, limit: Int = 60): List<Hit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val sfq = q.filterNot { it == ' ' }
        var hasAscii = false
        for (c in sfq) {
            if (c in 'A'..'Z' || c in 'a'..'z') { hasAscii = true; break }
        }
        val hasSpace = q.indexOf(' ') >= 0
        // 含英文字母（英文词组漏打空格），或输入本身带空格（如从文本复制的
        // “此 话 怎 讲”）才值得去空格再查；纯中文无空格漏查不扫，避免无谓开销。
        if (sfq.length < 2 || (!hasAscii && !hasSpace)) return emptyList()

        val sfqCp = sfq.codePointCount(0, sfq.length)
        val maxScaled = 20 // 距离 ≤ 2，与 lookupFuzzy 一致
        val fuzzyLimit = 20
        val exact = ArrayList<Int>(4)
        val prefix = ArrayList<Int>(16)
        data class FuzzyEntry(val distance: Int, val searchIndex: Int, val spacefree: String)
        val fuzzy = ArrayList<FuzzyEntry>(fuzzyLimit + 4)

        val prevPrev = IntArray(sfqCp + 1)
        val prev = IntArray(sfqCp + 1)
        val cur = IntArray(sfqCp + 1)

        val n = index.size
        for (i in 0 until n) {
            val w = index.wordAt(i)
            if (spaceFreeEquals(w, sfq)) {
                exact.add(i)
                if (exact.size >= limit) break
            } else if (spaceFreeStartsWith(w, sfq)) {
                prefix.add(i)
                if (prefix.size >= limit) break
            } else {
                val sfLen = countNonSpace(w)
                if (kotlin.math.abs(sfLen - sfqCp) <= 2) {
                    val sf = w.filterNot { it == ' ' }
                    val d = EditDistance.distance(sf, sfq, maxScaled, prevPrev, prev, cur)
                    if (d <= maxScaled && d < (sfqCp + sfLen) * 5) {
                        fuzzy.add(FuzzyEntry(d, i, sf))
                        if (fuzzy.size > fuzzyLimit * 2) {
                            fuzzy.sortBy { it.distance }
                            while (fuzzy.size > fuzzyLimit) fuzzy.removeAt(fuzzy.lastIndex)
                        }
                    }
                }
            }
        }

        fun hitsOf(searchIndexes: List<Int>): List<Hit> =
            searchIndexes.map { Hit(index.wordAt(it), index.offsetAt(it), index.sizeAt(it)) }

        if (exact.isNotEmpty()) return hitsOf(exact.take(limit))
        if (prefix.isNotEmpty()) return hitsOf(prefix.take(limit))
        if (fuzzy.isEmpty()) return emptyList()

        fuzzy.sortWith(Comparator { x, y ->
            val byDistance = x.distance.compareTo(y.distance)
            if (byDistance != 0) return@Comparator byDistance
            val px = commonPrefixLen(x.spacefree, sfq)
            val py = commonPrefixLen(y.spacefree, sfq)
            if (px != py) return@Comparator py.compareTo(px)
            val sx = commonSuffixLen(x.spacefree, sfq)
            val sy = commonSuffixLen(y.spacefree, sfq)
            if (sx != sy) return@Comparator sy.compareTo(sx)
            stardictCompare(index.wordAt(x.searchIndex), index.wordAt(y.searchIndex))
        })
        return fuzzy.take(fuzzyLimit).map {
            Hit(index.wordAt(it.searchIndex), index.offsetAt(it.searchIndex), index.sizeAt(it.searchIndex))
        }
    }

    /** [w] 去空格后是否与 [key]（无空格）忽略大小写相等。零分配。 */
    private fun spaceFreeEquals(w: String, key: String): Boolean {
        var wi = 0
        var ki = 0
        while (ki < key.length) {
            if (wi >= w.length) return false
            val wc = w[wi]
            if (wc == ' ') {
                wi++
                continue
            }
            if (foldAsciiCp(wc.code) != foldAsciiCp(key[ki].code)) return false
            wi++
            ki++
        }
        while (wi < w.length) {
            if (w[wi] != ' ') return false
            wi++
        }
        return true
    }

    /** [w] 去空格后是否以 [key]（无空格）为前缀（忽略大小写）。零分配。 */
    private fun spaceFreeStartsWith(w: String, key: String): Boolean {
        var wi = 0
        var ki = 0
        while (ki < key.length) {
            if (wi >= w.length) return false
            val wc = w[wi]
            if (wc == ' ') {
                wi++
                continue
            }
            if (foldAsciiCp(wc.code) != foldAsciiCp(key[ki].code)) return false
            wi++
            ki++
        }
        return true
    }

    /** [w] 中非空格字符数（即去空格后的码位数，ASCII 空格每字节约 1 个字符）。 */
    private fun countNonSpace(w: String): Int {
        var n = 0
        for (c in w) if (c != ' ') n++
        return n
    }
    private fun commonPrefixLen(a: String, b: String): Int {
        var ai = 0
        var bi = 0
        var len = 0
        while (ai < a.length && bi < b.length) {
            val ca = a.codePointAt(ai)
            val cb = b.codePointAt(bi)
            if (foldAsciiCp(ca) != foldAsciiCp(cb)) break
            val n = Character.charCount(ca)
            ai += n
            bi += n
            len++
        }
        return len
    }

    private fun commonSuffixLen(a: String, b: String): Int {
        var ai = a.length
        var bi = b.length
        var len = 0
        while (ai > 0 && bi > 0) {
            val ca = a.codePointBefore(ai)
            val cb = b.codePointBefore(bi)
            if (foldAsciiCp(ca) != foldAsciiCp(cb)) break
            val n = Character.charCount(ca)
            ai -= n
            bi -= n
            len++
        }
        return len
    }

    private fun foldAsciiCp(cp: Int): Int =
        if (cp in 'A'.code..'Z'.code) cp + 32 else cp

    fun article(hit: Hit): Article {
        val raw = data.read(hit.offset, hit.size)
        return Article(hit.word, ifo.sameTypeSequence, ArticleParser.parse(ifo.sameTypeSequence, raw))
    }

    override fun close() {
        data.close()
    }
}
