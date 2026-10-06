package com.qiuminal.juicedict.engine.mdict

import java.io.File

/**
 * Locates the resource packs that belong to one MDX dictionary.
 *
 * MDict splits a dictionary's images and audio across `<base>.mdd` and numbered siblings
 * (`<base>.1.mdd`, `<base>.2.mdd`, …): 《字源》 keeps 43MB of scans in `字源 (2012).1.mdd`
 * and 《新华字典12》 keeps its MP3s in `新华字典12.1.mdd`. The numbered packs carry no
 * separate identity — their entries are looked up as a continuation of `<base>.mdd` — so
 * they are returned in numeric order and the caller searches them in that order.
 *
 * The rule lives here rather than in the repository so both the app and the verification
 * tooling pair files the same way, and so it can be unit tested without a `Context`.
 */
internal object MdxResourceFiles {

    /** Suffix of a resource pack, matched case-insensitively. */
    private const val SUFFIX = ".mdd"

    /**
     * Resource packs for `base` inside `dir`: `<base>.mdd` first (when present), then
     * `<base>.1.mdd`, `<base>.2.mdd`, … in ascending numeric order.
     *
     * A name only counts as an ordinal pack when the segment between the base and the
     * suffix parses as an integer, so an unrelated `<base>.images.mdd` is ignored.
     */
    fun pairsFor(dir: File, base: String): List<File> {
        val out = ArrayList<File>()
        File(dir, "$base$SUFFIX").takeIf { it.isFile }?.let { out.add(it) }
        val prefix = "$base."
        (dir.listFiles() ?: emptyArray())
            .mapNotNull { f ->
                if (!f.isFile) return@mapNotNull null
                val name = f.name
                if (!name.endsWith(SUFFIX, ignoreCase = true)) return@mapNotNull null
                if (!name.startsWith(prefix)) return@mapNotNull null
                // `<base>.mdd` itself also starts with `<base>.`; it was already added
                // above and has no ordinal segment, so skip anything without one.
                if (name.length <= prefix.length + SUFFIX.length) return@mapNotNull null
                val middle = name.substring(prefix.length, name.length - SUFFIX.length)
                val ordinal = middle.toIntOrNull() ?: return@mapNotNull null
                ordinal to f
            }
            .sortedBy { it.first }
            .forEach { out.add(it.second) }
        return out
    }
}
