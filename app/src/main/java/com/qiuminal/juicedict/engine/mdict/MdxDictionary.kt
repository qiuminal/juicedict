package com.qiuminal.juicedict.engine.mdict

import com.qiuminal.juicedict.engine.Article
import com.qiuminal.juicedict.engine.ArticleSection
import com.qiuminal.juicedict.engine.DictHit
import com.qiuminal.juicedict.engine.DictionaryEngine
import java.io.File

/**
 * Facade over one MDict dictionary (`.mdx` articles plus an optional `.mdd` resource
 * pack), shaped to match `StarDict` so the rest of the app cannot tell them apart.
 *
 * The design turns on one fact: MDict's record section, once every block is
 * decompressed and concatenated, *is* a flat byte array addressed by `(offset, size)`
 * — exactly StarDict's model. [MdxRecordStore] exposes that array, so article
 * parsing and rendering reuse the existing pipeline instead of growing an MDict
 * branch.
 *
 * What is genuinely MDict-specific, and handled here:
 *
 * - **`@@@LINK` redirects.** A record may be nothing but `@@@LINK=<target>`, meaning
 *   "this key's body is that key's body". 《字源》 and 《王力字典 上古擬音》 are built
 *   almost entirely out of these, so following them is not optional: without it,
 *   most headwords render as a bare link line instead of an article.
 * - **`entry://` links.** In-text cross references are rewritten to the app's own
 *   `juice://lookup/<urlencoded>` scheme, which `SelectableLinkTextView` already
 *   intercepts, so cross-references work with no UI change.
 * - **Encoding.** Bodies are decoded with the header's declared charset. UTF-8 and
 *   UTF-16LE are handled directly; GBK/Big5 resolve by name, and an unavailable
 *   charset degrades to Latin-1 rather than throwing mid-render.
 */
