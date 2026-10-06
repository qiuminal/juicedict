package com.qiuminal.juicedict.engine.mdict

/**
 * Binary helpers for the MDict (MDX/MDD) container format.
 *
 * MDict mixes endianness deliberately, so every reader names its byte order
 * explicitly:
 *
 *  - the header length and all section/index integers are **big-endian**
 *    (`>L`, `>Q` in writemdict);
 *  - the per-block compression type is **little-endian** (`<L`).
 *
 * Getting this wrong is the classic MDict parsing bug (a big-endian block type of
 * zlib reads as 0x02000000), hence the naming.
 */
internal object MdxBytes {

    fun u8(b: ByteArray, pos: Int): Int = b[pos].toInt() and 0xff

    fun u16be(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xff) shl 8) or (b[pos + 1].toInt() and 0xff)

    fun u32be(b: ByteArray, pos: Int): Int =
        ((b[pos].toInt() and 0xff) shl 24) or
            ((b[pos + 1].toInt() and 0xff) shl 16) or
            ((b[pos + 2].toInt() and 0xff) shl 8) or
            (b[pos + 3].toInt() and 0xff)

    fun u32le(b: ByteArray, pos: Int): Int =
        (b[pos].toInt() and 0xff) or
            ((b[pos + 1].toInt() and 0xff) shl 8) or
            ((b[pos + 2].toInt() and 0xff) shl 16) or
            ((b[pos + 3].toInt() and 0xff) shl 24)

    fun u64be(b: ByteArray, pos: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[pos + i].toLong() and 0xff)
        return v
    }

    /** Reads a big-endian integer of [width] bytes (4 for v1.2, 8 for v2.0). */
    fun uBe(b: ByteArray, pos: Int, width: Int): Long {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or (b[pos + i].toLong() and 0xff)
        return v
    }
}
