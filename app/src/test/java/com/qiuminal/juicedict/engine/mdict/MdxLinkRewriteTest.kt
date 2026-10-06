package com.qiuminal.juicedict.engine.mdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the rewrite of MDict's in-text cross references into the scheme the app's
 * `SelectableLinkTextView` already intercepts.
 *
 * This is what makes 互见 (see-also) navigation work at all: MDict entries link with
 * `entry://target`, and the reader only reacts to `juice://lookup/<urlencoded>`. The
 * rewrite must not touch the entry's HTML otherwise, or the article stops rendering.
 */
class MdxLinkRewriteTest {

    private fun rewrite(text: String) = MdxDictionary.rewriteLinks(text)

    @Test
    fun `rewrites a plain entry link`() {
        assertEquals(
            "see <a href=\"juice://lookup/%E6%B1%89%E5%AD%97\">汉字</a>",
            rewrite("see <a href=\"entry://汉字\">汉字</a>"),
        )
    }

    @Test
    fun `leaves text without links untouched and identical`() {
        val text = "<div class=\"entry\"><p>没有链接</p></div>"
        // Must be returned as the very same object, not merely equal: article bodies are
        // large and the pass is skipped entirely when no scheme is present.
        assertTrue("expected the identical instance", text === rewrite(text))
    }

    @Test
    fun `keeps same entry anchors local`() {
        assertEquals("<a href=\"#frag\">x</a>", rewrite("<a href=\"entry://#frag\">x</a>"))
    }

    @Test
    fun `keeps a link with an empty target local`() {
        assertEquals("<a href=\"#\">x</a>", rewrite("<a href=\"entry://\">x</a>"))
    }

    @Test
    fun `rewrites every link in the body`() {
        val html = "<a href=\"entry://甲\">1</a> then <a href=\"entry://乙\">2</a>"
        val out = rewrite(html)
        assertFalse("no entry scheme may survive", out.contains("entry://"))
        assertEquals(2, out.split("juice://lookup/").size - 1)
    }

    @Test
    fun `stops the target at a quote`() {
        assertEquals(
            "<a href=\"juice://lookup/ab\">ab</a><b>",
            rewrite("<a href=\"entry://ab\">ab</a><b>"),
        )
    }

    @Test
    fun `stops the target at a space or bracket`() {
        // The terminator itself is not consumed, so it survives into the output.
        assertEquals("<a href=\"juice://lookup/ab \">", rewrite("<a href=\"entry://ab \">"))
        assertEquals("<a href=\"juice://lookup/ab)\">", rewrite("<a href=\"entry://ab)\">"))
        assertEquals("<a href='juice://lookup/ab'>", rewrite("<a href='entry://ab'>"))
    }

    @Test
    fun `a space always terminates so no target ever carries one`() {
        // A census over 7 real dictionaries (995k links) found no target containing a
        // space: the space terminates the target, so `b` is left as plain body text and
        // never becomes part of the encoded query.
        assertEquals("<a href=\"juice://lookup/a b\">", rewrite("<a href=\"entry://a b\">"))
    }

    @Test
    fun `percent encodes non ascii targets`() {
        val out = rewrite("<a href=\"entry://漢語大字典\">")
        assertTrue("expected percent encoding, got $out", out.contains("%E6%BC%A2"))
        assertFalse(out.contains("漢語大字典\">juice"))
    }

    @Test
    fun `empty target collapses to a bare hash`() {
        // A bare `entry://` not followed by a target is not a link; the pass is textual
        // rather than HTML-aware, so it degrades to a local '#'. Documented, not desired.
        assertEquals("the literal word # is not a link here",
            rewrite("the literal word entry:// is not a link here"))
    }

    @Test
    fun `handles a link at the very end of the text`() {
        assertEquals("<a href=\"juice://lookup/ab\">", rewrite("<a href=\"entry://ab\">"))
    }

    @Test
    fun `rewrites real dictionary bodies`() {
        // End-to-end on the real corpus: every article must come back free of entry://,
        // which is the invariant MdxSmoke also checks.
        val root = java.io.File("E:\\dictionary\\MDict")
        if (!root.isDirectory) return
        val files = root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".mdx", ignoreCase = true) }
            .sortedBy { it.length() }
            .take(3)
            .toList()
        if (files.isEmpty()) return
        var withLinks = 0
        var checked = 0
        for (mdx in files) {
            MdxDictionary(
                id = "test",
                mdxFile = mdx,
                mddFiles = emptyList(),
                title = "t",
                description = "d",
            ).use { dict ->
                dict.forEachWord { _, word ->
                    if (checked >= 400) return@forEachWord
                    checked++
                    val hit = dict.lookupExact(word, 1).firstOrNull() ?: return@forEachWord
                    val html = dict.article(hit).toHtml()
                    assertFalse("entry:// survived in '$word' of ${mdx.name}",
                        html.contains("entry://"))
                    if (html.contains("juice://lookup/")) withLinks++
                }
            }
            if (withLinks > 0) break
        }
        // If no link were rewritten the test would pass vacuously, so require evidence the
        // pass actually did something across the sampled dictionaries.
        assertTrue("no rewritten links observed in $files", withLinks > 0)
    }
}
