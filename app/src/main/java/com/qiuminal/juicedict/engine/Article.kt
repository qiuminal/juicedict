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
                // Pango/XDXF 多行正文（如 chibigenc 的 <b>1.</b> 义项分行）在 HTML
                // 中会折叠换行，转成 <br> 保留词条原有段落结构。
                'x', 'g', 'k' -> sb.append(linkifyMarkup(t).replace("\n", "<br>"))
                'w' -> sb.append(escapeHtml(t).replace("\n", "<br>"))
                'r' -> { /* resource list, not rendered in v1 */ }
                else -> sb.append(escapeHtml(t)).append("<br>")
            }
        }
        return sb.toString()
    }

    companion object {
        private val TAG = Regex("<[^>]+>")

        /** Pango hyperlinks: `<span foreground="blue">X</span>` -> lookup anchor. */
        private val PANGO_LINK =
            Regex("""<span[^>]*?foreground\s*=\s*["']blue["'][^>]*?>(.*?)</span>""", RegexOption.IGNORE_CASE)
        /** Other Pango spans carry styling only; unwrap them and keep the text. */
        private val OTHER_SPAN = Regex("""<span[^>]*?>(.*?)</span>""", RegexOption.IGNORE_CASE)

        private const val LOOKUP_SCHEME = "juice://lookup/"

        /**
         * Normalizes dictionary markup into HtmlCompat-friendly HTML.
         * Pango blue spans (chibigenc 汉语大词典 cross references such as
         * `<span foreground="blue">篳輅</span>`) become clickable anchors
         * `<a href="juice://lookup/…">篳輅</a>`; the UI intercepts the scheme
         * and looks the word up. Real HTML sections ('h') are passed through.
         */
        fun linkifyMarkup(markup: String): String {
            var out = PANGO_LINK.replace(markup) { m ->
                val word = m.groupValues[1].trim()
                if (word.isEmpty()) "" else "<a href=\"$LOOKUP_SCHEME${urlEncode(word)}\">$word</a>"
            }
            out = OTHER_SPAN.replace(out) { m -> m.groupValues[1] }
            return out
        }

        private fun urlEncode(s: String): String =
            java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

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
