package com.qiuminal.juicedict.engine

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * In-memory StarDict `.idx` index.
 *
 * The `.idx` file is a sequence of entries:
 *   word  (UTF-8, NUL terminated), offset (u32/u64 BE), size (u32/u64 BE)
 * sorted by `stardict_strcmp`. We keep the words and offsets in file order and
 * additionally keep a stable order sorted by [foldCompare] so that both exact
 * and prefix lookup are case-insensitive and allocation-free.
 */
class StarDictIndex private constructor(
    val words: Array<String>,
    val offsets: LongArray,
    val sizes: IntArray,
    /** Indices into the arrays above, sorted by [foldCompare] (stable). */
    private val order: IntArray,
    /** 折叠后的同义词别名（StarDict `.syn`）-> .idx 词条序号（文件顺序）。 */
    private val synAliases: HashMap<String, Int>,
    /** .idx 词条序号 -> 搜索序号（order 中的位置）。 */
    private val entryToSearch: IntArray,
) {
    val size: Int get() = words.size

    fun wordAt(searchIndex: Int): String = words[order[searchIndex]]
    fun offsetAt(searchIndex: Int): Long = offsets[order[searchIndex]]
    fun sizeAt(searchIndex: Int): Int = sizes[order[searchIndex]]

    /** First search index where [foldCompare](word, key) >= 0. */
    fun lowerBound(key: String): Int {
        var lo = 0
        var hi = order.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (foldCompare(words[order[mid]], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** All entries whose folded form equals the folded query (case variants included). */
    fun exactMatches(key: String, limit: Int = 30): List<Int> {
        val out = ArrayList<Int>(minOf(limit, 16))
        var i = lowerBound(key)
        while (i < order.size && out.size < limit && foldCompare(words[order[i]], key) == 0) {
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
        while (i < order.size && out.size < limit && words[order[i]].startsWith(prefix, ignoreCase = true)) {
            out.add(i)
            i++
        }
        return out
    }

    fun forEachWord(action: (searchIndex: Int, word: String) -> Unit) {
        for (i in 0 until order.size) action(i, words[order[i]])
    }

    companion object {
        private const val CACHE_MAGIC = 0x4A444958 // "JDIX"
        private const val CACHE_VERSION = 1

        fun load(ifo: Ifo, idxBytes: ByteArray, synBytes: ByteArray? = null): StarDictIndex {
            val declared = ifo.wordCount.toInt()
            val words = ArrayList<String>(declared)
            val offsets = ArrayList<Long>(declared)
            val sizes = ArrayList<Int>(declared)
            var p = 0
            while (p < idxBytes.size) {
                var end = p
                while (end < idxBytes.size && idxBytes[end] != 0.toByte()) end++
                if (end == p) break
                words.add(String(idxBytes, p, end - p, Charsets.UTF_8))
                p = end + 1
                if (ifo.idxOffsetBits == 64) {
                    if (p + 16 > idxBytes.size) break
                    offsets.add(readLongBE(idxBytes, p)); p += 8
                    sizes.add(readLongBE(idxBytes, p).toInt()); p += 8
                } else {
                    if (p + 8 > idxBytes.size) break
                    offsets.add(readIntBE(idxBytes, p).toLong() and 0xffffffffL); p += 4
                    sizes.add(readIntBE(idxBytes, p)); p += 4
                }
            }
            val n = words.size
            val order = (0 until n).toList()
                .sortedWith(Comparator { a, b -> foldCompare(words[a], words[b]) })
                .toIntArray()
            val entryToSearch = IntArray(n)
            for (s in 0 until n) entryToSearch[order[s]] = s
            val syn = HashMap<String, Int>()
            if (synBytes != null) {
                var p = 0
                while (p < synBytes.size) {
                    var end = p
                    while (end < synBytes.size && synBytes[end] != 0.toByte()) end++
                    if (end == p) break
                    val alias = String(synBytes, p, end - p, Charsets.UTF_8)
                    p = end + 1
                    if (p + 4 > synBytes.size) break
                    val entry = readIntBE(synBytes, p)
                    p += 4
                    if (entry in 0 until n) {
                        syn.putIfAbsent(alias.lowercase(), entry)
                    }
                }
            }
            return StarDictIndex(
                words.toTypedArray(),
                offsets.toLongArray(),
                sizes.toIntArray(),
                order,
                syn,
                entryToSearch,
            )
        }

        /**
         * 把解析好的索引写成预建缓存（类似 ColorDict 的 `.cdi`）。
         *
         * 词条以 UTF-16 连续存放（加载时用 `String(char[], off, len)` 重建，比 UTF-8
         * 逐词解码快数倍），`order` 等已排序数组原样落盘，`.syn` 别名已预折叠
         * （无需在加载时再 `lowercase()`）。文件头带 wordcount / idxfilesize /
         * synwordcount 校验，词典文件变化后自动失效。
         */
        fun writeCache(index: StarDictIndex, file: File, ifo: Ifo) {
            val n = index.size
            var totalChars = 0
            for (w in index.words) totalChars += w.length
            val charData = CharArray(totalChars)
            val charOffsets = IntArray(n)
            var cp = 0
            for (i in 0 until n) {
                charOffsets[i] = cp
                val w = index.words[i]
                w.toCharArray(charData, cp)
                cp += w.length
            }

            val synCount = index.synAliases.size
            var synTotalChars = 0
            for (k in index.synAliases.keys) synTotalChars += k.length
            val synCharData = CharArray(synTotalChars)
            val synCharOffsets = IntArray(synCount)
            val synValues = IntArray(synCount)
            var sp = 0
            var si = 0
            for ((k, v) in index.synAliases) {
                synCharOffsets[si] = sp
                synValues[si] = v
                k.toCharArray(synCharData, sp)
                sp += k.length
                si++
            }

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
                fun wChars(data: CharArray) {
                    for (c in data) {
                        out.write((c.code ushr 8) and 0xff)
                        out.write(c.code and 0xff)
                    }
                }
                wInt(CACHE_MAGIC)
                wInt(CACHE_VERSION)
                wInt(ifo.wordCount.toInt())
                wLong(ifo.idxFileSize)
                wInt(ifo.synWordCount.toInt())
                wInt(totalChars)
                wChars(charData)
                for (i in 0 until n) wInt(charOffsets[i])
                for (i in 0 until n) wLong(index.offsets[i])
                for (i in 0 until n) wInt(index.sizes[i])
                for (i in 0 until n) wInt(index.order[i])
                wInt(synCount)
                wInt(synTotalChars)
                wChars(synCharData)
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
         */
        fun loadCache(file: File, ifo: Ifo): StarDictIndex? {
            return try {
                val bytes = file.readBytes()
                var p = 0
                val magic = readIntBE(bytes, p); p += 4
                if (magic != CACHE_MAGIC) return null
                val version = readIntBE(bytes, p); p += 4
                if (version != CACHE_VERSION) return null
                val wordCount = readIntBE(bytes, p); p += 4
                val idxFileSize = readLongBE(bytes, p); p += 8
                val synWordCount = readIntBE(bytes, p); p += 4
                if (wordCount != ifo.wordCount.toInt() ||
                    idxFileSize != ifo.idxFileSize ||
                    synWordCount != ifo.synWordCount.toInt()
                ) {
                    return null
                }
                val totalChars = readIntBE(bytes, p); p += 4
                val charData = decodeChars(bytes, p, totalChars); p += totalChars * 2
                val charOffsets = IntArray(wordCount)
                for (i in 0 until wordCount) {
                    charOffsets[i] = readIntBE(bytes, p); p += 4
                }
                val words = Array(wordCount) { i ->
                    val start = charOffsets[i]
                    val end = if (i + 1 < wordCount) charOffsets[i + 1] else totalChars
                    String(charData, start, end - start)
                }
                val offsets = LongArray(wordCount)
                for (i in 0 until wordCount) {
                    offsets[i] = readLongBE(bytes, p); p += 8
                }
                val sizes = IntArray(wordCount)
                for (i in 0 until wordCount) {
                    sizes[i] = readIntBE(bytes, p); p += 4
                }
                val order = IntArray(wordCount)
                for (i in 0 until wordCount) {
                    order[i] = readIntBE(bytes, p); p += 4
                }
                val entryToSearch = IntArray(wordCount)
                for (s in 0 until wordCount) entryToSearch[order[s]] = s

                val synCount = readIntBE(bytes, p); p += 4
                val synTotalChars = readIntBE(bytes, p); p += 4
                val synCharData = decodeChars(bytes, p, synTotalChars); p += synTotalChars * 2
                val synCharOffsets = IntArray(synCount)
                for (i in 0 until synCount) {
                    synCharOffsets[i] = readIntBE(bytes, p); p += 4
                }
                val syn = HashMap<String, Int>(synCount)
                for (i in 0 until synCount) {
                    val start = synCharOffsets[i]
                    val end = if (i + 1 < synCount) synCharOffsets[i + 1] else synTotalChars
                    val alias = String(synCharData, start, end - start)
                    val entry = readIntBE(bytes, p); p += 4
                    syn[alias] = entry
                }
                StarDictIndex(words, offsets, sizes, order, syn, entryToSearch)
            } catch (e: Exception) {
                null
            }
        }

        private fun decodeChars(bytes: ByteArray, from: Int, count: Int): CharArray {
            val out = CharArray(count)
            var bi = from
            for (i in 0 until count) {
                out[i] = (((bytes[bi].toInt() and 0xff) shl 8) or (bytes[bi + 1].toInt() and 0xff)).toChar()
                bi += 2
            }
            return out
        }
    }
}
