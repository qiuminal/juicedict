package com.qiuminal.juicedict.engine.mdict

/** Which kind of MDict container a file is. */
internal enum class MdxKind {
    /** `.mdx` — text entries, keys in the header's declared encoding. */
    ARTICLE,

    /** `.mdd` — binary resources, keys always UTF-16LE. */
    RESOURCE,
    ;

    /** Root element name writemdict emits for each kind. */
    val rootTag: String
        get() = if (this == ARTICLE) "Dictionary" else "Library_Data"
}

/**
 * A parsed MDict file header plus the offsets of the structures that follow it.
 *
 * On-disk layout (verified byte-for-byte against six real dictionaries, including a
 * 53 MB / 137390-entry one):
 *
 * ```
 * u32be header_len              // length of header_str in BYTES
 * header_str                    // UTF-16LE XML, ends with "\r\n\0"
 * u32le adler32(header_str)     // note: little-endian, unlike header_len
 * ```
 *
 * The Adler-32 covers exactly `[4, 4 + header_len)`, i.e. the header string including
 * its trailing NUL but excluding the 4-byte little-endian checksum that follows.
 */
internal class MdxHeader(
    val kind: MdxKind,
    val attributes: Map<String, String>,
    /** Byte offset just past the header checksum, where the key section begins. */
    val keySectionOffset: Long,
    /** Length of the key section, when the file uses the 2.0 layout with a 40-byte preamble. */
    val version: Float,
) {
    val encoding: String
        get() = when (val e = attributes["encoding"]?.trim()) {
            null, "" -> "UTF-8"
            else -> e
        }

    val encrypted: Int
        get() = when (val v = attributes["encrypted"]?.trim()?.lowercase()) {
            null, "", "no", "0" -> 0
            // The attribute is either a flag string or a numeric bitmask; both appear
            // in the wild (bit 1 = key section, bit 2 = key index).
            else -> v.toIntOrNull() ?: 1
        }

    val isEncrypted: Boolean get() = encrypted != 0

    val title: String
        get() = attributes["title"]?.trim().orEmpty()

    val description: String
        get() = attributes["description"]?.trim().orEmpty()

    /** `UTF-16` keys under MDict are little-endian; used to size key fields. */
    val isUtf16: Boolean
        get() = encoding.equals("UTF-16", ignoreCase = true) ||
            encoding.startsWith("UTF-16", ignoreCase = true)

    /**
     * Whether the writer sorted keys with punctuation stripped.
     *
     * This decides how the key index must be searched, not just how it was built.
     * Every released MDD declares `StripKey="No"`, so `\0.png` precedes `\00.png`
     * on the dot; MDX files that declare `Stripkey="Yes"` sort `2.5D机织物` after
     * `21世纪议程` because the dot is dropped and `1` < `5` decides. The attribute
     * name itself is spelled `StripKey` in some files and `Stripkey` in others, so
     * the lookup has to be case-insensitive.
     */
    val stripKey: Boolean
        get() = flag("stripkey", default = this.kind == MdxKind.ARTICLE)

    /** Whether the writer sorted keys case-sensitively. Almost always false. */
    val keyCaseSensitive: Boolean
        get() = flag("keycasesensitive", default = false)

    /**
     * `num_entries` as declared in the header, or 0 when absent.
     *
     * Only a sanity hint: in several shipped files the attribute is stale (辞海第七版
     * declares none at all while its index holds 137390 entries), so the key index is
     * authoritative and this is never used to size anything.
     */
    val entryCount: Long
        get() = attributes["num_entries"]?.trim()?.toLongOrNull()
            ?: attributes["numentries"]?.trim()?.toLongOrNull()
            ?: 0L

    private fun flag(name: String, default: Boolean): Boolean {
        for ((k, v) in attributes) {
            if (!k.equals(name, ignoreCase = true)) continue
            return when (v.trim().lowercase()) {
                "", "yes", "true", "1" -> true
                "no", "false", "0" -> false
                else -> default
            }
        }
        return default
    }

    companion object {
        private const val UTF16LE_BOM = 0xFFFE
        private const val MAX_HEADER_BYTES = 1 shl 20

        /**
         * Parses the header at the start of [head], which must contain at least the
         * first `4 + header_len + 4` bytes of the file.
         *
         * Returns null when the magic/root tag does not look like an MDict file.
         */
        fun parse(head: ByteArray): MdxHeader? {
            if (head.size < 8) return null
            val headerLen = MdxBytes.u32be(head, 0)
            if (headerLen <= 0 || headerLen > MAX_HEADER_BYTES) return null
            val textStart = 4
            val textEnd = textStart + headerLen
            if (textEnd + 4 > head.size) return null

            // The header is UTF-16LE; decode then drop the trailing "\r\n\0".
            val sb = StringBuilder(headerLen / 2)
            var i = textStart
            while (i + 1 < textEnd) {
                val c = (head[i].toInt() and 0xff) or ((head[i + 1].toInt() and 0xff) shl 8)
                if (c == 0) break
                sb.append(c.toChar())
                i += 2
            }
            val text = sb.toString()
            if (!text.startsWith("<")) return null

            // Verify the little-endian Adler-32 over the raw header bytes.
            val declared = MdxBytes.u32le(head, textEnd)
            if (declared != MdxBlockReader.adler32(head, textStart, headerLen)) return null

            val rootTag = text.substringAfter('<').substringBefore('>').substringBefore(' ')
                .substringBefore('/').trim()
            val kind = when (rootTag) {
                "Dictionary" -> MdxKind.ARTICLE
                "Library_Data" -> MdxKind.RESOURCE
                else -> return null
            }

            val attrs = parseAttributes(text)

            // v2.0 files carry a 40-byte key-section preamble; v1.2 does not.
            // writemdict only writes 2.0, and every real file checked is 2.0.
            val version = attrs["generatedbyengineversion"]?.toFloatOrNull()
                ?: attrs["format"]?.toFloatOrNull()
                ?: if (attrs.containsKey("encoding")) 2.0f else 1.2f

            return MdxHeader(
                kind = kind,
                attributes = attrs,
                keySectionOffset = textEnd + 4L,
                version = version,
            )
        }

        /**
         * Extracts the root element's attributes. The header is a single self-closing
         * tag, so a full XML parser would be overkill (and Android's is unavailable in
         * plain JVM unit tests).
         */
        private fun parseAttributes(text: String): Map<String, String> {
            val out = HashMap<String, String>()
            val open = text.indexOf('<')
            val close = text.indexOf(">", open + 1)
            if (open < 0 || close < 0) return out
            val tag = text.substring(open + 1, close)
            var i = tag.indexOf(' ')
            if (i < 0) return out
            i++
            while (i < tag.length) {
                val before = i
                while (i < tag.length && tag[i].isWhitespace()) i++
                val nameStart = i
                while (i < tag.length && tag[i] != '=' && !tag[i].isWhitespace() && tag[i] != '/') i++
                if (i >= tag.length) break
                val name = tag.substring(nameStart, i).lowercase()
                while (i < tag.length && tag[i].isWhitespace()) i++
                if (i >= tag.length || tag[i] != '=') {
                    // A bare attribute, or the '/' of a self-closing tag. Neither
                    // consumes a character on its own, so advance explicitly or the
                    // outer loop spins forever on `<Dictionary ... />`.
                    if (name.isNotEmpty()) out[name] = ""
                    if (i == before) i++ else if (i > before && name.isEmpty()) i++
                    continue
                }
                i++
                while (i < tag.length && tag[i].isWhitespace()) i++
                if (i >= tag.length) break
                val quote = tag[i]
                if (quote == '"' || quote == '\'') {
                    i++
                    val valStart = i
                    while (i < tag.length && tag[i] != quote) i++
                    out[name] = tag.substring(valStart, i)
                    i++
                } else {
                    val valStart = i
                    while (i < tag.length && !tag[i].isWhitespace()) i++
                    out[name] = tag.substring(valStart, i)
                }
            }
            return out
        }
    }
}
