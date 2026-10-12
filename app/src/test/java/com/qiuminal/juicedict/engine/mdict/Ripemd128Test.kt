package com.qiuminal.juicedict.engine.mdict

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RIPEMD-128 against the published test vectors, plus a round-trip check of the
 * MDict key-block-info stream cipher. These guard the Encrypted=2 support: a wrong
 * hash or a wrong cipher silently produces garbage keys rather than an exception.
 */
class Ripemd128Test {

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun ripemd(s: String): String =
        hex(Ripemd128.digest(s.toByteArray(Charsets.US_ASCII)))

    @Test
    fun `official test vectors`() {
        assertEquals("cdf26213a150dc3ecb610f18f6b38b46", ripemd(""))
        assertEquals("86be7afa339d0fc7cfc785e72f578d33", ripemd("a"))
        assertEquals("c14a12199c66e4ba84636b0f69144c77", ripemd("abc"))
        assertEquals("9e327b3d6e523062afc1132d7df9d1b8", ripemd("message digest"))
        assertEquals(
            "fd2aa607f71dc8f510714922b371834e",
            ripemd("abcdefghijklmnopqrstuvwxyz"),
        )
    }

    @Test
    fun `vector that crosses a padding block boundary`() {
        // 62 bytes: with the 0x80 terminator and 8-byte length this spills into a
        // second padded block, exercising the multi-block loop.
        val s = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals(62, s.length)
        assertEquals("d1e959eb179c911faea4624c60c5c702", ripemd(s))
    }

    /**
     * `_fast_decrypt` must exactly invert the writer's `_fast_encrypt`. The encryption
     * side is defined here so the test does not depend on any real dictionary bytes.
     */
    @Test
    fun `fast cipher round trips through the block reader prefix`() {
        val payload = ByteArray(300) { ((it * 37 + 11) and 0xff).toByte() }
        val block = ByteArray(MdxBlockReader.HEADER_SIZE + payload.size)
        // zlib type + an arbitrary Adler-32 that drives the key derivation.
        block[0] = 0x02
        block[4] = 0x11; block[5] = 0x22; block[6] = 0x33; block[7] = 0x44
        System.arraycopy(payload, 0, block, MdxBlockReader.HEADER_SIZE, payload.size)

        val seed = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x95.toByte(), 0x36, 0x00, 0x00)
        val key = Ripemd128.digest(seed)
        fastEncrypt(block, MdxBlockReader.HEADER_SIZE, key)

        val decrypted = MdxKeyBlockCrypto.decryptKeyInfo(block)

        // Prefix untouched; payload restored byte-for-byte.
        for (i in 0 until MdxBlockReader.HEADER_SIZE) {
            assertEquals(block[i], decrypted[i])
        }
        for (i in payload.indices) {
            assertEquals(payload[i], decrypted[MdxBlockReader.HEADER_SIZE + i])
        }
    }

    /** The inverse of MDict's `_fast_decrypt`, used only to build the test input. */
    private fun fastEncrypt(data: ByteArray, from: Int, key: ByteArray) {
        var previous = 0x36
        var i = 0
        var p = from
        while (p < data.size) {
            val plain = data[p].toInt() and 0xff
            val mixed = plain xor previous xor (i and 0xff) xor (key[i % key.size].toInt() and 0xff)
            val swapped = ((mixed ushr 4) or (mixed shl 4)) and 0xff
            data[p] = swapped.toByte()
            previous = swapped
            i++
            p++
        }
    }
}
