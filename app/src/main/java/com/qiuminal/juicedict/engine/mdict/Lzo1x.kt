package com.qiuminal.juicedict.engine.mdict

/**
 * Pure-Kotlin LZO1X decompressor.
 *
 * MDict stores a bare LZO1X instruction stream: writemdict writes
 * `lzo.compress(data)[5:]`, so python-lzo's 5-byte frame header is stripped and only the
 * raw opcode stream remains. The reverse-engineered MDict 2.0 spec allows
 * `compression_type = 1` (LZO) for any block.
 *
 * No dictionary in the local corpus uses LZO — all 15 test files are raw or zlib — so
 * this path is not exercised by the shipped test data at all and is instead pinned by
 * `Lzo1xTest` over hand-built streams. It is written from scratch rather than pulled
 * from a library because the project forbids new Gradle dependencies and NDK/JNI code.
 *
 * ## Instruction set
 *
 * LZO1X is a byte-oriented LZ77 variant. Every instruction is either a literal run or a
 * back-reference ("match"), and the encoder packs distance and length into as few bytes
 * as it can. Dispatched by the current byte `t`:
 *
 * | context | byte | form | match length | distance |
 * |---|---|---|---|---|
 * | match | `t >= 64` | M2 | `(t >> 5) + 1` | `1 + ((t >> 2) & 7) + (next << 3)` |
 * | match | `32 <= t < 64` | M3 | `(t & 31) + 2`, extended when `t & 31 == 0` | `1 + (le16 >> 2)` |
 * | match | `16 <= t < 32` | M4 | `(t & 7) + 2`, extended when `t & 7 == 0` | `((t & 8) << 11) + (le16 >> 2) + 0x4000` |
 * | match | `t < 16` | M1 | 2 | `1 + (t >> 2) + (next << 2)` |
 * | after a literal run | `t < 16` | short | 3 | `0x801 + (t >> 2) + (next << 2)` |
 * | instruction boundary | `t < 16` | literal run | `t + 3`, extended when `t == 0` | — |
 *
 * Three things about this table are load-bearing and easy to get wrong:
 *
 * 1. **A byte below 16 means three different things** depending on where the decoder
 *    stands: a literal run at an instruction boundary, a 3-byte match immediately after
 *    a literal run, and M1 in match context. The same byte value is reinterpreted, not
 *    re-encoded.
 *
 * 2. **Trailing literals are not an instruction.** Every match is followed by 0..3
 *    literals whose count is hidden in the low 2 bits of a byte the match already
 *    emitted — the instruction byte for M1/M2 and the short form, but the *first
 *    distance byte* for M3/M4, because those spend all 8 instruction bits on
 *    length and distance.
 *
 * 3. **Distances are relative to the output cursor**, and M4's is biased by `0x4000`
 *    (`0x4001..0xBFFF`) while M2 reaches only `0x800` and M1 `0x400`. M4's biased
 *    distance of zero is the end-of-stream marker (`11 00 00`), which is why the
 *    accumulator is tested *before* the `0x4000` bias is applied.
 */
internal object Lzo1x {

    /** Largest offset any opcode can express: M4's `0x4000 + 0x7FFF`. */
    private const val MAX_DISTANCE = 0xBFFF

    /** Base offset of the short 3-byte match that always follows a literal run. */
    private const val M2_MAX_OFFSET = 0x0800

    /** M4 stores the distance this much smaller than it is, to fit larger offsets. */
    private const val M4_BIAS = 0x4000

    private const val MAX_OUTPUT = Int.MAX_VALUE.toLong()

    /** Ceiling for the up-front allocation; a lying declared size must not OOM us. */
    private const val MAX_INITIAL_OUTPUT = 1 shl 24

