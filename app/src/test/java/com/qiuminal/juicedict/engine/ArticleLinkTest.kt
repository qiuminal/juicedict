package com.qiuminal.juicedict.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.0.2：详情内词库互见链接的 markup 归一化。 */
class ArticleLinkTest {

    @Test
    fun pangoBlueSpanBecomesLookupAnchor() {
        val html = Article.linkifyMarkup("見“<span foreground=\"blue\">篳輅</span>”。")
        assertTrue(
            "expected lookup anchor, got: $html",
            html.contains("<a href=\"juice://lookup/%E7%AF%B3%E8%BC%85\">篳輅</a>"),
        )
    }

    @Test
    fun nonLinkSpansAreUnwrapped() {
        val html = Article.linkifyMarkup("<span size=\"large\">粗体</span> 与 <span foreground=\"blue\">链接</span>")
        assertEquals("粗体 与 <a href=\"juice://lookup/%E9%93%BE%E6%8E%A5\">链接</a>", html)
    }

    @Test
    fun markupNewlinesBecomeBreaks() {
        val article = Article(
            word = "篳路",
            sameTypeSequence = "g",
            sections = listOf(
                ArticleSection(
                    type = 'g',
                    data = ByteArray(0),
                    text = "bì lù、\n見“<span foreground=\"blue\">篳輅</span>”。",
                ),
            ),
        )
        val html = article.toHtml()
        assertTrue(html.contains("bì lù、<br>見“"))
        assertTrue(html.contains("juice://lookup/%E7%AF%B3%E8%BC%85"))
    }
}
