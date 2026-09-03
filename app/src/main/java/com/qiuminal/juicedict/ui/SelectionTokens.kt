package com.qiuminal.juicedict.ui

/** 长按选词辅助：决定系统原生选区默认的取词范围（无 Android 依赖，便于单测）。 */
object SelectionTokens {

    fun isAsciiWordChar(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9'

    fun isCjkChar(c: Char): Boolean {
        val cp = c.code
        return cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF || cp in 0xF900..0xFAFF
    }

    /**
     * 系统原生长按选词后，把选区收敛为 [offset] 所在的可选单位：
     * - ASCII 字母/数字按标点与空白整词选中（hel-lo 长按在 hel 上只选 hel）；
     * - 汉字及其它字符默认只选单个字符（用户可拖原生手柄继续扩选成词）。
     */
    fun tokenRangeAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        val i = offset.coerceIn(0, text.length - 1)
        if (!isAsciiWordChar(text[i])) return i until i + 1
        var start = i
        while (start > 0 && isAsciiWordChar(text[start - 1])) start--
        var end = i + 1
        while (end < text.length && isAsciiWordChar(text[end])) end++
        return start until end
    }
}
