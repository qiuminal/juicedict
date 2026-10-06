package com.qiuminal.juicedict.engine.mdict

/**
 * Key ordering for MDict lookups.
 *
 * MDict does not sort keys by their raw text, and it does not sort them by Kotlin
 * `String` order either. Three separate details have to be right, and each one is
 * observable in the shipped dictionaries under `E:\dictionary\MDict`:
 *
 * 1. **Case folding.** Every file here declares `KeyCaseSensitive="No"`, so
 *    `\hei_xhzd.woff` sorts before `\XHZD_12.css`: once folded, `h` (0x68) < `x`
 *    (0x78). Raw-byte comparison of the unfolded text puts `\XHZD_12.css` first
 *    and flags the real order as a violation.
 *
 * 2. **`Stripkey`, driven by the file's own header.** Both MDX files declare
 *    `Stripkey="Yes"`, so `2.5D机织物` follows `21世纪议程`: the dot is dropped
 *    and `1` < `5` decides. Every released MDD declares `StripKey="No"`, so
 *    `\0.png` precedes `\00.png` on the dot. Applying one rule to both files
 *    produces false violations in whichever direction is wrong.
 *
 * 3. **UTF-8 byte order, not UTF-16 code-unit order.** MDict compares the encoded
 *    bytes, so U+F97F (`EF A9 BF`) precedes U+20164 (`F0 A0 85 A4`). Kotlin's
 *    `String.compareTo` compares UTF-16 code units, and U+20164 becomes the
 *    surrogate pair `D840 DC64`, so it sorts *before* U+F97F. This is the subtle
 *    one: it silently breaks binary search rather than throwing, and it only
 *    shows up on keys outside the BMP.
 */
internal object MdxKeyOrder {
    /**
     * Builds the comparator matching a file's declared key ordering.
     *
     * @param stripKey whether the writer stripped punctuation before sorting
     */
    fun comparator(stripKey: Boolean): Comparator<String> = Comparator { a, b ->
        compare(a, b, stripKey)
    }

    /** Compares two keys under the given stripping rule. */
    fun compare(a: String, b: String, stripKey: Boolean): Int {
        val x = fold(a, stripKey).toByteArray(Charsets.UTF_8)
        val y = fold(b, stripKey).toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    /**
     * Normalizes a key the way the writer did before sorting, so that a user's
     * query hits the same slot the index was built under.
     */
    fun fold(s: String, stripKey: Boolean): String {
        val lowered = s.lowercase()
        return if (stripKey) lowered.filterNot { it.isWhitespace() || isStripChar(it) } else lowered
    }

    /**
     * Punctuation MDict drops when `Stripkey` is on.
     *
     * Deliberately ASCII-only: the dictionaries here mix CJK text with ASCII
     * punctuation, and every observed case (`2.5D`, `A-U`, `81/2`, `C#`) involves
     * ASCII. Stripping CJK punctuation as well would reorder keys like `现代·汉语`.
     */
    private fun isStripChar(c: Char): Boolean =
        c.code < 0x80 && !c.isLetterOrDigit()
}
