package com.qiuminal.juicedict.engine.mdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the key ordering that MDict's writers actually used.
 *
 * This is the single most dangerous piece of the parser: the index is only a binary
 * search over a byte range, so a comparator that disagrees with the writer does not throw
 * — it silently misses entries. Three independent rules were reverse engineered from real
 * files and each one is asserted here:
 *
 *  1. keys are folded to lower case;
 *  2. punctuation is stripped only when the header says `StripKey`, and only for ASCII;
 *  3. comparison is on UTF-8 **bytes**, not Kotlin `String` order (which is UTF-16 code
 *     units, so astral characters land in the wrong place).
 */
class MdxKeyOrderTest {

    private fun sorted(vararg keys: String): List<String> =
        keys.toList().sortedWith(MdxKeyOrder.comparator(stripKey = true))

    private fun sortedRaw(vararg keys: String): List<String> =
        keys.toList().sortedWith(MdxKeyOrder.comparator(stripKey = false))

    @Test
    fun `folds case`() {
        // Observed in 《新华字典12.mdd》, whose key block stores \hei_xhzd.woff third and
        // \XHZD_12.css fourth: folding makes 'h' < 'x'. Comparing raw, unfolded bytes
        // would instead expect \XHZD_12.css first and flag the real file as unsorted.
        assertEquals(
            listOf("\\hei_xhzd.woff", "\\XHZD_12.css"),
            sorted("\\XHZD_12.css", "\\hei_xhzd.woff"),
        )
    }

    @Test
    fun `strips ascii punctuation when the header says so`() {
        // Observed in 《辞海第七版》: 2.5D机织物 follows 21世纪议程 because the dot is
        // dropped and "1" < "5".
        assertEquals(
            listOf("21世纪议程", "2.5D机织物"),
            sorted("2.5D机织物", "21世纪议程"),
        )
    }

    @Test
    fun `keeps punctuation when the header says not to`() {
        // Every released MDD declares StripKey="No": \0.png precedes \00.png on the dot.
        assertEquals(
            listOf("\\0.png", "\\00.png"),
            sortedRaw("\\00.png", "\\0.png"),
        )
    }

    @Test
    fun `strips whitespace regardless of stripKey`() {
        assertEquals(listOf("a b", "ab c"), sorted("ab c", "a b"))
        assertEquals(listOf("a b", "ab c"), sortedRaw("ab c", "a b"))
    }

    @Test
    fun `does not strip non-ascii punctuation`() {
        // Deliberately ASCII-only: stripping CJK punctuation would reorder 现代·汉语.
        assertEquals(
            listOf("现代·汉语", "现代汉语"),
            sorted("现代汉语", "现代·汉语"),
        )
    }

    @Test
    fun `compares utf8 bytes not utf16 code units`() {
        // U+F97F (EF A9 BF) sorts before U+20164 (F0 A0 85 A4) on bytes, whereas Kotlin's
        // String.compareTo sees the surrogate pair D840 DC64 and puts U+20164 first.
        val bmp = "\uF97F"
        val astral = "\uD840\uDC64"
        assertTrue("sanity: String order disagrees", astral < bmp)
        assertEquals(listOf(bmp, astral), sorted(astral, bmp))
    }

    @Test
    fun `compare is consistent with the comparator`() {
        val pairs = listOf(
            "abc" to "abd",
            "abc" to "ABC",
            "a b" to "ab",
            "2.5D" to "21",
            "\uF97F" to "\uD840\uDC64",
        )
        for ((a, b) in pairs) {
            val byComparator = MdxKeyOrder.comparator(true).compare(a, b)
            assertEquals(
                "comparator and compare disagree for '$a' vs '$b'",
                Integer.signum(byComparator),
                Integer.signum(MdxKeyOrder.compare(a, b, stripKey = true)),
            )
        }
    }

    @Test
    fun `equal after folding compares equal`() {
        assertEquals(0, MdxKeyOrder.compare("ABC", "abc", stripKey = true))
        assertEquals(0, MdxKeyOrder.compare("a-b", "ab", stripKey = true))
        assertTrue(MdxKeyOrder.compare("a-b", "ab", stripKey = false) != 0)
    }

    @Test
    fun `sorting a real dictionary keeps its keys in order`() {
        // The strongest form of this test: take a real file's keys in on-disk order and
        // confirm the comparator agrees they are sorted. This is what the binary search
        // depends on, and it catches a rule mismatch that unit examples alone can miss.
        val file = realMdx() ?: return
        MdxFileReader(file).use { reader ->
            val head = MdxHeader.parse(reader.read(0L, minOf(reader.length, 1L shl 20).toInt()))
                ?: return
            val index = MdxKeyIndex.parse(
                reader = reader,
                offset = head.keySectionOffset,
                unitSize = if (head.isUtf16) 2 else 1,
                declaredEntries = head.entryCount,
                version = head.version,
                stripKey = head.stripKey,
            )
            val order = MdxKeyOrder.comparator(head.stripKey)
            var previous: String? = null
            var checked = 0
            for (block in 0 until index.blockCount.toInt()) {
                for (key in keysIn(reader, index, block)) {
                    val prev = previous
                    if (prev != null) {
                        assertTrue(
                            "keys out of order in ${file.name}: '$prev' then '$key'",
                            order.compare(prev, key) <= 0,
                        )
                        checked++
                    }
                    previous = key
                }
                if (checked > 20000) break
            }
            assertTrue("expected to compare some keys", checked > 0)
        }
    }

    private fun keysIn(reader: MdxFileReader, index: MdxKeyIndex, block: Int): List<String> {
        val bytes = MdxBlockReader.read(
            reader.read(index.blockOffsetAt(block), index.blockCompSizeAt(block).toInt()),
            index.blockDecompSizeAt(block),
        )
        val out = ArrayList<String>()
        var p = 0
        while (p + 8 <= bytes.size) {
            p += 8
            val start = p
            while (p < bytes.size && bytes[p].toInt() != 0) p++
            if (p >= bytes.size) break
            out.add(String(bytes, start, p - start, Charsets.UTF_8))
            p++
        }
        return out
    }

    private fun realMdx(): java.io.File? {
        val root = java.io.File("E:\\dictionary\\MDict")
        if (!root.isDirectory) return null
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".mdx", ignoreCase = true) }
            .minByOrNull { it.length() }
    }
}
