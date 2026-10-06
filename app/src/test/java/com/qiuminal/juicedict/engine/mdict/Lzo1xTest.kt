package com.qiuminal.juicedict.engine.mdict

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Pins the pure-Kotlin LZO1X decoder.
 *
 * LZO never appears in the shipped corpus — all 15 real dictionaries are raw or zlib — so
 * without this test the decoder is entirely unexercised. The fixtures in
 * `src/test/resources/lzo/fixtures.txt` are real LZO1X streams produced by the reference
 * compressor (`lzo-core` 1.0.1) and each was round-tripped through the reference
 * decompressor before being written, so they cannot encode a wrong expectation. The
 * reference library is deliberately *not* a test dependency: the project forbids new
 * Gradle dependencies, so the streams are frozen as data instead.
 */
class Lzo1xTest {

    private class Fixture(val name: String, val compressed: ByteArray, val plain: ByteArray)

    private fun fixtures(): List<Fixture> {
        val stream = javaClass.getResourceAsStream("/lzo/fixtures.txt")
            ?: error("missing test resource /lzo/fixtures.txt")
        val decoder = Base64.getDecoder()
        return stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.mapNotNull { line ->
                val text = line.trimEnd('\n', '\r')
                if (text.isBlank() || text.startsWith("#")) return@mapNotNull null
                // Split on the first two spaces only: the plaintext base64 is empty for the
                // zero-length fixture, so a plain split would yield two fields there.
                val first = text.indexOf(' ')
                val second = text.indexOf(' ', first + 1)
                assertTrue("malformed fixture line: $text", first > 0 && second > first)
                Fixture(
                    text.substring(0, first),
                    decoder.decode(text.substring(first + 1, second)),
                    decoder.decode(text.substring(second + 1)),
                )
            }.toList()
        }
    }

    @Test
    fun `decompresses every reference fixture byte-exactly`() {
        val all = fixtures()
        assertTrue("fixture file should not be empty", all.size >= 30)
        for (f in all) {
            val out = Lzo1x.decompress(f.compressed, 0, f.compressed.size, f.plain.size.toLong())
            assertArrayEquals("fixture ${f.name}", f.plain, out)
        }
    }

    @Test
    fun `covers the shapes that matter`() {
        val names = fixtures().map { it.name }.toSet()
        // A pure literal run, self-overlapping distance-1 runs, the short 3-byte match that
        // follows a literal run, far matches needing M4's 0x4000 bias, and incompressible input.
        assertTrue("literal-only fixture missing", names.contains("literal-8"))
        assertTrue("distance-1 run fixture missing", names.contains("run-a-50000"))
        assertTrue("extended length fixture missing", names.contains("zeros-70000"))
        assertTrue("short-match fixture missing", names.contains("period-3-x40"))
        assertTrue("far-distance fixture missing", names.contains("far-random-33000"))
        assertTrue("incompressible fixture missing", names.contains("random-1000"))
        assertTrue("long-distance fixture missing", names.contains("far-random-17000"))
    }

    @Test
    fun `ignores the declared size when allocating`() {
        // The stream's own end-of-stream marker defines the length, so a wrong hint must not
        // change the result (only the initial buffer capacity).
        val f = fixtures().first { it.name == "period-7-x500" }
        assertArrayEquals(f.plain, Lzo1x.decompress(f.compressed, 0, f.compressed.size, 0L))
        assertArrayEquals(f.plain, Lzo1x.decompress(f.compressed, 0, f.compressed.size, 1L))
        assertArrayEquals(
            f.plain,
            Lzo1x.decompress(f.compressed, 0, f.compressed.size, (f.plain.size * 4).toLong()),
        )
    }

    @Test
    fun `decompresses a sub-range of a larger array`() {
        // MdxBlockReader hands the payload as a slice of the block, so honouring off/len matters.
        val f = fixtures().first { it.name == "phrase-x30" }
        val padded = ByteArray(f.compressed.size + 6)
        System.arraycopy(f.compressed, 0, padded, 3, f.compressed.size)
        assertArrayEquals(f.plain, Lzo1x.decompress(padded, 3, f.compressed.size, f.plain.size.toLong()))
    }

    @Test
    fun `end of stream marker yields empty output`() {
        // 11 00 00 is M4 with a zero biased distance: the end-of-stream marker.
        val eos = byteArrayOf(0x11, 0x00, 0x00)
        assertEquals(0, Lzo1x.decompress(eos, 0, eos.size, 0L).size)
    }

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, Lzo1x.decompress(ByteArray(0), 0, 0, 0L).size)
    }

    @Test
    fun `hand built literal run round trips`() {
        // 0x19 = 25 = 8 + 17: an eight-byte literal run, then the end-of-stream marker.
        val stream = byteArrayOf(
            0x19, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x11, 0x00, 0x00,
        )
        assertArrayEquals("ABCDEFGH".toByteArray(), Lzo1x.decompress(stream, 0, stream.size, 0L))
    }

    @Test
    fun `truncated literal run is rejected`() {
        val stream = byteArrayOf(0x19, 0x41, 0x42)
        assertThrows(IllegalArgumentException::class.java) {
            Lzo1x.decompress(stream, 0, stream.size, 0L)
        }
    }

    @Test
    fun `match reaching before the output start is rejected`() {
        // 0x12 = 18 -> one leading literal, which then takes the plain-match path. The next
        // byte 0x40 is M2, and its distance byte 0x08 gives distance 65 with only 1 byte of
        // output so far.
        val stream = byteArrayOf(0x12, 0x41, 0x40, 0x08)
        assertThrows(IllegalArgumentException::class.java) {
            Lzo1x.decompress(stream, 0, stream.size, 0L)
        }
    }
}
