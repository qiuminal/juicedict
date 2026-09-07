package com.qiuminal.juicedict.engine

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

/**
 * In-memory StarDict `.idx` index.
 *
 * The `.idx` file is a sequence of entries:
 *   word  (UTF-8, NUL terminated), offset (u32/u64 BE), size (u32/u64 BE)
 * sorted by `stardict_strcmp`. We keep the words and offsets in file order and
 * additionally keep a stable order sorted by [foldCompare] so that both exact
 * and prefix lookup are case-insensitive and allocation-free.
 *
 * ## 内存布局（v0.1.1 重写：支持百万级词条）
 *
 * 大词典（如 340 万词的 ecdict，`.idx` 82MB）在手机上不能按「每词一个
 * String 对象 + 装箱集合」存放——那需要 500MB+ 堆，必然 OOM 闪退。
 * 这里改用紧凑布局：全部词条的 UTF-16 字符连续存放在一个 [wordsData]
 * 缓冲里，配套原始类型数组——
 *
 * | 数组 | 大小 | 说明 |
 * | --- | --- | --- |
 * | wordsData | 总字符数 | 所有词头连续存放（UTF-16） |
 * | wordStarts | n+1 | 词 i = wordsData[starts[i], starts[i+1])，末位哨兵=总长 |
 * | offsets / sizes | n | .idx 文件序的偏移与长度 |
 * | order | n | 搜索序（按 [foldCompare] 稳定排序的词条下标） |
 *
 * 340 万词 ≈ 190MB 常驻（旧实现 >500MB），配合 largeHeap 可在主流机型加载。
 * 排序用自绘的原地归并（原始 IntArray，无装箱）；解析直接从 InputStream
 * 流式读取（不把整个 .idx 读进内存）；`.jidx` 缓存读写同样流式化，
 * 文件格式与 v1 完全一致——旧缓存无需重建。
 */
