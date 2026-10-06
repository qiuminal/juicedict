package com.qiuminal.juicedict.engine.mdict

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random-access reader over an MDict file.
 *
 * MDict is a container of independently-compressed blocks addressed by absolute file
 * offset, so every read here is `seek` + `read` on a `RandomAccessFile` — nothing is
 * loaded wholesale and a 53 MB dictionary costs only its index in memory.
 *
 * Instances are closed by the owning dictionary; the engine never shares one across
 * dictionaries.
 */
internal class MdxFileReader(private val file: File) : Closeable {

    val length: Long get() = raf.length()

    private val raf = RandomAccessFile(file, "r")

    /** Reads exactly [size] bytes at [offset]; throws when the file is truncated. */
    fun read(offset: Long, size: Int): ByteArray {
        require(size >= 0) { "negative read size" }
        val out = ByteArray(size)
        if (size == 0) return out
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    /** Reads up to [size] bytes at [offset], returning fewer only at end of file. */
    fun readUpTo(offset: Long, size: Int): ByteArray {
        if (size <= 0) return ByteArray(0)
        val available = (length - offset).coerceAtLeast(0L)
        val n = minOf(available, size.toLong()).toInt()
        if (n <= 0) return ByteArray(0)
        raf.seek(offset)
        return read(offset, n)
    }

    override fun close() {
        runCatching { raf.close() }
    }
}
