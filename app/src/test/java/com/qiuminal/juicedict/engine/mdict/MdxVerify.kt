package com.qiuminal.juicedict.engine.mdict

import java.io.File

/**
 * End-to-end verification of the MDict reader against real dictionaries.
 *
 * A plain `main` rather than a JUnit test so it can read the corpus straight off disk
 * via the `verifyMdict` Gradle task:
 *
 *   ./gradlew verifyMdict -Pmdict.dir="E:/dictionary/MDict"
 *
 * It walks every key block, decodes every key, checks the ordering invariant and
 * resolves a sample of records, which is what the app will do at runtime.
 *
 * Output is flushed per file so a stall is visible rather than silent.
 */
object MdxVerify {

    @JvmStatic
    fun main(args: Array<String>) {
        val dirs = args.flatMap { it.split(File.pathSeparator, ",") }
            .filter { it.isNotBlank() }
            .map { File(it) }
        check(dirs.isNotEmpty()) { "usage: MdxVerify <dir> [dir...]" }

        val files = dirs.flatMap { root ->
            root.walkTopDown()
                .filter {
                    it.isFile && (it.extension.equals("mdx", true) || it.extension.equals("mdd", true))
                }
                .toList()
        }.sortedBy { it.length() }

        println("found ${files.size} MDict file(s)")
        flush()

        var pass = 0
        val failures = ArrayList<String>()
        for (f in files) {
            println("--- ${f.name} (${f.length()} bytes)")
            flush()
            try {
                val stats = verify(f)
                pass++
                println(
                    "OK  ${f.name}: ${stats.keys} keys, ${stats.blocks} key blocks, " +
                        "${stats.blocks} rec blocks, enc=${stats.encoding}",
                )
            } catch (e: Throwable) {
                val msg = "${f.name}: ${e::class.java.simpleName}: ${e.message}"
                failures.add(msg)
                println("FAIL $msg")
                e.stackTrace.take(6).forEach { println("       at $it") }
            }
            flush()
        }

        println()
        println("=== $pass passed, ${failures.size} failed ===")
        failures.forEach { println("  - $it") }
        flush()
        if (failures.isNotEmpty()) System.exit(1)
    }

    private data class Stats(
        val keys: Long,
        val blocks: Int,
        val encoding: String,
        val recordBytes: Long,
        val entryCount: Long,
    )

    private fun verify(file: File): Stats {
        val mdx = file.extension.equals("mdx", true)
        val mdxKind = if (mdx) MdxKind.ARTICLE else MdxKind.RESOURCE
        MdxFileReader(file).use { r ->
            val header = MdxHeader.parse(r.read(0, minOf(r.length, 1L shl 20).toInt()))
                ?: error("could not parse header")
            check(header.kind == mdxKind) { "kind ${header.kind} != expected $mdxKind" }
            check(!header.isEncrypted) { "file is encrypted" }
            println("    header: root=${header.kind} enc=${header.encoding} ver=${header.version}")

            val unit = if (mdxKind == MdxKind.RESOURCE || header.isUtf16) 2 else 1
            val stripKeys = header.stripKey
            println("    stripKey=$stripKeys caseSensitive=${header.keyCaseSensitive}")
            val declared = header.attributes["wordcount"]?.toLongOrNull() ?: 0L
            val keyIndex = MdxKeyIndex.parse(
                reader = r,
                offset = header.keySectionOffset,
                unitSize = unit,
                declaredEntries = declared,
                version = header.version,
                stripKey = header.stripKey,
            )
            println(
                "    key section: ${keyIndex.blockCount} blocks, " +
                    "index says ${keyIndex.entryCount} entries, header says $declared",
            )

            val records = MdxRecordStore(
                r,
                header.keySectionOffset + keyIndexTotalSize(r, header.keySectionOffset),
            )
            println(
                "    record section: ${records.entryCount} entries, " +
                    "${records.totalSize} decompressed bytes",
            )

            // Stream every key block: decode keys, check ordering, and resolve the first
            // record of each block through the record store (exercises block translation).
            var total = 0L
            var outOfOrder = 0L
            var resolved = 0L
            var prev: String? = null
            val blockRecords = ArrayList<Pair<String, Long>>()

            for (b in 0 until keyIndex.blockCount) {
                val raw = r.read(keyIndex.blockOffsetAt(b), keyIndex.blockCompSizeAt(b).toInt())
                val bytes = MdxBlockReader.read(raw, keyIndex.blockDecompSizeAt(b))
                var p = 0
                var n = 0L
                val want = keyIndex.entryCountAt(b)
                blockRecords.clear()
                while (p < bytes.size && n < want) {
                    val recOffset = MdxBytes.u64be(bytes, p)
                    p += 8
                    val start = p
                    // The terminator is a code unit, not a byte: UTF-16LE keys contain
                    // 0x00 as the high byte of every ASCII character, so scanning byte
                    // by byte would stop inside the first character and desynchronize
                    // the whole block.
                    while (p + unit <= bytes.size && !isTerminator(bytes, p, unit)) p += unit
                    val key = decodeKey(bytes, start, p, unit)
                    p += unit
                    n++
                    total++
                    val last = prev
                    // MDict orders keys case-insensitively unless the header sets
                    // KeyCaseSensitive="Yes", so compare folded. Raw byte order
                    // reports false violations for pairs like [\hei_xhzd.woff] and
                    // [\XHZD_12.css], where the writer sorted on lowercase text.
                    if (last != null && compareKeys(key, last, stripKeys) < 0) {
                        outOfOrder++
                        if (outOfOrder <= 5) {
                            println("    OUT-OF-ORDER: [${last}] then [$key] in block $b")
                        }
                    }
                    prev = key
                    if (recOffset > records.totalSize) {
                        error("record offset $recOffset exceeds record section ${records.totalSize}")
                    }
                    if (blockRecords.size < 3) blockRecords.add(key to recOffset)
                }
                if (n != want) {
                    error("key block $b yielded $n keys, index declared $want")
                }

                // Resolve a couple of records per block to assert the virtual offset
                // translation actually lands on data.
                for ((key, off) in blockRecords) {
                    val probe = records.read(off, 512)
                    if (probe.isEmpty()) error("record for '$key' at $off read empty")
                    resolved++
                }
                if (b % 50 == 0 || b == keyIndex.blockCount - 1) {
                    println("    block $b/${keyIndex.blockCount}: $total keys so far")
                    flush()
                }
            }

            check(outOfOrder == 0L) { "$outOfOrder keys out of order" }
            println("    verified $total keys, resolved $resolved sample records")

            return Stats(
                keys = total,
                blocks = keyIndex.blockCount,
                encoding = header.encoding,
                recordBytes = records.totalSize,
                entryCount = records.entryCount,
            )
        }
    }

