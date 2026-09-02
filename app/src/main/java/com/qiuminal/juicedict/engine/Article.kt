package com.qiuminal.juicedict.engine

/**
 * Renders an article (list of sections) into plain text or HTML.
 * 'h' sections are HTML already; 'x' (XDXF), 'g' (Pango) and 'k' (KingSoft XML)
 * are treated as markup; everything else is escaped text.
 */
class Article(
    val word: String,
    val sameTypeSequence: String,
    val sections: List<ArticleSection>,
) {

    /** Plain-text preview with markup stripped, suitable for list items. */
    fun preview(maxChars: Int = 200): String {
        val sb = StringBuilder()
        for (s in sections) {
            val t = s.text ?: continue
            when (s.type) {
                'h', 'x', 'g', 'k', 'w' -> sb.append(stripTags(t))
                else -> sb.append(t)
            }
            sb.append(' ')
        }
        var text = sb.toString().replace(Regex("\\s+"), " ").trim()
        if (text.length > maxChars) text = text.substring(0, maxChars).trimEnd() + '…'
        return text
    }

    /** HTML document body for `HtmlCompat.fromHtml`. */
    fun toHtml(): String {
        val sb = StringBuilder()
        for (s in sections) {
            val t = s.text ?: continue
            when (s.type) {
                'h' -> sb.append(t)
                'x' -> sb.append(t)
                'g' -> sb.append(t)
                'k' -> sb.append(t)
                'w' -> sb.append(escapeHtml(t).replace("\n", "<br>"))
                'r' -> { /* resource list, not rendered in v1 */ }
                else -> sb.append(escapeHtml(t)).append("<br>")
            }
        }
        return sb.toString()
    }

    companion object {
        private val TAG = Regex("<[^>]+>")

        fun stripTags(s: String): String = s.replace(TAG, " ").replace("&nbsp;", " ")

        fun escapeHtml(s: String): String = buildString(s.length) {
            for (c in s) {
                when (c) {
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '&' -> append("&amp;")
                    '"' -> append("&quot;")
                    else -> append(c)
                }
            }
        }
    }
}
