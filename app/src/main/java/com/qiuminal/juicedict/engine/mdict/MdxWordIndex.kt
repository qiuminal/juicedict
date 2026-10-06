package com.qiuminal.juicedict.engine.mdict

import com.qiuminal.juicedict.engine.DictDataReader

/**
 * The key half of an MDict dictionary, presented with the same surface as
 * `StarDictIndex` so the existing search chain can drive either one.
 *
 * MDict stores its keys across compressed blocks, which is the opposite of
 * StarDict's plain `.idx` array. Rather than materialize every word, this walks the
 * key blocks once at load and copies out just the two arrays lookups actually need:
 *
 * - [words] — every key, concatenated, UTF-8 encoded (the same compact layout
 *   `StarDictIndex` uses, chosen for the same reason: 137390 keys as `String[]`
 *   costs several times more than one flat `ByteArray`).
 * - [starts] — end offset of each key within [words].
 *
 * Offsets and sizes are not stored per entry: in MDict the record offset lives in
 * the key block, so it is read on demand from the block already cached for the
 * surrounding range. That keeps the resident index to roughly the size of the key
 * text itself.
 *
 * Ordering follows [MdxKeyOrder], and binary search *must* use it: several shipped
 * dictionaries contain keys outside the BMP, where Kotlin's `String` order and
 * MDict's byte order disagree.
 */
