package com.qiuminal.juicedict.engine

import java.io.File
import java.io.RandomAccessFile
import java.util.LinkedHashMap

/** Random-access reader for the `.dict` payload file. */
interface DictDataReader : AutoCloseable {
    fun read(offset: Long, size: Int): ByteArray
    val name: String
}

/** Reads a plain (uncompressed) `.dict` file with a small LRU cache. */
class PlainDictReader(file: File, cacheSize: Int = 32) : DictDataReader {
    private val raf = RandomAccessFile(file, "r")
    private val cache = object : LinkedHashMap<Long, ByteArray>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = size > cacheSize
    }
    override val name: String = file.name

    override fun read(offset: Long, size: Int): ByteArray {
        cache[offset]?.let { return it }
        val buf = ByteArray(size)
        raf.seek(offset)
        raf.readFully(buf)
        cache[offset] = buf
        return buf
    }

    override fun close() {
        cache.clear()
        raf.close()
    }
}
