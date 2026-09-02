package com.qiuminal.juicedict.engine

import java.io.File
import java.io.RandomAccessFile
import java.util.LinkedHashMap
import java.util.zip.Inflater

/**
 * Random-access reader for dictzip (`.dict.dz`) files as produced by the
 * `dictzip` tool and used by sdcv/dictd.
 *
 * A .dz file is a gzip stream whose deflate payload is split into independent
 * chunks of [chunkLength] uncompressed bytes. The gzip extra field "RA" holds
 * the version, chunk length, chunk count and the compressed size of every
 * chunk, so any chunk can be inflated on its own (raw deflate).
 */
class DictZipReader(file: File, cacheSize: Int = 16) : DictDataReader {
    private val raf = RandomAccessFile(file, "r")
    private val chunkLength: Int
    private val chunks: IntArray
    private val chunkOffsets: LongArray
    private val cache = object : LinkedHashMap<Int, ByteArray>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>?): Boolean = size > cacheSize
    }
    override val name: String = file.name

    init {
        require(raf.read() == 0x1f && raf.read() == 0x8b) { "not a gzip/dictzip file" }
        require(raf.read() == 8) { "not deflate" }
        val flg = raf.read()
        raf.readFully(ByteArray(6)) // mtime(4) + xfl + os

        var chunkLen = 0
        var chunkCount = 0
        var chunkSizes = IntArray(0)

        if (flg and 0x04 != 0) { // FEXTRA
            val xlen = readLe16()
            val extra = ByteArray(xlen)
            raf.readFully(extra)
            var p = 0
            while (p + 4 <= extra.size) {
                val si1 = extra[p].toInt() and 0xff
                val si2 = extra[p + 1].toInt() and 0xff
                val subLen = ((extra[p + 2].toInt() and 0xff) or ((extra[p + 3].toInt() and 0xff) shl 8))
                if (si1 == 'R'.code && si2 == 'A'.code) {
                    require(p + 10 <= extra.size) { "malformed dictzip extra field" }
                    val version = ((extra[p + 4].toInt() and 0xff) or ((extra[p + 5].toInt() and 0xff) shl 8))
                    require(version == 1) { "unsupported dictzip version $version" }
                    chunkLen = (extra[p + 6].toInt() and 0xff) or ((extra[p + 7].toInt() and 0xff) shl 8)
                    chunkCount = (extra[p + 8].toInt() and 0xff) or ((extra[p + 9].toInt() and 0xff) shl 8)
                    require(p + 10 + chunkCount * 2 <= extra.size) { "truncated dictzip chunk table" }
                    chunkSizes = IntArray(chunkCount)
                    for (i in 0 until chunkCount) {
                        chunkSizes[i] = (extra[p + 10 + i * 2].toInt() and 0xff) or
                            ((extra[p + 11 + i * 2].toInt() and 0xff) shl 8)
                    }
                }
                p += 4 + subLen
            }
        }
        require(chunkCount > 0 && chunkLen > 0) { "not a dictzip file (missing RA chunk table)" }

        if (flg and 0x08 != 0) { // FNAME
            var b = raf.read()
            while (b != 0 && b != -1) b = raf.read()
        }
        if (flg and 0x10 != 0) { // FCOMMENT
            var b = raf.read()
            while (b != 0 && b != -1) b = raf.read()
        }
        if (flg and 0x02 != 0) raf.readFully(ByteArray(2)) // FHCRC

        chunkLength = chunkLen
        chunks = chunkSizes
        chunkOffsets = LongArray(chunkCount)
        var off = raf.filePointer
        for (i in 0 until chunkCount) {
            chunkOffsets[i] = off
            off += chunks[i]
        }
    }

    private fun readLe16(): Int {
        val lo = raf.read()
        val hi = raf.read()
        return (lo and 0xff) or ((hi and 0xff) shl 8)
    }

    override fun read(offset: Long, size: Int): ByteArray {
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        var outPos = 0
        val first = (offset / chunkLength).toInt()
        val last = ((offset + size - 1) / chunkLength).toInt()
        for (c in first..last) {
            val block = chunk(c)
            val srcStart = if (c == first) (offset % chunkLength).toInt() else 0
            val srcEnd = if (c == last) ((offset + size) - c.toLong() * chunkLength).toInt() else block.size
            val len = srcEnd - srcStart
            if (len > 0) block.copyInto(out, outPos, srcStart, srcEnd)
            outPos += len
        }
        return out
    }

    private fun chunk(i: Int): ByteArray {
        cache[i]?.let { return it }
        val compressed = ByteArray(chunks[i])
        raf.seek(chunkOffsets[i])
        raf.readFully(compressed)
        val inflater = Inflater(true) // raw deflate
        try {
            val buffer = ByteArray(chunkLength)
            inflater.setInput(compressed)
            var n = 0
            while (!inflater.finished() && n < buffer.size) {
                n += inflater.inflate(buffer, n, buffer.size - n)
            }
            val result = if (n == buffer.size) buffer else buffer.copyOf(n)
            cache[i] = result
            return result
        } finally {
            inflater.end()
        }
    }

    override fun close() {
        cache.clear()
        raf.close()
    }
}

