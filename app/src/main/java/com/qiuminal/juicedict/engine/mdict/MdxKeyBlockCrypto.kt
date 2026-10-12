package com.qiuminal.juicedict.engine.mdict

/**
 * Decryption for MDict key-block-info sections flagged with `Encrypted` bit 2.
 *
 * Many Chinese MDX dictionaries (现代汉语词典第5版, COD9 牛津现代英汉双解词典, ...) set
 * `Encrypted="2"`. This does **not** require a user password: only the key-block-info
 * (the small index that lists every key block's size and first/last key) is scrambled
 * with a SWAPNIBBLE + XOR stream cipher whose key is derived from the block's own
 * Adler-32 checksum via [Ripemd128]. The record data and the individual key blocks are
 * stored in the clear, so decrypting the key-block-info is the whole fix — without it
 * the index decompresses to garbage, every key is unreadable, and the dictionary
 * imports (the entry count sits in the plaintext preamble) but answers no lookups.
 *
 * `Encrypted` bit 1 (value 1) is a different scheme that needs the user's registration
 * key; those files stay unsupported and are rejected up front.
 *
 * Mirrors readmdict's `_mdx_decrypt` / `_fast_decrypt`.
 */
internal object MdxKeyBlockCrypto {

    /** `Encrypted` bit that needs a user passcode we do not have. */
    const val NEEDS_PASSWORD = 0x01

    /** `Encrypted` bit meaning the key-block-info is `_fast_encrypt`'d (decryptable). */
    const val KEY_INFO_ENCRYPTED = 0x02

    /**
     * Returns a copy of the key-block-info [block] with its 8-byte prefix
     * (`u32le type | u32be adler32`) preserved and its payload decrypted.
     *
     * key = ripemd128(block[4:8] + u32le(0x3695)); payload = fastDecrypt(block[8:], key).
     */
    fun decryptKeyInfo(block: ByteArray): ByteArray {
        require(block.size >= MdxBlockReader.HEADER_SIZE) {
            "truncated encrypted MDict key-block-info (${block.size} bytes)"
        }
        val seed = ByteArray(8)
        System.arraycopy(block, 4, seed, 0, 4)
        // struct.pack("<L", 0x3695)
        seed[4] = 0x95.toByte()
        seed[5] = 0x36.toByte()
        seed[6] = 0x00.toByte()
        seed[7] = 0x00.toByte()
        val key = Ripemd128.digest(seed)

        val out = block.copyOf()
        fastDecrypt(out, MdxBlockReader.HEADER_SIZE, key)
        return out
    }

    /**
     * MDict's `_fast_decrypt`: for each payload byte, swap its nibbles, then XOR with
     * the previous *ciphertext* byte, the running index and a key byte. `previous`
     * seeds at 0x36 and always tracks the byte as it was before decryption.
     */
    private fun fastDecrypt(data: ByteArray, from: Int, key: ByteArray) {
        var previous = 0x36
        var i = 0
        var p = from
        while (p < data.size) {
            val b = data[p].toInt() and 0xff
            val swapped = ((b ushr 4) or (b shl 4)) and 0xff
            val t = swapped xor previous xor (i and 0xff) xor (key[i % key.size].toInt() and 0xff)
            previous = b
            data[p] = t.toByte()
            i++
            p++
        }
    }
}
