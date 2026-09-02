package com.qiuminal.juicedict.engine

/**
 * Binary helpers for the StarDict binary formats.
 * All multi-byte integers in .idx/.dict are network byte order (big-endian).
 */
internal fun readIntBE(b: ByteArray, pos: Int): Int =
    ((b[pos].toInt() and 0xff) shl 24) or
        ((b[pos + 1].toInt() and 0xff) shl 16) or
        ((b[pos + 2].toInt() and 0xff) shl 8) or
        (b[pos + 3].toInt() and 0xff)

internal fun readLongBE(b: ByteArray, pos: Int): Long {
    var v = 0L
    for (i in 0 until 8) v = (v shl 8) or (b[pos + i].toLong() and 0xff)
    return v
}
