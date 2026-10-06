package com.qiuminal.juicedict.engine.mdict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Pins the header parser, which is the one place where the MDict container's mixed byte
 * order and its non-obvious checksum coverage meet.
 *
 * The header is `<u32be byte length><UTF-16LE xml><u32le adler32>` and the checksum covers
 * only the XML, starting at offset 4 — *not* the length prefix and *not* the trailing NUL.
 * Getting that range wrong makes every real dictionary fail to open, so it is asserted
 * directly here on synthesised headers rather than only on real files.
 */
class MdxHeaderTest {

    /** Wraps [xml] in the real on-disk framing, computing the checksum unless overridden. */
    private fun headerBytes(
        xml: String,
        checksum: Int? = null,
        terminator: Boolean = true,
    ): ByteArray {
        val text = xml + if (terminator) "\u0000" else ""
        val body = text.toByteArray(Charsets.UTF_16LE)
        val out = ByteArrayOutputStream()
        out.write(
            byteArrayOf(
                (body.size ushr 24).toByte(), (body.size ushr 16).toByte(),
                (body.size ushr 8).toByte(), body.size.toByte(),
            ),
        )
        out.write(body)
        val sum = checksum ?: MdxBlockReader.adler32(out.toByteArray(), 4, body.size)
        out.write(
            byteArrayOf(
                sum.toByte(), (sum ushr 8).toByte(), (sum ushr 16).toByte(), (sum ushr 24).toByte(),
            ),
        )
        return out.toByteArray()
    }

    /** [MdxHeader.parse] or a hard failure, since these fixtures are all well formed. */
    private fun parse(xml: String): MdxHeader {
        val header = MdxHeader.parse(headerBytes(xml))
        assertNotNull("could not parse header: $xml", header)
        return header!!
    }

    private val article =
        "<Dictionary GeneratedByEngineVersion=\"2.0\" Encoding=\"UTF-8\" " +
            "Title=\"测试词典\" Description=\"desc\" KeyCaseSensitive=\"No\" StripKey=\"Yes\"/>"

    private val resource = "<Library_Data GeneratedByEngineVersion=\"2.0\" Title=\"res\"/>"

    @Test
    fun `parses an article header`() {
        val header = parse(article)
        assertEquals(MdxKind.ARTICLE, header.kind)
        assertEquals("Dictionary", header.kind.rootTag)
        assertEquals("UTF-8", header.encoding)
        assertEquals("测试词典", header.title)
        assertEquals("desc", header.description)
        assertEquals(2.0f, header.version, 0.001f)
        assertTrue("should not be encrypted", !header.isEncrypted)
    }

    @Test
    fun `key section starts right after the checksum`() {
        val bytes = headerBytes(article)
        val header = MdxHeader.parse(bytes)
        assertNotNull("could not parse header", header)
        // length prefix (4) + xml + checksum (4); the buffer holds exactly the header, so
        // the key section begins at its end.
        assertEquals(bytes.size.toLong(), header!!.keySectionOffset)
        assertEquals(4L + MdxBytes.u32be(bytes, 0) + 4L, header.keySectionOffset)
    }

    @Test
    fun `recognises a resource header`() {
        val header = parse(resource)
        assertEquals(MdxKind.RESOURCE, header.kind)
        assertEquals("Library_Data", header.kind.rootTag)
        // An MDD declares no Encoding; UTF-8 is the documented default and the key unit is
        // forced to 2 by MdxResourcePack regardless.
        assertEquals("UTF-8", header.encoding)
    }

    @Test
    fun `rejects a header whose checksum does not match`() {
        assertNull(MdxHeader.parse(headerBytes(article, checksum = 0x12345678)))
    }

