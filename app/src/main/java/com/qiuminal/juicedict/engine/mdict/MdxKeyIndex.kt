package com.qiuminal.juicedict.engine.mdict

/**
 * The key index of an MDict file: a list of key blocks, each covering a contiguous
 * range of the sorted key space.
 *
 * Key section layout (verified against real files):
 *
 * ```
 * u64be num_key_blocks
 * u64be num_entries
 * u64be key_index_decompressed_size
 * u64be key_index_compressed_size
 * u64be key_blocks_total_size
 * u32be adler32(first 40 bytes)
 * <key index block>       // one compressed block, see below
 * <key blocks>            // num_key_blocks compressed blocks, back to back
 * ```
 *
 * The decompressed key index is a sequence of records:
 *
 * ```
 * u64be num_entries_in_block
 * u16be first_key_length        // in encoding units, excluding its NUL terminator
 * first_key                     // followed by one NUL unit, NOT counted in the length
 * u16be last_key_length         // in encoding units, excluding its NUL terminator
 * last_key                      // followed by one NUL unit, NOT counted in the length
 * u64be compressed_size
 * u64be decompressed_size
 * ```
 *
 * The published format description says the key fields carry no terminator; real files
 * write one anyway, so parsing must skip it. The length *excludes* it, which is the
 * detail that matters: reading `length` bytes and then the next field lands 1 unit
 * early, and every following `compressed_size` is garbage. The length is measured in
 * encoding units — bytes for UTF-8, 16-bit units for UTF-16 — and parsing still strips
 * a trailing NUL if a writer folded it into the length instead.
 */
