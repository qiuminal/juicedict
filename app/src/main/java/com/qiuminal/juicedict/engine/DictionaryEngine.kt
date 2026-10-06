package com.qiuminal.juicedict.engine

/**
 * A resolved search hit: the headword plus the address of its body.
 *
 * The address is `(offset, size)` into the dictionary's own data space — the one thing
 * every format here agrees on. StarDict addresses its `.dict`/`.dict.dz` verbatim;
 * MDict addresses the flat byte space formed by concatenating its decompressed record
 * blocks. Holding that shape is exactly what lets `Article`, the UI, the history
 * entries and the clipboard flow stay format-agnostic.
 */
data class DictHit(val word: String, val offset: Long, val size: Int)

/**
 * What the app requires of a dictionary, whatever its on-disk format.
 *
 * Deliberately tiny: these are the only calls
 * [com.qiuminal.juicedict.data.LookupEngine] and
 * [com.qiuminal.juicedict.data.DictionaryRepository] actually make. Both engines offer
 * far more — prefix, exact, fuzzy, resource reads — but narrowing the shared surface
 * here is what stops the app from growing a format branch at every call site.
 *
 * Implementations are read-only, safe to use from several threads at once, and must
 * return empty/null rather than throw when fed malformed data or a missing file.
 */
interface DictionaryEngine : AutoCloseable {
    val id: String

    val wordCount: Int

    /** Prefix-first search with format-appropriate fallbacks. */
    fun lookupSmart(query: String, limit: Int = 60): List<DictHit>

    /** Renders the article body addressed by [hit]. */
    fun article(hit: DictHit): Article
}