    @Test
    fun `checksum is read little-endian`() {
        // The length prefix is big-endian but the checksum that follows the header string is
        // little-endian. Feeding the same value byte-reversed (i.e. big-endian) must fail.
        val body = (article + "\u0000").toByteArray(Charsets.UTF_16LE)
        val sum = MdxBlockReader.adler32(headerBytes(article), 4, body.size)
        val reversed = Integer.reverseBytes(sum)
        assertNull(MdxHeader.parse(headerBytes(article, checksum = reversed)))
    }

    @Test
    fun `rejects a truncated header`() {
        val bytes = headerBytes(article)
        assertNull(MdxHeader.parse(bytes.copyOfRange(0, bytes.size - 8)))
    }

    @Test
    fun `rejects a body that is not xml`() {
        assertNull(MdxHeader.parse(headerBytes("not xml at all")))
    }

    @Test
    fun `rejects an empty input`() {
        assertNull(MdxHeader.parse(ByteArray(0)))
    }

    @Test
    fun `self closing tag does not loop forever`() {
        // Regression: a '/'-only token in the attribute scanner used to leave the cursor
        // where it was and spin forever, which hung the parser on every real dictionary.
        assertEquals("UTF-8", parse("<Dictionary Encoding=\"UTF-8\"/>").encoding)
    }

    @Test
    fun `parses an attribute value containing spaces`() {
        val header = parse("<Dictionary Encoding=\"UTF-8\"  Title=\"  spaced  \" />")
        assertEquals("spaced", header.title)
    }

    @Test
    fun `strip key defaults on for articles and off for resources`() {
        assertTrue("article defaults to stripKey", parse(article).stripKey)
        assertTrue("resource defaults to no stripKey", !parse(resource).stripKey)
    }

    @Test
    fun `strip key attribute name is matched case insensitively`() {
        // Real dictionaries spell it both "StripKey" and "Stripkey".
        for (spelling in listOf("StripKey", "Stripkey", "stripkey")) {
            val header = parse("<Dictionary Encoding=\"UTF-8\" $spelling=\"Yes\"/>")
            assertTrue("$spelling should enable stripping", header.stripKey)
        }
        assertTrue(!parse("<Dictionary Encoding=\"UTF-8\" StripKey=\"No\"/>").stripKey)
    }

    @Test
    fun `encrypted flag is detected`() {
        val header = parse("<Dictionary Encoding=\"UTF-8\" Encrypted=\"2\"/>")
        assertTrue(header.isEncrypted)
        assertEquals(2, header.encrypted)
        assertTrue(!parse(article).isEncrypted)
    }

    @Test
    fun `entry count comes from the attribute when present`() {
        val declared = parse("<Dictionary Encoding=\"UTF-8\" num_entries=\"137390\"/>")
        assertEquals(137390L, declared.entryCount)
        // Absent means "unknown", never zero-length index: 《辞海第七版》 omits it while
        // its index still holds 137390 entries.
        assertEquals(0L, parse(resource).entryCount)
    }

    @Test
    fun `utf16 encoding is reported`() {
        assertTrue(parse("<Dictionary Encoding=\"UTF-16\"/>").isUtf16)
        assertTrue(!parse(article).isUtf16)
    }

    @Test
    fun `parses a real dictionary header`() {
        val file = realMdx() ?: return
        MdxFileReader(file).use { reader ->
            val head = reader.read(0L, minOf(reader.length, 1L shl 20).toInt())
            val header = MdxHeader.parse(head)
            assertNotNull("could not parse ${file.name}", header)
            assertEquals(MdxKind.ARTICLE, header!!.kind)
            assertTrue("header must precede the key section", header.keySectionOffset < reader.length)
            assertTrue("title must not be blank", header.title.isNotBlank())
        }
    }

    /**
     * A real dictionary from the developer's corpus, or null when the corpus is absent so
     * the synthetic tests above still run on a clean machine.
     */
    private fun realMdx(): File? {
        val root = File("E:\\dictionary\\MDict")
        if (!root.isDirectory) return null
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".mdx", ignoreCase = true) }
            .minByOrNull { it.length() }
    }
}