    /**
     * Decompresses `src[off, off+len)`.
     *
     * @param expectedSize the block's declared uncompressed size, used to size the output
     *   buffer up front. Pass 0 when unknown; the buffer still grows as needed.
     */
    fun decompress(src: ByteArray, off: Int, len: Int, expectedSize: Long): ByteArray {
        val end = off + len
        var ip = off
        var dp = 0
        var out = ByteArray(
            when {
                expectedSize in 1..MAX_OUTPUT ->
                    expectedSize.coerceAtMost(MAX_INITIAL_OUTPUT.toLong()).toInt()

                else -> (len * 5).coerceIn(1024, MAX_INITIAL_OUTPUT)
            },
        )

        fun grow(need: Int) {
            val required = dp.toLong() + need
            if (required <= out.size) return
            require(required <= MAX_OUTPUT) { "LZO output exceeds $MAX_OUTPUT bytes" }
            var cap = out.size.toLong()
            while (cap < required) cap = minOf(cap shl 1, MAX_OUTPUT)
            out = out.copyOf(cap.toInt())
        }

        fun copyLiterals(from: Int, n: Int) {
            require(n >= 0 && from >= 0 && from + n <= end) { "LZO truncated literal run" }
            grow(n)
            System.arraycopy(src, from, out, dp, n)
            dp += n
        }

        /**
         * Copies a back-reference of [n] bytes starting [dist] bytes before the output
         * cursor. Runs are encoded as self-overlapping matches (a run of 50 identical
         * bytes is `dist = 1, n = 49`), so the overlapping case has to be copied one
         * byte at a time to see its own output.
         */
        fun copyMatch(dist: Int, n: Int) {
            require(dist >= 1 && dist <= dp) { "LZO match distance $dist exceeds output ($dp)" }
            require(dist <= MAX_DISTANCE) { "LZO match distance $dist out of range" }
            grow(n)
            var from = dp - dist
            if (from + n <= dp) {
                System.arraycopy(out, from, out, dp, n)
                dp += n
            } else {
                var remaining = n
                while (remaining > 0) {
                    out[dp++] = out[from++]
                    remaining--
                }
            }
        }

        fun nextByte(): Int {
            require(ip < end) { "LZO truncated input" }
            return src[ip++].toInt() and 0xff
        }

        /** Reads a length: [base], plus 255 per zero byte, plus one final addend byte. */
        fun readLength(base: Int): Int {
            var t = base
            while (ip < end && src[ip].toInt() == 0) {
                t += 255
                ip++
            }
            require(ip < end) { "LZO truncated length" }
            return t + nextByte()
        }

        var t = 0

        /**
         * The reference's inner loop: `t` holds a match instruction. Consumes the match and
         * any trailing literals, which may leave `t` holding the next match instruction.
         *
         * Returns true when the trailing count was zero, meaning the caller must read the
         * next instruction byte at an instruction boundary; false on the end-of-stream
         * marker.
         */
        fun matchLoop(): Boolean {
            while (true) {
                // Which byte carries this match's trailing-literal count.
                val trailingSource: Int
                if (t >= 64) {
                    // M2
                    val hi = nextByte()
                    copyMatch(1 + ((t shr 2) and 0x07) + (hi shl 3), (t shr 5) + 1)
                    trailingSource = t
                } else if (t >= 32) {
                    // M3
                    var n = t and 0x1f
                    if (n == 0) n = readLength(31)
                    n += 2
                    val lo = nextByte()
                    val hi = nextByte()
                    copyMatch(1 + ((lo or (hi shl 8)) shr 2), n)
                    trailingSource = lo
                } else if (t >= 16) {
                    // M4
                    var n = t and 0x07
                    if (n == 0) n = readLength(7)
                    n += 2
                    val lo = nextByte()
                    val hi = nextByte()
                    val biased = ((t and 0x08) shl 11) + ((lo or (hi shl 8)) shr 2)
                    if (biased == 0) return false
                    copyMatch(biased + M4_BIAS, n)
                    trailingSource = lo
                } else {
                    // M1
                    val hi = nextByte()
                    copyMatch(1 + (t shr 2) + (hi shl 2), 2)
                    trailingSource = t
                }

                val trailing = trailingSource and 0x03
                if (trailing == 0) return true
                copyLiterals(ip, trailing)
                ip += trailing
                t = nextByte()
            }
        }

        /**
         * The reference's `first_literal_run` label: `t` is the byte just past a literal
         * run. Returns true when the caller must read the next instruction byte.
         */
        fun afterLiteralRun(): Boolean {
            if (t < 16) {
                val lo = nextByte()
                copyMatch(1 + M2_MAX_OFFSET + (t shr 2) + (lo shl 2), 3)
                val trailing = t and 0x03
                if (trailing == 0) return true
                copyLiterals(ip, trailing)
                ip += trailing
                t = nextByte()
            }
            return matchLoop()
        }

        /**
         * One pass of the reference's outer loop for the already-read byte `t`: a literal
         * run when `t < 16`, otherwise straight to the match handler.
         */
        fun outerStep(): Boolean {
            if (t >= 16) return matchLoop()
            var n = if (t == 0) readLength(15) else t
            n += 3
            copyLiterals(ip, n)
            ip += n
            t = nextByte()
            return afterLiteralRun()
        }

        if (ip >= end) return out.copyOf(0)

        // The stream opens with `17 + leading literal count`, so the first byte is only
        // ever a literal-run header, and never an opcode below 16. A run of 1..3 uses the
        // `match_next` path instead of `first_literal_run`, which is why the following
        // byte is read as a plain match instruction there (t < 16 means M1, not the short
        // 3-byte form).
        t = nextByte()
        if (t > 17) {
            val n = t - 17
            copyLiterals(ip, n)
            ip += n
            t = nextByte()
            val ok = if (n >= 4) afterLiteralRun() else matchLoop()
            if (!ok) return out.copyOf(dp)
        } else {
            if (!outerStep()) return out.copyOf(dp)
        }

        while (true) {
            t = nextByte()
            if (!outerStep()) return out.copyOf(dp)
        }
    }
}
