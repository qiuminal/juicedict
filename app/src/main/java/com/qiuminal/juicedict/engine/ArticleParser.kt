package com.qiuminal.juicedict.engine

/**
 * One typed data section of a dictionary article, following the StarDict
 * "sametypesequence" rules:
 *
 *  - lowercase type: the field is a NUL-terminated UTF-8 string;
 *  - uppercase type: the field starts with a u32 BE length prefix;
 *  - the last field of an entry always consumes the remaining bytes.
 *
 * When `sameTypeSequence` is empty, every section in the entry carries its own
 * type character.
 */
data class ArticleSection(
    val type: Char,
    val data: ByteArray,
    /** Decoded UTF-8 text, or null for binary sections ('W' wav, 'P' picture, 'r' resources). */
    val text: String?,
)

object ArticleParser {

    fun parse(sameTypeSequence: String, raw: ByteArray): List<ArticleSection> {
        val sections = ArrayList<ArticleSection>(maxOf(1, sameTypeSequence.length))
        var p = 0
        if (sameTypeSequence.isEmpty()) {
            while (p < raw.size) {
                val type = raw[p].toInt().toChar()
                p++
                if (isLower(type)) {
                    var end = p
                    while (end < raw.size && raw[end] != 0.toByte()) end++
                    sections.add(section(type, raw, p, end))
                    p = end + 1
                } else {
                    require(p + 4 <= raw.size) { "truncated size prefix" }
                    val len = readIntBE(raw, p)
                    p += 4
                    val end = p + len
                    require(end <= raw.size) { "section exceeds entry size" }
                    sections.add(section(type, raw, p, end))
                    p = end
                }
            }
        } else {
            val n = sameTypeSequence.length
            for (i in 0 until n) {
                val type = sameTypeSequence[i]
                val last = i == n - 1
                val start = p
                if (last) {
                    p = raw.size
                } else if (isLower(type)) {
                    var end = p
                    while (end < raw.size && raw[end] != 0.toByte()) end++
                    p = end + 1
                } else {
                    require(p + 4 <= raw.size) { "truncated size prefix" }
                    val len = readIntBE(raw, p)
                    p += 4
                    require(p + len <= raw.size) { "section exceeds entry size" }
                    p += len
                }
                sections.add(section(type, raw, start, p))
            }
        }
        return sections
    }

    private fun section(type: Char, raw: ByteArray, from: Int, to: Int): ArticleSection {
        val data = raw.copyOfRange(from, to)
        val text = if (isBinary(type)) null else String(data, Charsets.UTF_8)
        return ArticleSection(type, data, text)
    }

    private fun isLower(c: Char) = c in 'a'..'z'
    private fun isBinary(c: Char) = c == 'W' || c == 'P' || c == 'r' || c == 'X'
}
