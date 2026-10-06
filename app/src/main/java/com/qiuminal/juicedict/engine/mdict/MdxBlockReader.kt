package com.qiuminal.juicedict.engine.mdict

import java.io.ByteArrayOutputStream
import java.util.zip.Adler32
import java.util.zip.Inflater

/** Compression schemes MDict stores per block. */
internal enum class MdxCompression(val id: Int) {
    /** Block body is stored verbatim. */
    NONE(0),

    /** Raw LZO1X stream (python-lzo output with its 5-byte frame header stripped). */
    LZO(1),

    /** zlib-wrapped deflate. */
    ZLIB(2),
    ;

    companion object {
        /**
         * Reads the block's compression type.
         *
         * This field is **little-endian** (`struct.pack("<L", ...)` in writemdict),
         * unlike every other integer in the container. Reading it big-endian makes zlib
         * look like 0x02000000, which is the classic MDict parsing bug.
         */
        fun of(block: ByteArray): MdxCompression? {
            val v = MdxBytes.u32le(block, 0)
            return entries.firstOrNull { it.id == v }
        }
    }
}

/**
 * Expands one MDict block.
 *
 * Every block (key index, key block, record block) is laid out as:
 *
 * ```
 * u32le compression_type | u32be adler32(uncompressed) | payload
 * ```
 *
 * For zlib the payload keeps the zlib stream's own trailing Adler-32, so the
 * compressed payload is 4 bytes longer than the raw deflate data and
 * `Inflater` stops on its own at the deflate end.
 */
internal object MdxBlockReader {

    /** Size of the `compression_type` + `adler32` prefix every block carries. */
    const val HEADER_SIZE = 8

    /**
     * Expands [block], which must include the full 8-byte prefix, and returns the
     * uncompressed bytes.
     *
     * @param decompSize the size declared by the block's index entry; used to size
     *   buffers and to detect a truncated or corrupt file. Pass 0 when unknown.
     */
    fun read(block: ByteArray, decompSize: Long): ByteArray {
        require(block.size >= HEADER_SIZE) { "truncated MDict block (${block.size} bytes)" }
        val type = MdxCompression.of(block)
            ?: throw IllegalArgumentException(
                "unknown MDict compression type ${MdxBytes.u32le(block, 0)}",
            )
        val payloadOffset = HEADER_SIZE
        val payloadLen = block.size - HEADER_SIZE
        return when (type) {
            MdxCompression.NONE -> {
                val raw = block.copyOfRange(payloadOffset, block.size)
                verifyAdler(block, raw)
                raw
            }

            MdxCompression.ZLIB -> {
                val out = inflate(block, payloadOffset, payloadLen, decompSize)
                verifyAdler(block, out)
                out
            }

            MdxCompression.LZO -> {
                val out = Lzo1x.decompress(block, payloadOffset, payloadLen, decompSize)
                verifyAdler(block, out)
                out
            }
        }
    }

    /**
     * The container stores an Adler-32 next to several structures (the file header, the
     * key-section preamble and every block). Checking it turns "silently renders garbage"
     * into a clean load failure, so the engine can report a bad dictionary instead of
     * showing mojibake.
     */
    fun adler32(data: ByteArray, from: Int = 0, len: Int = data.size): Int {
        val ad = Adler32()
        ad.update(data, from, len)
        return ad.value.toInt()
    }

    private fun verifyAdler(block: ByteArray, uncompressed: ByteArray) {
        val declared = MdxBytes.u32be(block, 4)
        val actual = adler32(uncompressed)
        require(declared == actual) {
            "MDict block checksum mismatch (declared 0x${declared.toUInt().toString(16)}, " +
                "computed 0x${actual.toUInt().toString(16)})"
        }
    }

    private fun inflate(block: ByteArray, off: Int, len: Int, expectedSize: Long): ByteArray {
        val inflater = Inflater()
        inflater.setInput(block, off, len)
        val out = ByteArrayOutputStream(
            if (expectedSize in 1..(1L shl 26)) expectedSize.toInt() else 1 shl 16,
        )
        val buf = ByteArray(1 shl 16)
        try {
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: java.util.zip.DataFormatException) {
                    throw IllegalArgumentException("corrupt zlib block in MDict file", e)
                }
                if (n > 0) {
                    out.write(buf, 0, n)
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    // Without more input the declared size cannot be reached: the block
                    // is truncated.
                    break
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