internal class MdxKeyIndex(
    /** Declared entry count for each key block. */
    private val blockEntryCounts: LongArray,
    /** Absolute file offset of each key block's compressed bytes. */
    private val blockOffsets: LongArray,
    /** Compressed size of each key block. */
    private val blockCompSizes: LongArray,
    /** Decompressed size of each key block. */
    private val blockDecompSizes: LongArray,
    private val firstKeys: Array<String>,
    private val lastKeys: Array<String>,
    /** Encoding unit width in bytes: 1 for UTF-8/GBK, 2 for UTF-16. */
    val unitSize: Int,
    /** Ordering the writer used to sort keys; see [MdxKeyOrder]. */
    private val order: Comparator<String>,
    /** Compressed size of the key index block, needed to locate the record section. */
    val indexCompSize: Long,
) {
    /** Number of key blocks. */
    val blockCount: Int get() = blockOffsets.size

    /** Sum of per-block entry counts (may differ slightly from the header's count). */
    val entryCount: Long get() = blockEntryCounts.sum()

    fun entryCountAt(block: Int): Long = blockEntryCounts[block]

    fun firstKeyAt(block: Int): String = firstKeys[block]

    fun lastKeyAt(block: Int): String = lastKeys[block]

    fun blockOffsetAt(block: Int): Long = blockOffsets[block]

    fun blockCompSizeAt(block: Int): Long = blockCompSizes[block]

    fun blockDecompSizeAt(block: Int): Long = blockDecompSizes[block]

    /**
     * Resolves a search index to its record address `(offset, size)`.
     *
     * The record offset lives inside the key block alongside the key text, so this
     * decompresses (and caches) the owning block and walks to the entry. Block 0 of
     * 《辞海第七版》 holds 2140 keys over ~32 KB, so the walk is a short linear scan
     * over an array already in memory, not a per-entry cost paid at load time.
     *
     * The size is derived from the *next* entry's offset, since MDict stores only
     * offsets. The last entry of a block therefore needs the first record offset of
     * the following block; when that is unavailable the block's decompressed size is
     * used as an upper bound and the record reader clamps it.
     */
    fun recordAt(reader: MdxFileReader, searchIndex: Int): Pair<Long, Int> {
        val block = blockOfIndex(searchIndex)
        val inBlock = searchIndex - entriesBefore(block)
        val offsets = recordOffsets(reader, block)
        require(inBlock in offsets.indices) {
            "MDict entry $searchIndex out of range for block $block (${offsets.size} entries)"
        }
        val offset = offsets[inBlock]
        val end = when {
            inBlock + 1 < offsets.size -> offsets[inBlock + 1]
            block + 1 < blockCount -> firstRecordOffset(reader, block + 1)
            else -> recordsEnd
        }
        val size = if (end > offset) (end - offset).toInt() else 0
        return offset to size
    }

    /** Total decompressed size of the whole record space, from the record preamble. */
    private var recordsEnd: Long = 0

    internal fun setRecordsEnd(value: Long) {
        recordsEnd = value
    }

    private fun entriesBefore(block: Int): Int {
        var n = 0
        for (i in 0 until block) n += blockEntryCounts[i].toInt()
        return n
    }

    /** Index of the key block containing [searchIndex]. */
    private fun blockOfIndex(searchIndex: Int): Int {
        var n = 0
        for (b in 0 until blockCount) {
            n += blockEntryCounts[b].toInt()
            if (searchIndex < n) return b
        }
        return blockCount - 1
    }

    private val recordOffsetCache = LinkedHashMap<Int, LongArray>(4, 0.75f, true)

    /** Public form of the per-block record offsets, for the resource pack. */
    internal fun recordOffsetsIn(reader: MdxFileReader, block: Int): LongArray =
        recordOffsets(reader, block)

    /**
     * Byte offset of the record section: the 44-byte key preamble (40 fields plus
     * Adler-32), the compressed key index, and every compressed key block.
     */
    internal fun recordSectionOffset(keySectionOffset: Long): Long {
        var total = 44L + indexCompSize
        for (b in 0 until blockCount) total += blockCompSizes[b]
        return keySectionOffset + total
    }

    /**
     * The record offset of every entry in [block], in key order.
     *
     * Extracted once per block and kept small: 《辞海第七版》's largest block has
     * 2140 entries, so this is tens of KB, and only the blocks a user actually
     * browses ever get built.
     */
    private fun recordOffsets(reader: MdxFileReader, block: Int): LongArray {
        recordOffsetCache[block]?.let { return it }
        val raw = reader.read(blockOffsets[block], blockCompSizes[block].toInt())
        val bytes = MdxBlockReader.read(raw, blockDecompSizes[block])
        val out = LongArray(blockEntryCounts[block].toInt())
        var p = 0
        var n = 0
        while (n < out.size && p + 8 <= bytes.size) {
            out[n] = MdxBytes.u64be(bytes, p)
            p += 8
            while (p + unitSize <= bytes.size && !isNul(bytes, p, unitSize)) p += unitSize
            p += unitSize
            n++
        }
        require(n == out.size) { "MDict key block $block held $n entries, expected ${out.size}" }
        if (recordOffsetCache.size >= 4) {
            val it = recordOffsetCache.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        recordOffsetCache[block] = out
        return out
    }

    /** Record offset of the first entry in [block], used to size the previous block's tail. */
    private fun firstRecordOffset(reader: MdxFileReader, block: Int): Long =
        recordOffsets(reader, block)[0]

    /**
     * Finds the index of the key block that would contain [key], using the per-block
     * first/last key ranges.
     *
     * Returns `size` when the key sorts after every block.
     */
    fun blockFor(key: String): Int {
        var lo = 0
        var hi = blockCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (order.compare(lastKeys[mid], key) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        /** Five `u64be` fields: block count, entry count and three sizes. */
        private const val PREAMBLE = 40

        /** The preamble plus the `u32be` Adler-32 that follows it. */
        private const val PREAMBLE_WITH_CHECKSUM = PREAMBLE + 4

        /**
         * Parses the key section starting at [offset].
         *
         * @param reader the dictionary file
         * @param offset byte offset of the key section
         * @param unitSize encoding unit width (1 for UTF-8/GBK, 2 for UTF-16)
         * @param declaredEntries the entry count from the header, used for sanity checks
         */
        fun parse(
            reader: MdxFileReader,
            offset: Long,
            unitSize: Int,
            declaredEntries: Long,
            version: Float,
            stripKey: Boolean,
        ): MdxKeyIndex {
            val head = reader.read(offset, PREAMBLE_WITH_CHECKSUM)
            val numBlocks = MdxBytes.u64be(head, 0)
            val numEntries = MdxBytes.u64be(head, 8)
            val indexDecompSize = MdxBytes.u64be(head, 16)
            val indexCompSize = MdxBytes.u64be(head, 24)
            val blocksTotalSize = MdxBytes.u64be(head, 32)

            val declaredAdler = MdxBytes.u32be(head, 40)
            val actualAdler = MdxBlockReader.adler32(head, 0, PREAMBLE)
            require(declaredAdler == actualAdler) { "MDict key section preamble checksum mismatch" }
            require(numBlocks in 1..MAX_BLOCKS) { "implausible MDict key block count $numBlocks" }
            require(indexCompSize in 1..MAX_INDEX_SIZE) {
                "implausible MDict key index size $indexCompSize"
            }

            val indexBlock = reader.read(offset + PREAMBLE_WITH_CHECKSUM, indexCompSize.toInt())
            val indexBytes = MdxBlockReader.read(indexBlock, indexDecompSize)
            require(indexBytes.size.toLong() == indexDecompSize) {
                "MDict key index decompressed to ${indexBytes.size}, expected $indexDecompSize"
            }

            val counts = LongArray(numBlocks.toInt())
            val offsets = LongArray(numBlocks.toInt())
            val compSizes = LongArray(numBlocks.toInt())
            val decompSizes = LongArray(numBlocks.toInt())
            val firsts = arrayOfNulls<String>(numBlocks.toInt())
            val lasts = arrayOfNulls<String>(numBlocks.toInt())

            var p = 0
            var blockDataOffset = offset + PREAMBLE_WITH_CHECKSUM + indexCompSize
            for (b in 0 until numBlocks.toInt()) {
                require(p + 8 <= indexBytes.size) { "truncated MDict key index" }
                counts[b] = MdxBytes.u64be(indexBytes, p)
                p += 8

                // Each key field is `u16be length | length * unit bytes | NUL unit`.
                // The length excludes the terminator, but the terminator is physically
                // present and must be skipped. Some writers instead fold the NUL into
                // the declared length, so the terminator is tolerated but not required.
                val firstLen = MdxBytes.u16be(indexBytes, p)
                p += 2
                val firstLenBytes = firstLen * unitSize
                require(p + firstLenBytes <= indexBytes.size) { "truncated MDict first key" }
                firsts[b] = decodeKey(indexBytes, p, firstLenBytes, unitSize)
                p += firstLenBytes + unitSize

                val lastLen = MdxBytes.u16be(indexBytes, p)
                p += 2
                val lastLenBytes = lastLen * unitSize
                require(p + lastLenBytes <= indexBytes.size) { "truncated MDict last key" }
                lasts[b] = decodeKey(indexBytes, p, lastLenBytes, unitSize)
                p += lastLenBytes + unitSize

                require(p + 16 <= indexBytes.size) { "truncated MDict key block descriptor" }
                compSizes[b] = MdxBytes.u64be(indexBytes, p)
                p += 8
                decompSizes[b] = MdxBytes.u64be(indexBytes, p)
                p += 8

                offsets[b] = blockDataOffset
                blockDataOffset += compSizes[b]
            }

            val totalEntries = counts.sum()
            if (declaredEntries > 0 && totalEntries != declaredEntries) {
                // Not fatal: the header count is occasionally stale, and the index is
                // authoritative because it is what the reader walks.
                MdxLog.warn(
                    "MdxKeyIndex",
                    "entry count mismatch: header=$declaredEntries index=$totalEntries",
                )
            }
            if (blocksTotalSize > 0 && blockDataOffset > reader.length) {
                MdxLog.warn(
                    "MdxKeyIndex",
                    "key blocks extend past end of file " +
                        "(need $blockDataOffset, file ${reader.length})",
                )
            }

            return MdxKeyIndex(
                blockEntryCounts = counts,
                blockOffsets = offsets,
                blockCompSizes = compSizes,
                blockDecompSizes = decompSizes,
                firstKeys = Array(firsts.size) { firsts[it] ?: "" },
                lastKeys = Array(lasts.size) { lasts[it] ?: "" },
                unitSize = unitSize,
                order = MdxKeyOrder.comparator(stripKey),
                indexCompSize = indexCompSize,
            )
        }

        /** Decodes a length-delimited key, dropping its NUL terminator(s). */
        private fun decodeKey(bytes: ByteArray, at: Int, lenBytes: Int, unitSize: Int): String {
            var n = lenBytes
            while (n >= unitSize && isNul(bytes, at + n - unitSize, unitSize)) n -= unitSize
            if (n <= 0) return ""
            return if (unitSize == 2) decodeUtf16Le(bytes, at, n) else decodeUtf8(bytes, at, n)
        }

        private fun isNul(bytes: ByteArray, at: Int, unitSize: Int): Boolean {
            for (i in 0 until unitSize) if (bytes[at + i].toInt() != 0) return false
            return true
        }

        private fun decodeUtf16Le(bytes: ByteArray, at: Int, len: Int): String {
            val chars = CharArray(len / 2)
            var i = at
            var j = 0
            while (j < chars.size) {
                chars[j] = ((bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8))
                    .toChar()
                i += 2
                j++
            }
            return String(chars)
        }

        private fun decodeUtf8(bytes: ByteArray, at: Int, len: Int): String =
            String(bytes, at, len, Charsets.UTF_8)

        private const val MAX_BLOCKS = 1 shl 24
        private const val MAX_INDEX_SIZE = 1L shl 28
    }
}