    /** Total byte size of the key section: preamble + index + all key blocks. */
    private fun keyIndexTotalSize(r: MdxFileReader, at: Long): Long {
        val head = r.read(at, 44)
        val indexComp = MdxBytes.u64be(head, 24)
        val blocksTotal = MdxBytes.u64be(head, 32)
        return 44 + indexComp + blocksTotal
    }

    /**
     * Compares two keys the way MDict ordered them: fold case, drop stripped
     * punctuation, then compare as UTF-8 bytes.
     *
     * The final detail matters. Kotlin's `String.compareTo` compares UTF-16 code
     * units, so a surrogate pair (U+20164 = D840 DC64) sorts *before* U+F97F,
     * while the file has the opposite order. MDict compares the encoded bytes, so
     * U+F97F (EF A9 BF) does precede U+20164 (F0 A0 85 A4). Getting this wrong
     * silently breaks binary search in the real index.
     */
    private fun compareKeys(a: String, b: String, strip: Boolean): Int {
        val x = foldKey(a, strip).toByteArray(Charsets.UTF_8)
        val y = foldKey(b, strip).toByteArray(Charsets.UTF_8)
        val n = minOf(x.size, y.size)
        for (i in 0 until n) {
            val d = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return x.size - y.size
    }

    /**
     * The key MDict actually sorted on. `KeyCaseSensitive` and `Stripkey` come
     * from the file's own header and the writer applies them before ordering, so
     * a raw comparison reports false violations. Concretely: every released MDD
     * declares `StripKey="No"`, so `[\0.png]` precedes `[\00.png]` on the dot;
     * both MDX files here declare `Stripkey="Yes"`, so `[2.5D机织物]` follows
     * `[21世纪议程]` because the dot is dropped and `1` < `5` decides.
     */
    private fun foldKey(s: String, strip: Boolean): String {
        val lowered = s.lowercase()
        return if (strip) lowered.filterNot { it.isWhitespace() || isStripChar(it) } else lowered
    }

    /** Punctuation MDict strips when `Stripkey` is on. */
    private fun isStripChar(c: Char): Boolean =
        c.code < 0x80 && !c.isLetterOrDigit()

    private fun isTerminator(bytes: ByteArray, at: Int, unit: Int): Boolean {
        for (i in 0 until unit) if (bytes[at + i].toInt() != 0) return false
        return true
    }

    private fun decodeKey(bytes: ByteArray, from: Int, to: Int, unit: Int): String {
        val len = to - from
        if (len <= 0) return ""
        return if (unit == 2) {
            val chars = CharArray(len / 2)
            for (i in chars.indices) {
                chars[i] = ((bytes[from + i * 2].toInt() and 0xff) or
                    ((bytes[from + i * 2 + 1].toInt() and 0xff) shl 8)).toChar()
            }
            String(chars)
        } else {
            String(bytes, from, len, Charsets.UTF_8)
        }
    }

    private fun flush() = System.out.flush()
}
