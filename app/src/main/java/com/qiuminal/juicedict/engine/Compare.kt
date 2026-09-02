package com.qiuminal.juicedict.engine

/**
 * Comparison rules shared by the index, mirroring StarDict/sdcv:
 *
 *  - [stardictCompare] is sdcv's `stardict_strcmp`: ASCII case-insensitive byte
 *    comparison, with the exact (case-sensitive) comparison as tie-break.
 *  - [foldCompare] is the key order used by the search index: case-folded
 *    characters first; when folded forms are equal, shorter strings sort
 *    before longer ones and case variants compare as equal (stable sort keeps
 *    file order among them). This makes exact and prefix lookup case-insensitive
 *    while remaining allocation-free.
 */
internal fun foldAscii(c: Char): Char = if (c in 'A'..'Z') (c.code + 32).toChar() else c

fun foldCompare(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val ca = foldAscii(a[i])
        val cb = foldAscii(b[i])
        if (ca != cb) return if (ca < cb) -1 else 1
    }
    return when {
        a.length < b.length -> -1
        a.length > b.length -> 1
        else -> 0
    }
}

fun stardictCompare(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    for (i in 0 until n) {
        val ca = foldAscii(a[i])
        val cb = foldAscii(b[i])
        if (ca != cb) return if (ca < cb) -1 else 1
    }
    if (a.length != b.length) return if (a.length < b.length) -1 else 1
    return a.compareTo(b)
}