internal class MdxWordIndex private constructor(
    private val words: ByteArray,
    private val starts: IntArray,
    private val entryBlock: IntArray,
    private val order: Comparator<String>,
    private val stripKey: Boolean,
) {
    /** Number of keys. */
    val size: Int get() = starts.size

    fun wordAt(index: Int): String = decode(words, startOf(index), starts[index])

    /** Block that holds this entry's record offset; used by the record reader. */
    fun blockOf(index: Int): Int = entryBlock[index]

    private fun startOf(index: Int): Int = if (index == 0) 0 else starts[index - 1]

    /**
     * First index whose key is >= [key]. Returns [size] when every key is smaller.
     */
    fun lowerBound(key: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (order.compare(compactKey(mid), key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Indices of entries exactly equal to [key].
     *
     * `compactKey` is compared rather than the decoded `String` so that lookup never
     * allocates for the common case, and so that the comparison uses exactly the same
     * folding the sort used.
     */
    fun exactMatches(key: String, limit: Int = 30): List<Int> {
        val out = ArrayList<Int>(4)
        var i = lowerBound(key)
        while (i < size && out.size < limit) {
            if (order.compare(compactKey(i), key) != 0) break
            out.add(i)
            i++
        }
        return out
    }

    /** Indices whose key starts with [prefix], capped at [limit]. */
    fun prefixMatches(prefix: String, limit: Int = 100): List<Int> {
        val out = ArrayList<Int>(minOf(limit, 32))
        if (prefix.isEmpty()) return out
        val folded = MdxKeyOrder.fold(prefix, stripKey)
        var i = lowerBound(prefix)
        while (i < size && out.size < limit) {
            val word = wordAt(i)
            if (!MdxKeyOrder.fold(word, stripKey).startsWith(folded)) break
            out.add(i)
            i++
        }
        return out
    }

    /** Visits every key in index order. */
    fun forEachWord(action: (searchIndex: Int, word: String) -> Unit) {
        for (i in 0 until size) action(i, wordAt(i))
    }

    /**
     * Indices whose key equals [spaceFree] once spaces are removed from both sides.
     *
     * Binary search is useless here — the space-free form is not the sort key — so
     * candidates come from the prefix range instead: any key folding to exactly
     * [spaceFree] must *start* with it once spaces are dropped, which puts it at or
     * after `lowerBound(spaceFree)`. Walking only that range keeps a miss cheap on a
     * 137390-key index, where a full scan would dominate the query.
     */
    fun spaceFreeMatches(spaceFree: String, limit: Int = 60): List<Int> {
        val out = ArrayList<Int>(4)
        if (spaceFree.isEmpty()) return out
        val target = spaceFree.lowercase()
        val start = lowerBound(spaceFree)
        val scanCap = start + SPACE_FREE_SCAN
        var i = start
        while (i < size && i < scanCap && out.size < limit) {
            val w = wordAt(i)
            if (w.indexOf(' ') >= 0 && w.filterNot { it == ' ' }.lowercase() == target) out.add(i)
            i++
        }
        return out
    }

    /**
     * The folded form of entry [index], built from the packed bytes without going
     * through a `String` first.
     *
     * Folding allocates either way; the point is that it happens once per comparison
     * rather than once per probe *plus* one `String` decode per probe.
     */
    private fun compactKey(index: Int): String =
        MdxKeyOrder.fold(decode(words, startOf(index), starts[index]), stripKey)

    companion object {
        /**
         * Loads every key from [keyIndex]'s blocks.
         *
         * @param reader the dictionary file
         * @param keyIndex parsed key section
         * @param stripKey whether the writer stripped punctuation before sorting
         */
        fun load(
            reader: MdxFileReader,
            keyIndex: MdxKeyIndex,
            stripKey: Boolean,
        ): MdxWordIndex {
            val unit = keyIndex.unitSize
            val total = keyIndex.entryCount
            require(total in 1..MAX_ENTRIES.toLong()) { "implausible MDict entry count $total" }

            val words = java.io.ByteArrayOutputStream(minOf(total * 8, 1 shl 24).toInt())
            val starts = IntArray(total.toInt())
            val blocks = IntArray(total.toInt())

            var n = 0
            for (b in 0 until keyIndex.blockCount) {
                val raw = reader.read(keyIndex.blockOffsetAt(b), keyIndex.blockCompSizeAt(b).toInt())
                val bytes = MdxBlockReader.read(raw, keyIndex.blockDecompSizeAt(b))
                var p = 0
                val want = keyIndex.entryCountAt(b)
                var seen = 0L
                while (seen < want && p + 8 <= bytes.size) {
                    // The record offset is stored with the key but is not needed to
                    // build the word list; it is re-read from the cached block when a
                    // hit is turned into an article.
                    p += 8
                    val start = p
                    while (p + unit <= bytes.size && !isTerminator(bytes, p, unit)) p += unit
                    writeUtf8(words, bytes, start, p - start, unit)
                    n++
                    starts[n - 1] = words.size()
                    blocks[n - 1] = b
                    p += unit
                    seen++
                }
                require(seen == want) { "MDict key block $b held $seen keys, expected $want" }
            }
            require(n == total.toInt()) { "MDict key index claimed $total keys, read $n" }

            return MdxWordIndex(
                words = words.toByteArray(),
                starts = starts,
                entryBlock = blocks,
                order = MdxKeyOrder.comparator(stripKey),
                stripKey = stripKey,
            )
        }

        /**
         * Appends one key to [out] as UTF-8.
         *
         * Keys are already in the file's encoding (UTF-8 for every MDX here, UTF-16LE
         * for MDD); UTF-16 is transcoded so the packed array is uniformly UTF-8 and
         * byte-order comparison over it is meaningful.
         */
        private fun writeUtf8(
            out: java.io.ByteArrayOutputStream,
            bytes: ByteArray,
            at: Int,
            len: Int,
            unit: Int,
        ) {
            if (len <= 0) return
            if (unit == 2) {
                var i = at
                val end = at + len
                val sb = StringBuilder(len / 2)
                while (i + 1 < end) {
                    sb.append(
                        ((bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8))
                            .toChar(),
                    )
                    i += 2
                }
                out.write(sb.toString().toByteArray(Charsets.UTF_8))
            } else {
                out.write(bytes, at, len)
            }
        }

        private fun isTerminator(bytes: ByteArray, at: Int, unit: Int): Boolean {
            for (i in 0 until unit) if (bytes[at + i].toInt() != 0) return false
            return true
        }

        private fun decode(bytes: ByteArray, from: Int, to: Int): String =
            if (to <= from) "" else String(bytes, from, to - from, Charsets.UTF_8)

        private const val MAX_ENTRIES = 50_000_000

        /**
         * How far past `lowerBound(spaceFree)` the space-folded search looks. Keys
         * sharing a space-free prefix sit within a few dozen positions of it, so this
         * is generous while keeping the worst case (a total miss) bounded.
         */
        private const val SPACE_FREE_SCAN = 4096
    }
}