class StarDictIndex private constructor(
    private val wordsData: CharArray,
    private val wordStarts: IntArray,
    private val offsets: LongArray,
    private val sizes: IntArray,
    private val order: IntArray,
    /** 折叠后的同义词别名（StarDict `.syn`）-> .idx 词条序号（文件顺序）。 */
    private val synAliases: HashMap<String, Int>,
    /** .idx 词条序号 -> 搜索序号（order 中的位置）。 */
    private val entryToSearch: IntArray,
) {

    val size: Int get() = order.size

    fun wordAt(searchIndex: Int): String {
        val e = order[searchIndex]
        return String(wordsData, wordStarts[e], wordStarts[e + 1] - wordStarts[e])
    }

    fun offsetAt(searchIndex: Int): Long = offsets[order[searchIndex]]

    fun sizeAt(searchIndex: Int): Int = sizes[order[searchIndex]]

    /** First search index where [foldCompare](word, key) >= 0. */
    fun lowerBound(key: String): Int {
        var lo = 0
        var hi = order.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            val e = order[mid]
            if (foldCompare(wordsData, wordStarts[e], wordStarts[e + 1], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** All entries whose folded form equals the folded query (case variants included). */
    fun exactMatches(key: String, limit: Int = 30): List<Int> {
        val out = ArrayList<Int>(minOf(limit, 16))
        var i = lowerBound(key)
        while (i < order.size && out.size < limit) {
            val e = order[i]
            if (foldCompare(wordsData, wordStarts[e], wordStarts[e + 1], key) != 0) break
            out.add(i)
            i++
        }
        return out
    }

    /**
     * 同义词别名精确命中（如繁体词库 chibigenc 里的简体别名“三军 -> 三軍”）。
     * 返回目标词条对应的搜索序号；无则返回空列表。
     */
    fun synExactMatches(key: String, limit: Int = 30): List<Int> {
        val entry = synAliases[key.lowercase()] ?: return emptyList()
        val searchIndex = entryToSearch[entry]
        return if (searchIndex >= 0) listOf(searchIndex) else emptyList()
    }

    /** Entries starting with [prefix] (case-insensitive), in index order. */
    fun prefixMatches(prefix: String, limit: Int = 100): List<Int> {
        val out = ArrayList<Int>(minOf(limit, 16))
        var i = lowerBound(prefix)
        while (i < order.size && out.size < limit) {
            val e = order[i]
            if (!regionStartsWithFold(wordsData, wordStarts[e], wordStarts[e + 1], prefix)) break
            out.add(i)
            i++
        }
        return out
    }

    fun forEachWord(action: (searchIndex: Int, word: String) -> Unit) {
        for (i in 0 until order.size) action(i, wordAt(i))
    }

    companion object {
        private const val CACHE_MAGIC = 0x4A444958 // "JDIX"
        private const val CACHE_VERSION = 1

        /** 防御性上限：.ifo 声称的词条数超过此值视为损坏数据。 */
        private const val MAX_ENTRIES = 50_000_000

        // ------------------------------------------------------------------
        // 解析：.idx / .syn 流式读取（兼容旧的 ByteArray 入口）
        // ------------------------------------------------------------------

        /** 兼容入口：小词典/测试直接给字节数组（内部包装为流）。 */
        fun load(ifo: Ifo, idxBytes: ByteArray, synBytes: ByteArray? = null): StarDictIndex =
            load(ifo, ByteArrayInputStream(idxBytes), synBytes?.let { ByteArrayInputStream(it) })

        /**
         * 流式解析 `.idx`（与可选 `.syn`）。整个解析过程只保留紧凑数组，
         * 不把源文件整体读入内存，也不为词条创建 String 对象。
         */
        fun load(ifo: Ifo, idx: InputStream, syn: InputStream?): StarDictIndex {
            val declared = ifo.wordCount.toInt()
            val cap = if (declared in 1..MAX_ENTRIES) declared else (1 shl 16)
            // 字符容量预估：平均词长 ≈ idxfilesize/wordcount - 9（8B 偏移+长度、1B NUL），
            // +2 字符余量避免末尾扩容拷贝；上限 2 亿字符（再大的词典必然 OOM，
            // 由 open() 的兜底捕获兜住）。
            val estChars = if (declared in 1..MAX_ENTRIES && ifo.idxFileSize > declared * 9L) {
                ((ifo.idxFileSize / declared - 9 + 2) * declared.toLong())
                    .coerceIn(1024L, 200_000_000L).toInt()
            } else {
                (cap.toLong() * 12).coerceAtMost(200_000_000L).toInt()
            }
            val words = CharBuilder(maxOf(estChars, 1024))
            var starts = IntArray(cap + 1)
            var offsets = LongArray(cap)
            var sizes = IntArray(cap)
            val r = ByteReader(idx)
            val scratch = Scratch()

            var n = 0
            parse@ while (true) {
                val first = r.read()
                if (first <= 0) break@parse // EOF 或空词头：结束（与旧解析器一致）
                // 先读进暂存：只有「词头 + 偏移/长度」都完整才提交进紧凑数组，
                // 截断的残词条不留任何痕迹（否则残留字符会污染上一词条的区域）。
                if (!r.readWord(first, scratch)) break@parse
                var off = 0L
                var sz = 0
                var ok = true
                if (ifo.idxOffsetBits == 64) {
                    var v = 0L
                    var read = 0
                    while (read < 16) {
                        val b = r.read()
                        if (b < 0) {
                            ok = false
                            break
                        }
                        if (read < 8) v = (v shl 8) or b.toLong() else sz = (sz shl 8) or b
                        read++
                    }
                    off = v
                } else {
                    var v = 0L
                    var s = 0
                    var read = 0
                    while (read < 8) {
                        val b = r.read()
                        if (b < 0) {
                            ok = false
                            break
                        }
                        if (read < 4) v = (v shl 8) or b.toLong() else s = (s shl 8) or b
                        read++
                    }
                    off = v and 0xffffffffL
                    sz = s
                }
                if (!ok) break@parse // 偏移/长度不完整：丢弃残词条
                if (n == offsets.size) {
                    val newCap = n + (n ushr 1) + 1 // .ifo 少报词条数时扩容 1.5x
                    starts = starts.copyOf(newCap + 1)
                    offsets = offsets.copyOf(newCap)
                    sizes = sizes.copyOf(newCap)
                }
                starts[n] = words.size
                appendUtf8(scratch.buf, scratch.len, words)
                offsets[n] = off
                sizes[n] = sz
                n++
            }
            starts[n] = words.size
            val wordsData = words.trim()
            val wStarts = if (n + 1 == starts.size) starts else starts.copyOf(n + 1)
            val offs = if (n == offsets.size) offsets else offsets.copyOf(n)
            val szs = if (n == sizes.size) sizes else sizes.copyOf(n)

            val synMap = HashMap<String, Int>()
            parseSyn(synMap, syn, n)

            val order = sortEntries(wordsData, wStarts, n)
            val entryToSearch = IntArray(n)
            for (s in 0 until n) entryToSearch[order[s]] = s
            return StarDictIndex(wordsData, wStarts, offs, szs, order, synMap, entryToSearch)
        }

        /** 解析 `.syn`：别名（UTF-8 NUL 结尾）+ 4 字节词条序号。 */
        private fun parseSyn(target: HashMap<String, Int>, syn: InputStream?, entryCount: Int) {
            if (syn == null) return
            val r = ByteReader(syn)
            val scratch = Scratch()
            while (true) {
                val first = r.read()
                if (first <= 0) break
                if (!r.readWord(first, scratch)) return
                val alias = String(scratch.buf, 0, scratch.len, Charsets.UTF_8)
                var entry = 0
                var read = 0
                while (read < 4) {
                    val b = r.read()
                    if (b < 0) return
                    entry = (entry shl 8) or b
                    read++
                }
                if (entry in 0 until entryCount) {
                    target.putIfAbsent(alias.lowercase(), entry)
                }
            }
        }

        /** 底部向上的稳定归并排序：按 [foldCompare] 对词条下标排序，无装箱。 */
        private fun sortEntries(words: CharArray, starts: IntArray, n: Int): IntArray {
            if (n <= 1) return IntArray(n) { it }
            var src = IntArray(n) { it }
            var dst = IntArray(n)
            var width = 1
            while (width < n) {
                var i = 0
                while (i < n) {
                    val mid = minOf(i + width, n)
                    val end = minOf(i + width * 2, n)
                    var a = i
                    var b = mid
                    var o = i
                    while (a < mid && b < end) {
                        // 稳定：右侧严格更小才先出，相等取左侧（保持文件序）
                        val av = src[a]
                        val bv = src[b]
                        if (foldCompare(
                                words, starts[bv], starts[bv + 1],
                                starts[av], starts[av + 1]
                            ) < 0
                        ) {
                            dst[o++] = bv
                            b++
                        } else {
                            dst[o++] = av
                            a++
                        }
                    }
                    while (a < mid) dst[o++] = src[a++]
                    while (b < end) dst[o++] = src[b++]
                    i = end
                }
                val t = src
                src = dst
                dst = t
                width = width shl 1
            }
            return src
        }

        // ------------------------------------------------------------------
        // .jidx 预建缓存（格式与 v1 完全一致；读写均流式）
        // ------------------------------------------------------------------

        fun writeCache(index: StarDictIndex, file: File, ifo: Ifo) {
            val n = index.size
            val synCount = index.synAliases.size
            var synTotalChars = 0
            for (k in index.synAliases.keys) synTotalChars += k.length

            val tmp = File(file.parentFile, file.name + ".tmp")
            FileOutputStream(tmp).use { fos ->
                val out = BufferedOutputStream(fos, 1 shl 16)
                fun wInt(v: Int) {
                    out.write((v ushr 24) and 0xff)
                    out.write((v ushr 16) and 0xff)
                    out.write((v ushr 8) and 0xff)
                    out.write(v and 0xff)
                }
                fun wLong(v: Long) {
                    for (i in 7 downTo 0) out.write(((v ushr (i * 8)) and 0xff).toInt())
                }
                wInt(CACHE_MAGIC)
                wInt(CACHE_VERSION)
                wInt(ifo.wordCount.toInt())
                wLong(ifo.idxFileSize)
                wInt(ifo.synWordCount.toInt())
                wInt(index.wordsData.size) // totalChars
                writeChars(out, index.wordsData)
                for (i in 0 until n) wInt(index.wordStarts[i])
                for (i in 0 until n) wLong(index.offsets[i])
                for (i in 0 until n) wInt(index.sizes[i])
                for (i in 0 until n) wInt(index.order[i])

                val synCharOffsets = IntArray(synCount)
                val synValues = IntArray(synCount)
                val keyBuf = CharArray(if (synTotalChars > 0) synTotalChars else 1)
                var sp = 0
                var si = 0
                for ((k, v) in index.synAliases) {
                    synCharOffsets[si] = sp
                    synValues[si] = v
                    k.toCharArray(keyBuf, sp)
                    sp += k.length
                    si++
                }
                wInt(synCount)
                wInt(synTotalChars)
                writeChars(out, keyBuf, 0, synTotalChars)
                for (i in 0 until synCount) wInt(synCharOffsets[i])
                for (i in 0 until synCount) wInt(synValues[i])
                out.flush()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }

        /**
         * 从预建缓存加载索引；文件缺失、损坏或校验字段（wordcount / idxfilesize /
         * synwordcount）与当前 `.ifo` 不一致时返回 null，调用方回退到 [load]。
         * 流式读取，不把缓存文件整体读入内存。
         */
        fun loadCache(file: File, ifo: Ifo): StarDictIndex? {
            if (!file.exists()) return null
            return try {
                DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                    val magic = inp.readInt()
                    if (magic != CACHE_MAGIC) return null
                    val version = inp.readInt()
                    if (version != CACHE_VERSION) return null
                    val wordCount = inp.readInt()
                    val idxFileSize = inp.readLong()
                    val synWordCount = inp.readInt()
                    if (wordCount != ifo.wordCount.toInt() ||
                        idxFileSize != ifo.idxFileSize ||
                        synWordCount != ifo.synWordCount.toInt()
                    ) {
                        return null
                    }
                    if (wordCount < 0 || wordCount > MAX_ENTRIES) return null
                    val totalChars = inp.readInt()
                    if (totalChars < 0) return null
                    val wordsData = CharArray(totalChars)
                    readChars(inp, wordsData, totalChars)
                    val wordStarts = IntArray(wordCount + 1)
                    var prevStart = -1
                    for (i in 0 until wordCount) {
                        val s = inp.readInt()
                        if (s < prevStart || s > totalChars) return null // 损坏防御
                        wordStarts[i] = s
                        prevStart = s
                    }
                    wordStarts[wordCount] = totalChars
                    val offsets = LongArray(wordCount)
                    for (i in 0 until wordCount) offsets[i] = inp.readLong()
                    val sizes = IntArray(wordCount)
                    for (i in 0 until wordCount) sizes[i] = inp.readInt()
                    val order = IntArray(wordCount)
                    for (i in 0 until wordCount) {
                        val v = inp.readInt()
                        if (v < 0 || v >= wordCount) return null
                        order[i] = v
                    }
                    val entryToSearch = IntArray(wordCount)
                    for (s in 0 until wordCount) entryToSearch[order[s]] = s

                    val synCount = inp.readInt()
                    if (synCount < 0) return null
                    val synTotalChars = inp.readInt()
                    if (synTotalChars < 0) return null
                    val synCharData = CharArray(synTotalChars)
                    readChars(inp, synCharData, synTotalChars)
                    val synCharOffsets = IntArray(synCount)
                    for (i in 0 until synCount) synCharOffsets[i] = inp.readInt()
                    val syn = HashMap<String, Int>(synCount)
                    for (i in 0 until synCount) {
                        val start = synCharOffsets[i]
                        val end = if (i + 1 < synCount) synCharOffsets[i + 1] else synTotalChars
                        val alias = String(synCharData, start, end - start)
                        val entry = inp.readInt()
                        if (entry in 0 until wordCount) syn[alias] = entry
                    }
                    StarDictIndex(wordsData, wordStarts, offsets, sizes, order, syn, entryToSearch)
                }
            } catch (e: Exception) {
                null
            }
        }

        // ------------------------------------------------------------------
        // 内部工具
        // ------------------------------------------------------------------

        /** UTF-16BE 分块写（对 5000 万字符级的大缓存，逐字符写太慢）。 */
        private fun writeChars(out: BufferedOutputStream, data: CharArray, from: Int = 0, count: Int = data.size) {
            val chunk = 1 shl 15 // 32768 字符 -> 65536 字节
            val bytes = ByteArray(chunk * 2)
            var i = from
            val end = from + count
            while (i < end) {
                val len = minOf(chunk, end - i)
                var o = 0
                for (j in 0 until len) {
                    val c = data[i + j].code
                    bytes[o++] = (c ushr 8).toByte()
                    bytes[o++] = (c and 0xff).toByte()
                }
                out.write(bytes, 0, o)
                i += len
            }
        }

        private fun readChars(inp: DataInputStream, out: CharArray, count: Int) {
            val chunk = 1 shl 15
            val bytes = ByteArray(chunk * 2)
            var done = 0
            while (done < count) {
                val chars = minOf(chunk, count - done)
                inp.readFully(bytes, 0, chars * 2)
                var o = 0
                for (j in 0 until chars) {
                    out[done + j] = (((bytes[o].toInt() and 0xff) shl 8) or
                        (bytes[o + 1].toInt() and 0xff)).toChar()
                    o += 2
                }
                done += chars
            }
        }

        /** 可增长字符缓冲（预分配容量到位时零扩容）。 */
        private class CharBuilder(initialCapacity: Int) {
            var data = CharArray(initialCapacity.coerceAtLeast(16))
                private set
            var size = 0
                private set

            fun ensure(extra: Int) {
                if (size + extra > data.size) {
                    var newCap = data.size * 2
                    while (newCap < size + extra) newCap = newCap * 2
                    data = data.copyOf(newCap)
                }
            }

            fun append(c: Char) {
                ensure(1)
                data[size++] = c
            }

            fun trim(): CharArray = if (size == data.size) data else data.copyOf(size)
        }

        /** 读词用的临时字节缓冲（复用，按需扩容）。 */
        private class Scratch {
            var buf = ByteArray(4096)
            var len = 0
        }

        /** 64KB 缓冲的字节读取器。 */
        private class ByteReader(private val stream: InputStream) {
            private val buf = ByteArray(1 shl 16)
            private var pos = 0
            private var limit = 0

            fun read(): Int {
                if (pos >= limit) {
                    while (true) {
                        val n = stream.read(buf)
                        if (n > 0) {
                            limit = n
                            pos = 0
                            break
                        }
                        if (n < 0) return -1
                        // n == 0：极少见的空读，继续
                    }
                }
                return buf[pos++].toInt() and 0xff
            }
        }

        /** 把一个词读入 [s]（含首字节 [first]），读到 NUL 或 EOF 为止；NUL 结束才算完整。 */
        private fun ByteReader.readWord(first: Int, s: Scratch): Boolean {
            s.len = 0
            var b = first
            while (b > 0) {
                if (s.len == s.buf.size) s.buf = s.buf.copyOf(s.buf.size * 2)
                s.buf[s.len++] = b.toByte()
                b = read()
            }
            return b == 0
        }

        /** UTF-8 → UTF-16 追加解码（不构造 String；非法序列替换为 U+FFFD）。 */
        private fun appendUtf8(bytes: ByteArray, len: Int, out: CharBuilder) {
            var i = 0
            while (i < len) {
                val b = bytes[i].toInt() and 0xff
                when {
                    b < 0x80 -> {
                        out.append(b.toChar())
                        i += 1
                    }
                    b and 0xE0 == 0xC0 && i + 1 < len &&
                        bytes[i + 1].toInt() and 0xC0 == 0x80 -> {
                        val c = ((b and 0x1F) shl 6) or (bytes[i + 1].toInt() and 0x3F)
                        out.append(c.toChar())
                        i += 2
                    }
                    b and 0xF0 == 0xE0 && i + 2 < len &&
                        bytes[i + 1].toInt() and 0xC0 == 0x80 &&
                        bytes[i + 2].toInt() and 0xC0 == 0x80 -> {
                        val c = ((b and 0x0F) shl 12) or
                            ((bytes[i + 1].toInt() and 0x3F) shl 6) or
                            (bytes[i + 2].toInt() and 0x3F)
                        out.append(c.toChar())
                        i += 3
                    }
                    b and 0xF8 == 0xF0 && i + 3 < len &&
                        bytes[i + 1].toInt() and 0xC0 == 0x80 &&
                        bytes[i + 2].toInt() and 0xC0 == 0x80 &&
                        bytes[i + 3].toInt() and 0xC0 == 0x80 -> {
                        val cp = ((b and 0x07) shl 18) or
                            ((bytes[i + 1].toInt() and 0x3F) shl 12) or
                            ((bytes[i + 2].toInt() and 0x3F) shl 6) or
                            (bytes[i + 3].toInt() and 0x3F)
                        out.append(Character.highSurrogate(cp))
                        out.append(Character.lowSurrogate(cp))
                        i += 4
                    }
                    else -> {
                        out.append('�')
                        i += 1
                    }
                }
            }
        }
    }
}