internal class MdxDictionary(
    override val id: String,
    private val mdxFile: File,
    mddFiles: List<File>,
    private val title: String,
    private val description: String,
) : DictionaryEngine {

    private val reader = MdxFileReader(mdxFile)
    private val header: MdxHeader
    private val keyIndex: MdxKeyIndex
    private val words: MdxWordIndex
    private val records: MdxRecordStore

    /**
     * Resource packs, searched in order. A dictionary may ship several:
     * 《字源》 splits its images across `.mdd` and `.1.mdd`, and 《新华字典12》 keeps
     * audio in the second one.
     */
    private val resources: List<MdxResourcePack>

    override val wordCount: Int get() = words.size

    val bookTitle: String
        get() = title.ifEmpty { header.title.ifEmpty { mdxFile.nameWithoutExtension } }

    val bookDescription: String get() = description

    init {
        val head = MdxHeader.parse(reader.read(0L, minOf(reader.length, MAX_HEADER_BYTES).toInt()))
            ?: error("not an MDict file: ${mdxFile.name}")
        require(head.kind == MdxKind.ARTICLE) {
            "${mdxFile.name} is a resource pack, not a dictionary"
        }
        require(!head.isEncrypted) { "encrypted MDict files are not supported" }
        require(head.keySectionOffset < reader.length) { "truncated MDict file ${mdxFile.name}" }
        header = head

        keyIndex = MdxKeyIndex.parse(
            reader = reader,
            offset = head.keySectionOffset,
            unitSize = if (head.isUtf16) 2 else 1,
            declaredEntries = head.entryCount,
            version = head.version,
            stripKey = head.stripKey,
        )
        words = MdxWordIndex.load(reader, keyIndex, head.stripKey)
        records = MdxRecordStore(reader, keyIndex.recordSectionOffset(head.keySectionOffset))
        keyIndex.setRecordsEnd(records.totalSize)

        resources = mddFiles.filter { it.isFile }.mapNotNull { file ->
            runCatching { MdxResourcePack(file) }
                .onFailure { MdxLog.warn(TAG, "could not open ${file.name}", it) }
                .getOrNull()
        }
    }

    // ---- search -------------------------------------------------------------

    /**
     * Prefix-first search: every key starting with the query, which naturally
     * includes the exact match (and puts the shortest key first).
     */
    fun lookupPrefix(query: String, limit: Int = 100): List<DictHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return words.prefixMatches(q, limit).map { hitAt(it) }
    }

    /** Key at an absolute entry position, in index order. */
    fun wordAt(index: Int): String = words.wordAt(index)

    /** Visits every key in index order. */
    fun forEachWord(action: (index: Int, word: String) -> Unit) = words.forEachWord(action)

    fun lookupExact(query: String, limit: Int = 30): List<DictHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val matches = words.exactMatches(q, limit * 2)
        // Case-exact hits first, then the other case variants.
        val exact = ArrayList<Int>(matches.size)
        val variants = ArrayList<Int>(matches.size)
        for (i in matches) {
            if (words.wordAt(i) == q) exact.add(i) else variants.add(i)
        }
        return (exact + variants).take(limit).map { hitAt(it) }
    }

    /**
     * The app's main entry point, mirroring `StarDict.lookupSmart`: prefix first,
     * then a space-folded retry.
     *
     * StarDict's bounded edit-distance scan is deliberately not replicated. MDict
     * dictionaries here hold up to 137390 keys, and `StarDict.lookupFuzzy` walks
     * every key computing a DP matrix — affordable on a 15776-word StarDict and not
     * on these. Prefix plus space folding is what MDict readers themselves offer.
     */
    override fun lookupSmart(query: String, limit: Int): List<DictHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val prefix = lookupPrefix(q, limit)
        if (prefix.isNotEmpty()) return prefix
        return lookupSpaceFree(q, limit)
    }

    /**
     * Retries with spaces removed, matching the way MDict keys are often written
     * (`ass yeah right` for `assyeahright`).
     *
     * Only attempted when the query holds ASCII letters or whitespace: a plain CJK
     * miss is not a spacing problem, and scanning 137390 keys on every miss would
     * make the empty result the most expensive one. Candidates are found by binary
     * search on the space-free form rather than by walking the whole index.
     */
    fun lookupSpaceFree(query: String, limit: Int = 60): List<DictHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val sfq = q.filterNot { it == ' ' }
        if (sfq.length < 2) return emptyList()
        var hasAscii = false
        for (c in sfq) {
            if (c in 'A'..'Z' || c in 'a'..'z') {
                hasAscii = true
                break
            }
        }
        if (!hasAscii && q.indexOf(' ') < 0) return emptyList()
        return words.spaceFreeMatches(sfq, limit).map { hitAt(it) }
    }

    private fun hitAt(searchIndex: Int): DictHit {
        val (offset, size) = keyIndex.recordAt(reader, searchIndex)
        return DictHit(words.wordAt(searchIndex), offset, size)
    }

    // ---- article ------------------------------------------------------------

    /**
     * Builds the article for [hit], following `@@@LINK` redirects and rewriting
     * `entry://` cross references.
     */
    override fun article(hit: DictHit): Article {
        val raw = readEntry(hit.word, hit.offset, hit.size, depth = 0)
        val text = decodeText(raw, header.encoding)
        // Tagged 'h' so `Article.toHtml` emits it verbatim: MDict bodies are already
        // HTML, and StarDict's markup pass would mangle them.
        return Article(hit.word, "", listOf(ArticleSection('h', raw, rewriteLinks(text))))
    }

    /**
     * Reads one body, following `@@@LINK` chains.
     *
     * The depth cap is load-bearing: a malformed file can contain a cycle
     * (`A` -> `B` -> `A`), and returning the raw text at the cap keeps the lookup
     * useful instead of recursing until the stack dies.
     */
    private fun readEntry(word: String, offset: Long, size: Int, depth: Int): ByteArray {
        val raw = records.read(offset, size)
        if (depth >= MAX_LINK_DEPTH) return raw
        val target = linkTargetOf(raw) ?: return raw
        if (target == word) return raw
        for (i in words.exactMatches(target, 8)) {
            val (o, s) = keyIndex.recordAt(reader, i)
            return readEntry(words.wordAt(i), o, s, depth + 1)
        }
        return raw
    }

    // ---- resources ----------------------------------------------------------

    /**
     * Looks up an `.mdd` resource by path (`\XHZD_12.css`), or null when there is no
     * resource pack or no such path.
     */
    fun resource(path: String): ByteArray? {
        for (pack in resources) {
            val bytes = pack.read(path)
            if (bytes != null) return bytes
        }
        return null
    }

    // ---- lifecycle ----------------------------------------------------------

    override fun close() {
        resources.forEach { runCatching { it.close() } }
        records.clearCache()
        reader.close()
    }

    companion object {
        private const val TAG = "MdxDictionary"

        /** Enough for real redirect chains while stopping cycles. */
        private const val MAX_LINK_DEPTH = 8

        private const val MAX_HEADER_BYTES = 1L shl 20

        private const val LINK_PREFIX = "@@@LINK="

        private const val ENTRY_SCHEME = "entry://"

        private const val LOOKUP_SCHEME = "juice://lookup/"

        /**
         * Returns the redirect target when [raw] is exactly an `@@@LINK=` record.
         *
         * The prefix is compared as bytes so a large non-link record is rejected
         * without decoding it.
         */
        private fun linkTargetOf(raw: ByteArray): String? {
            if (raw.size < LINK_PREFIX.length) return null
            for (i in LINK_PREFIX.indices) {
                if (raw[i].toInt().toChar() != LINK_PREFIX[i]) return null
            }
            var end = raw.size
            while (end > LINK_PREFIX.length && raw[end - 1].toInt() == 0) end--
            if (end <= LINK_PREFIX.length) return null
            return String(raw, LINK_PREFIX.length, end - LINK_PREFIX.length, Charsets.UTF_8)
                .trim()
                .ifEmpty { null }
        }

        /**
         * Decodes a record body, dropping the trailing NUL MDX writers append.
         *
         * UTF-16 is handled directly because the platform's decoder expects a BOM,
         * which these records do not carry.
         */
        private fun decodeText(raw: ByteArray, encoding: String): String {
            var end = raw.size
            while (end > 0 && raw[end - 1].toInt() == 0) end--
            if (end <= 0) return ""
            return if (encoding.startsWith("UTF-16", ignoreCase = true)) {
                decodeUtf16Le(raw, 0, end)
            } else {
                charsetOf(encoding).let { String(raw, 0, end, it) }
            }
        }

        /** Resolves a header encoding name, falling back to Latin-1 when unknown. */
        private fun charsetOf(encoding: String): java.nio.charset.Charset =
            runCatching { java.nio.charset.Charset.forName(encoding) }
                .getOrElse { Charsets.ISO_8859_1 }

        private fun decodeUtf16Le(bytes: ByteArray, from: Int, to: Int): String {
            if (to <= from) return ""
            val chars = CharArray((to - from) / 2)
            var i = from
            var j = 0
            while (j < chars.size) {
                chars[j] = ((bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8))
                    .toChar()
                i += 2
                j++
            }
            return String(chars)
        }

        /**
         * Rewrites MDict's in-text cross references to the app's own lookup scheme:
         * `entry://foo` becomes `juice://lookup/foo`, which `SelectableLinkTextView`
         * already handles.
         *
         * Same-entry anchors (`entry://#frag`) keep their `#` so they stay local, and
         * only the URI portion is touched, leaving the body's markup intact.
         */
        internal fun rewriteLinks(text: String): String {
            if (!text.contains(ENTRY_SCHEME)) return text
            val sb = StringBuilder(text.length + 64)
            var i = 0
            while (i < text.length) {
                val at = text.indexOf(ENTRY_SCHEME, i)
                if (at < 0) {
                    sb.append(text, i, text.length)
                    break
                }
                sb.append(text, i, at)
                var end = at + ENTRY_SCHEME.length
                while (end < text.length && text[end] !in LINK_TERMINATORS) end++
                val target = text.substring(at + ENTRY_SCHEME.length, end)
                if (target.startsWith("#")) {
                    // Same-entry anchor: keep the whole fragment so it stays local. Sending
                    // it through the lookup scheme would fabricate a bogus query.
                    sb.append(target)
                } else if (target.isEmpty()) {
                    sb.append('#')
                } else {
                    sb.append(LOOKUP_SCHEME).append(encodeTarget(target))
                }
                i = end
            }
            return sb.toString()
        }

        private val LINK_TERMINATORS = charArrayOf('"', '\'', '>', ' ', ')')

        /** Percent-encodes a link target the way `Article.linkifyMarkup` does. */
        private fun encodeTarget(target: String): String =
            java.net.URLEncoder.encode(target, "UTF-8").replace("+", "%20")
    }
}
