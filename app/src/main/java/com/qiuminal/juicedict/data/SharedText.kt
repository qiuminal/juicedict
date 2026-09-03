package com.qiuminal.juicedict.data

/**
 * 从系统分享（ACTION_SEND）的 EXTRA_TEXT 中提取适合词典查询的词句。
 * 阅读软件常把整段文字一起分享，或给选中的词套上引号/书名号：
 * 只取首个非空行，并去掉首尾成对的包裹引号，让查询词更干净。
 */
object SharedTextParser {

    /** 可能包裹分享文本的成对引号（开 -> 合）。 */
    private val quotePairs = mapOf(
        '"' to '"',
        '\'' to '\'',
        '\u201C' to '\u201D', // “ ”
        '\u2018' to '\u2019', // ‘ ’
        '\u300C' to '\u300D', // 「 」
        '\u300E' to '\u300F', // 『 』
        '\u00AB' to '\u00BB', // « »
    )

    fun extract(shared: String?): String {
        if (shared.isNullOrBlank()) return ""
        val firstLine = shared.lineSequence().firstOrNull { it.isNotBlank() } ?: return ""
        val trimmed = firstLine.trim()
        if (trimmed.length <= 2) return trimmed
        val open = trimmed.first()
        val close = trimmed.last()
        return if (quotePairs[open] == close) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
    }
}
