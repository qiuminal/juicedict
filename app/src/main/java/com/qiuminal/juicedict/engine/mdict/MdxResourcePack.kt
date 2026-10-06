package com.qiuminal.juicedict.engine.mdict

import java.io.File

/**
 * The `.mdd` companion of an MDX dictionary: a second MDict container whose records
 * are binary blobs (images, stylesheets, fonts, audio) addressed by path.
 *
 * MDD differs from MDX in three ways, all of which matter here:
 *
 * - Keys are **UTF-16LE regardless of the header**, so the encoding unit is always 2.
 * - Keys are **paths**, conventionally backslash-prefixed and case-insensitive:
 *   `\XHZD_12.css`. Lookup has to tolerate a missing leading backslash and either
 *   slash direction, because the referring HTML is inconsistent about it.
 * - Bodies are raw bytes with **no trailing NUL**, so the record size is the whole
 *   byte range — MDict stores only offsets, so it comes from the next record.
 *
 * Resources are read on demand and never held wholesale: 《字源 (2012)》 ships a
 * 43 MB pack, and one entry typically needs a stylesheet plus a couple of images.
 */
internal class MdxResourcePack(private val file: File) : AutoCloseable {

    private val reader = MdxFileReader(file)
    private val header: MdxHeader
    private val index: MdxKeyIndex
    private val records: MdxRecordStore
    private val order: Comparator<String>

    /** Number of resources in the pack. */
    val resourceCount: Int get() = index.entryCount.toInt()

    val name: String get() = file.name

    init {
        val head = MdxHeader.parse(reader.read(0L, minOf(reader.length, MAX_HEADER_BYTES).toInt()))
            ?: error("not an MDict resource pack: ${file.name}")
        require(head.kind == MdxKind.RESOURCE) { "${file.name} is not a resource pack" }
        require(!head.isEncrypted) { "encrypted MDict resource packs are not supported" }
        header = head
        index = MdxKeyIndex.parse(
            reader = reader,
            offset = head.keySectionOffset,
            unitSize = 2,
            declaredEntries = 0L,
            version = head.version,
            stripKey = head.stripKey,
        )
        order = MdxKeyOrder.comparator(head.stripKey)
        records = MdxRecordStore(reader, index.recordSectionOffset(head.keySectionOffset))
        index.setRecordsEnd(records.totalSize)
    }

    /**
     * Reads a resource by MDD path.
     *
     * Returns null when the path is absent. Callers must not treat that as an error:
     * the referring HTML routinely names resources a pack does not carry.
     */
    fun read(path: String): ByteArray? {
        val normalized = path.replace('/', '\\')
        for (candidate in candidatesFor(normalized)) {
            val hit = find(candidate) ?: continue
            val (offset, size) = hit
            if (size <= 0) return ByteArray(0)
            return records.read(offset, size)
        }
        return null
    }

    /** The path spellings a pack might use for [path]. */
    private fun candidatesFor(path: String): List<String> = buildList {
        add(path)
        if (!path.startsWith("\\")) add("\\$path")
        if (path.startsWith("\\")) add(path.substring(1))
    }

    /**
     * Locates [key] in the pack.
     *
     * Binary search over key blocks narrows to one block (~655 keys here) and only
     * that block is decompressed and walked, so a lookup costs one block inflate
     * rather than a scan of all 10269 keys.
     */
    private fun find(key: String): Pair<Long, Int>? {
        val block = index.blockFor(key)
        if (block >= index.blockCount) return null
        val offsets = index.recordOffsetsIn(reader, block)
        val bytes = loadKeys(block)

        var p = 0
        var n = 0
        while (n < offsets.size && p + 8 <= bytes.size) {
            p += 8
            val start = p
            while (p + 2 <= bytes.size && !isNul(bytes, p, 2)) p += 2
            val candidate = decodeUtf16Le(bytes, start, p)
            p += 2
            if (order.compare(candidate, key) == 0 || candidate.equals(key, ignoreCase = true)) {
                val offset = offsets[n]
                val size = sizeOf(block, n, offset)
                return offset to size
            }
            n++
        }
        return null
    }

    /** Record size, derived from the following record's offset. */
    private fun sizeOf(block: Int, entry: Int, offset: Long): Int {
        val offsets = index.recordOffsetsIn(reader, block)
        val end = if (entry + 1 < offsets.size) {
            offsets[entry + 1]
        } else {
            val next = block + 1
            if (next < index.blockCount) {
                index.recordOffsetsIn(reader, next)[0]
            } else {
                records.totalSize
            }
        }
        return if (end > offset) (end - offset).toInt() else 0
    }

    private val keyBlockCache = LinkedHashMap<Int, ByteArray>(CACHE_BLOCKS, 0.75f, true)

    private fun loadKeys(block: Int): ByteArray {
        keyBlockCache[block]?.let { return it }
        val raw = reader.read(index.blockOffsetAt(block), index.blockCompSizeAt(block).toInt())
        val bytes = MdxBlockReader.read(raw, index.blockDecompSizeAt(block))
        evictOldest(keyBlockCache, CACHE_BLOCKS)
        keyBlockCache[block] = bytes
        return bytes
    }

    override fun close() {
        keyBlockCache.clear()
        records.clearCache()
        reader.close()
    }

    private companion object {
        const val MAX_HEADER_BYTES = 1L shl 20

        /**
         * Decompressed key blocks held at once. A block is tens of KB, and reference
         * resolution touches a handful of neighbouring directories, so this is small
         * on purpose.
         */
        const val CACHE_BLOCKS = 8

        fun <V> evictOldest(cache: LinkedHashMap<Int, V>, max: Int) {
            while (cache.size >= max) {
                val it = cache.entries.iterator()
                if (!it.hasNext()) return
                it.next()
                it.remove()
            }
        }

        fun isNul(bytes: ByteArray, at: Int, unit: Int): Boolean {
            for (i in 0 until unit) if (bytes[at + i].toInt() != 0) return false
            return true
        }

        fun decodeUtf16Le(bytes: ByteArray, from: Int, to: Int): String {
            if (to <= from) return ""
            val chars = CharArray((to - from) / 2)
            var i = from
            var j = 0
            while (j < chars.size) {
                chars[j] = ((bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8))
                    .toChar()
                i += 2
                j++
            }
            return String(chars)
        }
    }
}
