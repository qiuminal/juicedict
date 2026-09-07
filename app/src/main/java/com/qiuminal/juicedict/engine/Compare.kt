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
 *
 * 区域版本（[foldCompare] / [regionStartsWithFold] 带 CharArray 下标的重载）
 * 直接在大词表字符缓冲上比较，不为每次比较构造 String——百万级词条的索引
 * 排序与二分查找因此既快又不产生临时对象。
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

/** [foldCompare] 的区域版本：比较 data[aStart, aEnd) 与 [b]。 */
internal fun foldCompare(data: CharArray, aStart: Int, aEnd: Int, b: String): Int {
    val aLen = aEnd - aStart
    val n = minOf(aLen, b.length)
    for (i in 0 until n) {
        val ca = foldAscii(data[aStart + i])
        val cb = foldAscii(b[i])
        if (ca != cb) return if (ca < cb) -1 else 1
    }
    return when {
        aLen < b.length -> -1
        aLen > b.length -> 1
        else -> 0
    }
}

/** [foldCompare] 的双区域版本：比较 data 上的两个词（排序用，零分配）。 */
internal fun foldCompare(
    data: CharArray,
    aStart: Int, aEnd: Int,
    bStart: Int, bEnd: Int,
): Int {
    val aLen = aEnd - aStart
    val bLen = bEnd - bStart
    val n = minOf(aLen, bLen)
    for (i in 0 until n) {
        val ca = foldAscii(data[aStart + i])
        val cb = foldAscii(data[bStart + i])
        if (ca != cb) return if (ca < cb) -1 else 1
    }
    return when {
        aLen < bLen -> -1
        aLen > bLen -> 1
        else -> 0
    }
}

/** data[start, end) 折叠后是否以 [prefix] 开头（前缀查询用）。 */
internal fun regionStartsWithFold(data: CharArray, start: Int, end: Int, prefix: String): Boolean {
    if (end - start < prefix.length) return false
    for (i in 0 until prefix.length) {
        if (foldAscii(data[start + i]) != foldAscii(prefix[i])) return false
    }
    return true
}
