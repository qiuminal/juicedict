package com.qiuminal.juicedict.engine.mdict

/**
 * The record section of an MDict file, presented as a **virtual flat byte array**.
 *
 * This is the single design decision that lets MDX reuse the whole existing
 * StarDict-shaped article pipeline. MDict stores entry bodies as independently
 * compressed blocks, and each key's `record_offset` is an offset into the
 * *concatenation of all decompressed blocks* — not into the file. So this class
 * maintains a decompressed-block cache with LRU eviction and translates
 * `(offset, size)` in that virtual space into one or more block reads.
 *
 * Layout of the section:
 *
 * ```
 * u64be num_record_blocks
 * u64be num_entries
 * u64be index_size            // == 16 * num_record_blocks
 * u64be blocks_total_size
 * <index>                     // num_record_blocks * (u64be comp_size, u64be decomp_size)
 * <record blocks>             // back to back, same block framing as everything else
 * ```
 *
 * Unlike the key section there is **no Adler-32** over this preamble.
 *
 * Entries are NUL-terminated text (MDX) or raw binary (MDD/`.mdd`), and an article's
 * `size` includes the trailing NUL when the writer stores it.
 */
internal class MdxRecordStore(private val reader: MdxFileReader, private val offset: Long) {

    private val blockOffsets: LongArray
    private val blockCompSizes: LongArray
    private val blockDecompSizes: LongArray

    /** Cumulative decompressed size before each block, for offset translation. */
    private val blockStarts: LongArray

    /** Total decompressed size of the whole record section. */
    val totalSize: Long

    /** Number of records declared by the section preamble. */
    val entryCount: Long

    private val cache = LinkedHashMap<Int, ByteArray>(CACHE_BLOCKS, 0.75f, true)

    init {
        val head = reader.read(offset, 32)
        val numBlocks = MdxBytes.u64be(head, 0)
        entryCount = MdxBytes.u64be(head, 8)
        val indexSize = MdxBytes.u64be(head, 16)
        require(numBlocks in 1..MAX_BLOCKS) { "implausible MDict record block count $numBlocks" }
        require(indexSize == numBlocks * 16L) {
            "MDict record index size $indexSize does not match $numBlocks blocks"
        }

        val count = numBlocks.toInt()
        blockCompSizes = LongArray(count)
        blockDecompSizes = LongArray(count)
        blockOffsets = LongArray(count)

        val indexBytes = reader.read(offset + 32, indexSize.toInt())
        var total = 0L
        var dataOffset = offset + 32 + indexSize
        for (i in 0 until count) {
            blockCompSizes[i] = MdxBytes.u64be(indexBytes, i * 16)
            blockDecompSizes[i] = MdxBytes.u64be(indexBytes, i * 16 + 8)
            blockOffsets[i] = dataOffset
            dataOffset += blockCompSizes[i]
            total += blockDecompSizes[i]
        }
        totalSize = total
        blockStarts = LongArray(count)
        var acc = 0L
        for (i in 0 until count) {
            blockStarts[i] = acc
            acc += blockDecompSizes[i]
        }
    }

    /**
     * Reads [size] bytes at virtual [at], which may span block boundaries.
     *
     * Returns a shorter array only when [at] is at or past the end of the section.
     */
    fun read(at: Long, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        if (at >= totalSize) return ByteArray(0)
        val end = minOf(at + size, totalSize)
        val out = ByteArray((end - at).toInt())
        var written = 0
        var pos = at
        while (pos < end) {
            val b = blockForOffset(pos)
            if (b < 0) break
            val inBlock = (pos - blockStarts[b]).toInt()
            val block = loadBlock(b)
            val n = minOf(block.size - inBlock, (end - pos).toInt())
            if (n <= 0) break
            System.arraycopy(block, inBlock, out, written, n)
            written += n
            pos += n
        }
        return if (written == out.size) out else out.copyOf(written)
    }

    /** Binary search over [blockStarts] for the block containing [virtualOffset]. */
    private fun blockForOffset(virtualOffset: Long): Int {
        var lo = 0
        var hi = blockStarts.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (blockStarts[mid] <= virtualOffset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private fun loadBlock(index: Int): ByteArray {
        cache[index]?.let { return it }
        val compSize = blockCompSizes[index]
        require(compSize in 1..MAX_BLOCK_SIZE) { "implausible MDict record block size $compSize" }
        val raw = reader.read(blockOffsets[index], compSize.toInt())
        val block = MdxBlockReader.read(raw, blockDecompSizes[index])
        require(block.size.toLong() == blockDecompSizes[index]) {
            "MDict record block $index decompressed to ${block.size}, " +
                "expected ${blockDecompSizes[index]}"
        }
        if (cache.size >= CACHE_BLOCKS) {
            val it = cache.entries.iterator()
            if (it.hasNext()) {
                it.next()
                it.remove()
            }
        }
        cache[index] = block
        return block
    }

    /** Drops all decompressed blocks; used when the dictionary is released. */
    fun clearCache() = cache.clear()

    private companion object {
        /**
         * Decompressed record blocks are kept in memory. A block is a few tens of KB,
         * so this caps the cache at a few MB while making sequential reads (the common
         * case when rendering an article) hit repeatedly.
         */
        const val CACHE_BLOCKS = 24
        const val MAX_BLOCKS = 1 shl 24
        const val MAX_BLOCK_SIZE = 1L shl 28
    }
}
