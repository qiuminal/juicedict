package com.qiuminal.juicedict.engine.mdict

import java.io.File

/**
 * Engine-level smoke test over the real dictionaries in `E:\dictionary\MDict`.
 *
 * `MdxVerify` checks the binary layout; this drives the surface the app actually
 * uses: open a dictionary, walk its keys, run the search chain, render an article,
 * follow an `@@@LINK` redirect and pull an `.mdd` resource. It is a development
 * tool — the committed tests live in `app/src/test`.
 */
object MdxSmoke {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args.getOrElse(0) { "E:\\dictionary\\MDict" })
        var pass = 0
        var fail = 0

        val files = root.walkTopDown()
            .filter { it.isFile && it.extension.equals("mdx", ignoreCase = true) }
            .sortedBy { it.name }
            .toList()

        for (mdx in files) {
            val label = mdx.name
            try {
                probe(mdx)
                pass++
                println("OK   $label")
            } catch (e: Throwable) {
                fail++
                println("FAIL $label: ${e::class.simpleName}: ${e.message}")
                e.stackTrace.take(5).forEach { println("       at $it") }
            }
        }
        println()
        println("=== $pass passed, $fail failed ===")
        if (fail > 0) kotlin.system.exitProcess(1)
    }

    private fun probe(mdx: File) {
        MdxDictionary(
            id = "smoke:" + mdx.nameWithoutExtension,
            mdxFile = mdx,
            mddFiles = mddFilesFor(mdx),
            title = "",
            description = "",
        ).use { dict ->
            println("--- ${mdx.name}  (${dict.wordCount} entries, title='${dict.bookTitle}')")
            check(dict.wordCount > 0) { "no keys loaded" }

            // Walk a fixed set of positions so the run is deterministic: spread
            // across the key space rather than only the ASCII front matter.
            val positions = intArrayOf(0, dict.wordCount / 4, dict.wordCount / 2,
                dict.wordCount * 3 / 4, dict.wordCount - 1)

            for (pos in positions) {
                val word = dict.wordAt(pos)
                check(word.isNotEmpty()) { "empty key at $pos" }

                val hit = dict.lookupExact(word, 1).firstOrNull()
                    ?: error("key '$word' at $pos did not resolve through lookupExact")

                val article = dict.article(hit)
                val html = article.toHtml()
                check(html.isNotBlank()) { "empty article for '$word'" }
                check(!html.contains("@@@LINK=")) { "redirect left unresolved for '$word'" }
                check(!html.contains("entry://")) { "entry:// survived rewriting in '$word'" }

                val links = Regex("juice://lookup/").findAll(html).count()
                val preview = article.preview(60).replace('\n', ' ').trim()
                println("    [$pos] '$word' -> ${html.length}B, $links link(s)  ${preview.take(50)}")
            }

            // Search chain: a prefix of a real key must find that key.
            val sample = dict.wordAt(dict.wordCount / 2)
            val prefixLen = minOf(2, sample.length)
            if (prefixLen > 0) {
                val prefix = sample.substring(0, prefixLen)
                val hits = dict.lookupSmart(prefix, 10)
                check(hits.isNotEmpty()) { "prefix '$prefix' found nothing" }
                println("    prefix '$prefix' -> ${hits.size} hits")
            }

            // A miss must be empty, not an exception.
            check(dict.lookupSmart("\u0001\u0002zzz-not-a-word", 5).isEmpty()) {
                "bogus query returned hits"
            }

            for (mdd in mddFilesFor(mdx)) probeResources(mdd, dict)
        }
    }

    private fun probeResources(mdd: File, dict: MdxDictionary) {
        val pack = runCatching { MdxResourcePack(mdd) }.getOrElse {
            println("    (resource pack ${mdd.name} unreadable: ${it.message})")
            return
        }
        pack.use { p ->
            println("    resources: ${p.resourceCount} in ${mdd.name}")
            val path = firstResourcePath(mdd)
            if (path != null) {
                val bytes = p.read(path)
                if (bytes == null) {
                    println("    WARN first resource '$path' not found by path")
                } else {
                    println("    resource '$path' -> ${bytes.size}B")
                }
            }
            // A path that certainly does not exist must return null, not throw.
            check(p.read("\\no-such-resource-xyz.png") == null) { "missing resource threw" }
        }
    }

    /** Every `.mdd` paired with an `.mdx`, using the same rule the app layer uses. */
    private fun mddFilesFor(mdx: File): List<File> {
        val dir = mdx.parentFile ?: return emptyList()
        return MdxResourceFiles.pairsFor(dir, mdx.nameWithoutExtension)
    }

    /** First key of the first key block, used as a real resource path to read. */
    private fun firstResourcePath(mdd: File): String? = runCatching {
        MdxFileReader(mdd).use { r ->
            val head = MdxHeader.parse(r.read(0L, minOf(r.length, 1L shl 20).toInt()))
                ?: return null
            val index = MdxKeyIndex.parse(
                reader = r,
                offset = head.keySectionOffset,
                unitSize = 2,
                declaredEntries = 0L,
                version = head.version,
                stripKey = head.stripKey,
            )
            val raw = r.read(index.blockOffsetAt(0), index.blockCompSizeAt(0).toInt())
            val bytes = MdxBlockReader.read(raw, index.blockDecompSizeAt(0))
            var p = 8
            val start = p
            while (p + 2 <= bytes.size && !(bytes[p].toInt() == 0 && bytes[p + 1].toInt() == 0)) p += 2
            val chars = CharArray((p - start) / 2)
            var i = start
            var j = 0
            while (j < chars.size) {
                chars[j] = ((bytes[i].toInt() and 0xff) or
                    ((bytes[i + 1].toInt() and 0xff) shl 8)).toChar()
                i += 2
                j++
            }
            String(chars)
        }
    }.getOrNull()
}
