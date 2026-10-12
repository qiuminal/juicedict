package com.qiuminal.juicedict.engine.mdict

/**
 * RIPEMD-128 (Dobbertin, Bosselaers & Preneel, 1996).
 *
 * Implemented from scratch in pure Kotlin because the project forbids new Gradle
 * dependencies and the Android JVM ships no RIPEMD provider. Its one use here is to
 * derive the key-block-info decryption key for MDict files flagged with `Encrypted`
 * bit 2 (see [MdxKeyBlockCrypto]); it is not a general-purpose hashing utility.
 *
 * Verified against the published test vectors:
 * - `""`    -> `cdf26213a150dc3ecb610f18f6b38b46`
 * - `"a"`   -> `86be7afa339d0fc7cfc785e72f578d33`
 * - `"abc"` -> `c14a12199c66e4ba84636b0f69144c77`
 */
internal object Ripemd128 {

    /** Returns the 16-byte RIPEMD-128 digest of [message]. */
    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt()
        var h2 = 0x98badcfe.toInt()
        var h3 = 0x10325476

        val padded = pad(message)
        val x = IntArray(16)
        var base = 0
        while (base < padded.size) {
            for (j in 0 until 16) {
                val o = base + j * 4
                x[j] = (padded[o].toInt() and 0xff) or
                    ((padded[o + 1].toInt() and 0xff) shl 8) or
                    ((padded[o + 2].toInt() and 0xff) shl 16) or
                    ((padded[o + 3].toInt() and 0xff) shl 24)
            }

            var al = h0; var bl = h1; var cl = h2; var dl = h3
            var ar = h0; var br = h1; var cr = h2; var dr = h3

            for (s in 0 until 64) {
                val round = s / 16

                val tl = rotl(al + f(round, bl, cl, dl) + x[RL[s]] + KL[round], SL[s])
                al = dl; dl = cl; cl = bl; bl = tl

                val tr = rotl(ar + f(3 - round, br, cr, dr) + x[RR[s]] + KR[round], SR[s])
                ar = dr; dr = cr; cr = br; br = tr
            }

            val t = h1 + cl + dr
            h1 = h2 + dl + ar
            h2 = h3 + al + br
            h3 = h0 + bl + cr
            h0 = t

            base += 64
        }

        val out = ByteArray(16)
        putLe(out, 0, h0)
        putLe(out, 4, h1)
        putLe(out, 8, h2)
        putLe(out, 12, h3)
        return out
    }

    /** MD4-style padding: 0x80, zeros to 56 mod 64, then the 64-bit little-endian bit length. */
    private fun pad(message: ByteArray): ByteArray {
        val bitLen = message.size.toLong() * 8
        var padLen = 56 - (message.size + 1) % 64
        if (padLen < 0) padLen += 64
        val out = ByteArray(message.size + 1 + padLen + 8)
        System.arraycopy(message, 0, out, 0, message.size)
        out[message.size] = 0x80.toByte()
        var lenPos = out.size - 8
        var v = bitLen
        for (k in 0 until 8) {
            out[lenPos++] = (v and 0xff).toByte()
            v = v ushr 8
        }
        return out
    }

    private fun f(round: Int, x: Int, y: Int, z: Int): Int = when (round) {
        0 -> x xor y xor z
        1 -> (x and y) or (x.inv() and z)
        2 -> (x or y.inv()) xor z
        else -> (x and z) or (y and z.inv())
    }

    private fun rotl(v: Int, bits: Int): Int = (v shl bits) or (v ushr (32 - bits))

    private fun putLe(out: ByteArray, at: Int, v: Int) {
        out[at] = (v and 0xff).toByte()
        out[at + 1] = ((v ushr 8) and 0xff).toByte()
        out[at + 2] = ((v ushr 16) and 0xff).toByte()
        out[at + 3] = ((v ushr 24) and 0xff).toByte()
    }

    private val KL = intArrayOf(0x00000000, 0x5a827999, 0x6ed9eba1, 0x8f1bbcdc.toInt())
    private val KR = intArrayOf(0x50a28be6, 0x5c4dd124, 0x6d703ef3, 0x00000000)

    private val RL = intArrayOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        7, 4, 13, 1, 10, 6, 15, 3, 12, 0, 9, 5, 2, 14, 11, 8,
        3, 10, 14, 4, 9, 15, 8, 1, 2, 7, 0, 6, 13, 11, 5, 12,
        1, 9, 11, 10, 0, 8, 12, 4, 13, 3, 7, 15, 14, 5, 6, 2,
    )

    private val RR = intArrayOf(
        5, 14, 7, 0, 9, 2, 11, 4, 13, 6, 15, 8, 1, 10, 3, 12,
        6, 11, 3, 7, 0, 13, 5, 10, 14, 15, 8, 12, 4, 9, 1, 2,
        15, 5, 1, 3, 7, 14, 6, 9, 11, 8, 12, 2, 10, 0, 4, 13,
        8, 6, 4, 1, 3, 11, 15, 0, 5, 12, 2, 13, 9, 7, 10, 14,
    )

    private val SL = intArrayOf(
        11, 14, 15, 12, 5, 8, 7, 9, 11, 13, 14, 15, 6, 7, 9, 8,
        7, 6, 8, 13, 11, 9, 7, 15, 7, 12, 15, 9, 11, 7, 13, 12,
        11, 13, 6, 7, 14, 9, 13, 15, 14, 8, 13, 6, 5, 12, 7, 5,
        11, 12, 14, 15, 14, 15, 9, 8, 9, 14, 5, 6, 8, 6, 5, 12,
    )

    private val SR = intArrayOf(
        8, 9, 9, 11, 13, 15, 15, 5, 7, 7, 8, 11, 14, 14, 12, 6,
        9, 13, 15, 7, 12, 8, 9, 11, 7, 7, 12, 7, 6, 15, 13, 11,
        9, 7, 15, 11, 8, 6, 6, 14, 12, 13, 5, 14, 13, 13, 7, 5,
        15, 5, 8, 11, 14, 14, 6, 14, 6, 9, 12, 9, 12, 5, 15, 8,
    )
}
