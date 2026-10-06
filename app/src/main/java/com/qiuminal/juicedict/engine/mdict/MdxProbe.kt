package com.qiuminal.juicedict.engine.mdict

import java.io.File

/**
 * Cheap metadata read for an MDict file that is *not* worth opening yet.
 *
 * [com.qiuminal.juicedict.data.DictionaryRepository.listDictionaries] runs on every
 * lookup (through `listEnabled()`), so it must never build a word index. A full
 * [MdxWordIndex.load] walks and inflates every key block — ~76 of them and a few
 * hundred milliseconds for 《辞海第七版》. This probe instead reads the header and the
 * first 16 bytes of the key section, which is where the entry count lives.
 */
internal object MdxProbe {

    data class Info(
        val title: String,
        val description: String,
        /** Entry count from the key-section preamble; 0 when unreadable. */
        val entryCount: Long,
    )

    private const val MAX_HEADER_BYTES = 1L shl 20

    /** `u64be num_blocks | u64be num_entries` — the second field is all we need. */
    private const val ENTRY_COUNT_OFFSET = 8

    private const val PREAMBLE_PROBE_BYTES = 16

    fun read(file: File): Info? {
        val reader = runCatching { MdxFileReader(file) }.getOrNull() ?: return null
        try {
            val headBytes = reader.read(0L, minOf(reader.length, MAX_HEADER_BYTES).toInt())
            val head = MdxHeader.parse(headBytes) ?: return null
            return Info(
                title = head.title,
                description = head.description,
                entryCount = entryCount(reader, head),
            )
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { reader.close() }
        }
    }

    /**
     * Same as [read], but from a prefix of the file already in memory.
     *
     * The import path needs to vet a document-tree URI before copying gigabytes into
     * the dictionary directory; when the key-section preamble falls outside
     * [headBytes] the header's own (possibly stale) count is used instead.
     */
    fun readFromHead(headBytes: ByteArray): Info? {
        val head = MdxHeader.parse(headBytes) ?: return null
        val preambleEnd = head.keySectionOffset + PREAMBLE_PROBE_BYTES
        val count = if (head.kind == MdxKind.ARTICLE && preambleEnd <= headBytes.size) {
            MdxBytes.u64be(headBytes, head.keySectionOffset.toInt() + ENTRY_COUNT_OFFSET)
                .takeIf { it > 0 } ?: head.entryCount
        } else {
            head.entryCount
        }
        return Info(head.title, head.description, count)
    }

    /**
     * The key section's own `num_entries`, which is authoritative.
     *
     * The header's `num_entries` attribute is a stale hint in several shipped files
     * (辞海第七版 declares none at all while its index holds 137390 entries), so it is
     * only the fallback.
     */
    private fun entryCount(reader: MdxFileReader, head: MdxHeader): Long {
        if (head.kind != MdxKind.ARTICLE) return 0L
        if (head.keySectionOffset + PREAMBLE_PROBE_BYTES > reader.length) return head.entryCount
        return try {
            val preamble = reader.read(head.keySectionOffset, PREAMBLE_PROBE_BYTES)
            MdxBytes.u64be(preamble, ENTRY_COUNT_OFFSET).takeIf { it > 0 } ?: head.entryCount
        } catch (t: Throwable) {
            head.entryCount
        }
    }
}
