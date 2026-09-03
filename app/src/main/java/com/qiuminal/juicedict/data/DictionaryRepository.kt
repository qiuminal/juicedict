package com.qiuminal.juicedict.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.qiuminal.juicedict.engine.DictZipReader
import com.qiuminal.juicedict.engine.Ifo
import com.qiuminal.juicedict.engine.PlainDictReader
import com.qiuminal.juicedict.engine.StarDict
import com.qiuminal.juicedict.engine.StarDictIndex
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPInputStream

/**
 * Owns the installed dictionary files (filesDir/dicts/<baseName>/...), the
 * enabled/order metadata, and the set of loaded [StarDict] instances.
 */
class DictionaryRepository(private val context: Context) {

    private val dictRoot: File = File(context.filesDir, "dicts")
    private val metaFile: File = File(context.filesDir, "dicts_meta.json")
    private val loaded = HashMap<String, StarDict>()
    private val mutex = Any()

    val rootDir: File get() = dictRoot

    /**
     * Install/refresh bundled dictionary assets.
     *
     * 每个内置词库目录放一个 `<base>.version` 标记（内容为版本号）。首次安装
     * 或版本号与上次不同时整目录重建，避免手机里残留旧版本内置词库（例如早
     * 期 APK 拷入、升级后因“文件已存在”永不更新的情况），保证查询行为与当前
     * APK 打包的词库一致。版本号相同的安装只做“缺文件补齐”。
     */
    fun ensureBundledDict() {
        dictRoot.mkdirs()
        val meta = loadMeta()
        val bundleVersions = meta.optJSONObject(BUNDLE_VERSION_KEY) ?: JSONObject()
        var metaDirty = false

        // 按词库 base 归组 assets 文件名（.ifo/.idx/.idx.gz/.dict/.dict.dz/.syn/.version）
        val byBase = HashMap<String, ArrayList<String>>()
        for (name in context.assets.list(BUNDLED_ASSET_DIR).orEmpty()) {
            val base = baseNameOf(name) ?: continue
            byBase.getOrPut(base) { ArrayList() }.add(name)
        }

        for ((base, files) in byBase) {
            val versionName = files.firstOrNull { it.endsWith(BUNDLE_VERSION_SUFFIX) }
            val expectedVersion: String = if (versionName != null) {
                runCatching {
                    context.assets.open("$BUNDLED_ASSET_DIR/$versionName").use {
                        it.readBytes().toString(Charsets.UTF_8).trim()
                    }
                }.getOrNull().orEmpty()
            } else {
                ""
            }
            val dir = File(dictRoot, base)
            val installedVersion = bundleVersions.optString(base, "")
            if (expectedVersion.isNotEmpty() && expectedVersion != installedVersion) {
                // 版本变化：整目录重建并刷新元数据
                synchronized(mutex) { loaded.remove(base)?.let { runCatching { it.close() } } }
                dir.deleteRecursively()
                bundleVersions.put(base, expectedVersion)
                metaDirty = true
            }
            dir.mkdirs()
            for (name in files) {
                if (name.endsWith(BUNDLE_VERSION_SUFFIX)) continue // 版本标记不拷入词库目录
                val target = File(dir, name)
                if (!target.exists() || target.length() == 0L) {
                    context.assets.open("$BUNDLED_ASSET_DIR/$name").use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                    metaDirty = true
                }
            }
        }
        if (metaDirty) {
            meta.put(BUNDLE_VERSION_KEY, bundleVersions)
            saveMeta(meta)
        }
        cleanupLegacyDirs()
    }

    /**
     * v1.3 及以前 baseNameOf 把 `.dict.dz` 的双扩展名算错，导致
     * `chibigenc.dict.dz` 被复制到 `dicts/chibigenc.dict/` 这样的遗留目录。
     * 清理这些既无 `.ifo` 也无法被识别为词库的旧目录，避免浪费存储空间。
     */
    private fun cleanupLegacyDirs() {
        dictRoot.listFiles { f ->
            f.isDirectory && f.name.endsWith(".dict") && !File(f, f.name + ".ifo").exists()
        }?.forEach { it.deleteRecursively() }
    }

    /** Scan installed dictionaries and merge persisted preferences. */
    fun listDictionaries(): List<DictionaryInfo> {
        val meta = loadMeta()
        // 内置标识以实际打包的 assets 为准：用户自行导入的同名词库（如 chibigenc）
        // 不应再被误标为“内置”。
        val bundledNames: Set<String> = runCatching {
            context.assets.list(BUNDLED_ASSET_DIR)
                ?.mapNotNull { baseNameOf(it) }?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        val out = ArrayList<DictionaryInfo>()
        val dirs = dictRoot.listFiles { f -> f.isDirectory } ?: emptyArray()
        for (dir in dirs) {
            val ifoFile = File(dir, dir.name + ".ifo")
            if (!ifoFile.exists()) continue
            val ifo = try {
                Ifo.parse(ifoFile.readText())
            } catch (e: Exception) {
                continue
            }
            val entry = meta.optJSONObject(dir.name)
            val dictFile = when {
                File(dir, dir.name + ".dict.dz").exists() -> File(dir, dir.name + ".dict.dz")
                File(dir, dir.name + ".dict").exists() -> File(dir, dir.name + ".dict")
                else -> null
            }
            out.add(
                DictionaryInfo(
                    id = dir.name,
                    bookName = ifo.bookName.ifBlank { dir.name },
                    baseName = dir.name,
                    wordCount = ifo.wordCount,
                    description = ifo.description ?: "",
                    author = ifo.author ?: "",
                    date = ifo.date ?: "",
                    version = ifo.version ?: "",
                    dictFileName = dictFile?.name ?: "",
                    bundled = dir.name in bundledNames,
                    enabled = entry?.optBoolean("enabled", true) ?: true,
                )
            )
        }
        out.sortBy { it.bookName }
        return out
    }

    fun listEnabled(): List<DictionaryInfo> = listDictionaries().filter { it.enabled }

    /** Load (and cache) the [StarDict] instance for a dictionary. */
    fun open(info: DictionaryInfo): StarDict? = synchronized(mutex) {
        loaded[info.id]?.let { return it }
        val dir = File(dictRoot, info.baseName)
        val ifo = try {
            Ifo.parse(File(dir, info.baseName + ".ifo").readText())
        } catch (e: Exception) {
            return null
        }
        // 预建索引缓存优先：校验通过则免去 .idx/.syn 重解析与排序，冷启动更快。
        val cacheFile = File(dir, info.baseName + ".jidx")
        val index = StarDictIndex.loadCache(cacheFile, ifo) ?: run {
            val idxBytes = try {
                readIdxBytes(File(dir, info.baseName + ".idx"), File(dir, info.baseName + ".idx.gz"))
            } catch (e: Exception) {
                return null
            }
            val synBytes = File(dir, info.baseName + ".syn").takeIf { it.exists() }?.readBytes()
            val parsed = StarDictIndex.load(ifo, idxBytes, synBytes)
            // 写缓存失败不影响本次查询（下次启动重新解析即可）。
            runCatching { StarDictIndex.writeCache(parsed, cacheFile, ifo) }
            parsed
        }
        val data = when {
            File(dir, info.baseName + ".dict.dz").exists() ->
                DictZipReader(File(dir, info.baseName + ".dict.dz"))
            File(dir, info.baseName + ".dict").exists() ->
                PlainDictReader(File(dir, info.baseName + ".dict"))
            else -> return null
        }
        val sd = StarDict(info.id, ifo, index, data)
        loaded[info.id] = sd
        sd
    }

    /** 后台预热：加载（并在必要时构建缓存）指定词典，供后续查询直接复用。 */
    fun prewarm(id: String) {
        listDictionaries().firstOrNull { it.id == id }?.let { open(it) }
    }

    /** 后台预热所有启用词典（应用启动 / 导入完成后调用）。 */
    fun prewarmAll() {
        for (info in listEnabled()) open(info)
    }

    fun article(dictId: String, offset: Long, size: Int) = synchronized(mutex) {
        loaded[dictId]?.article(StarDict.Hit("", offset, size))
    }

    fun closeAll() = synchronized(mutex) {
        loaded.values.forEach { runCatching { it.close() } }
        loaded.clear()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val meta = loadMeta()
        val entry = meta.optJSONObject(id) ?: JSONObject()
        entry.put("enabled", enabled)
        meta.put(id, entry)
        saveMeta(meta)
    }

    fun delete(id: String) {
        synchronized(mutex) {
            loaded.remove(id)?.let { runCatching { it.close() } }
        }
        val dir = File(dictRoot, id)
        if (dir.exists()) dir.deleteRecursively()
        val meta = loadMeta()
        meta.remove(id)
        saveMeta(meta)
    }

    /** Import a dictionary folder selected via SAF (ACTION_OPEN_DOCUMENT_TREE). */
    fun importFromTree(treeUri: Uri): ImportReport {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: return ImportReport(0, 0, "无法访问所选目录")
        val byBase = HashMap<String, HashMap<String, DocumentFile>>()
        tree.listFiles().forEach { f ->
            if (f.isFile) {
                val name = f.name ?: return@forEach
                val key = when {
                    name.endsWith(".dict.dz") -> "dict.dz"
                    name.endsWith(".idx.gz") -> "idx.gz"
                    name.endsWith(".dict") -> "dict"
                    name.endsWith(".idx") -> "idx"
                    name.endsWith(".ifo") -> "ifo"
                    name.endsWith(".syn") -> "syn"
                    else -> return@forEach
                }
                val base = baseNameOf(name) ?: return@forEach
                byBase.getOrPut(base) { HashMap() }[key] = f
            }
        }
        var ok = 0
        var failed = 0
        val importedIds = ArrayList<String>()
        for ((base, map) in byBase) {
            val ifoDoc = map["ifo"] ?: continue
            val ifoText = runCatching {
                context.contentResolver.openInputStream(ifoDoc.uri)?.use { it.readBytes() }
                    ?.toString(Charsets.UTF_8)
            }.getOrNull() ?: continue
            val ifo = runCatching { Ifo.parse(ifoText) }.getOrNull()
            if (ifo == null) {
                failed++
                continue
            }
            if (ifo.wordCount <= 0) {
                failed++
                continue
            }

            val idxDoc = map["idx"] ?: map["idx.gz"]
            val dictDoc = map["dict"] ?: map["dict.dz"]
            if (idxDoc == null || dictDoc == null) {
                failed++
                continue
            }

            val targetDir = File(dictRoot, base)
            targetDir.mkdirs()
            val copy = { doc: DocumentFile, file: File ->
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(file).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("open failed")
            }
            val okCopy = runCatching {
                copy(ifoDoc, File(targetDir, base + ".ifo"))
                copy(idxDoc, File(targetDir, if (map["idx"] != null) base + ".idx" else base + ".idx.gz"))
                copy(dictDoc, File(targetDir, if (map["dict"] != null) base + ".dict" else base + ".dict.dz"))
                map["syn"]?.let { copy(it, File(targetDir, base + ".syn")) }
            }.isSuccess
            if (okCopy) {
                ok++
                importedIds.add(base)
            } else {
                failed++
            }
        }
        val message = if (ok > 0) "成功导入 $ok 部词典" else "未找到可导入的词典"
        return ImportReport(ok, failed, message, importedIds)
    }

    private fun readIdxBytes(idx: File, idxGz: File): ByteArray {
        if (idxGz.exists()) {
            GZIPInputStream(idxGz.inputStream()).use { return it.readBytes() }
        }
        return idx.readBytes()
    }

    private fun loadMeta(): JSONObject {
        if (!metaFile.exists()) return JSONObject()
        return runCatching { JSONObject(metaFile.readText()) }.getOrDefault(JSONObject())
    }

    private fun saveMeta(meta: JSONObject) {
        metaFile.writeText(meta.toString())
    }

    data class ImportReport(
        val imported: Int,
        val failed: Int,
        val message: String,
        val importedIds: List<String> = emptyList(),
    )

    private companion object {
        const val BUNDLED_ASSET_DIR = "dict"
        const val BUNDLE_VERSION_KEY = "bundle_versions"
        const val BUNDLE_VERSION_SUFFIX = ".version"

        fun baseNameOf(fileName: String): String? {
            // 双扩展名（.dict.dz / .idx.gz）整体视为一个扩展名剥离，
            // 保证 chibigenc.dict.dz 与 chibigenc.ifo 归入同一词库目录 chibigenc/。
            val name = when {
                fileName.endsWith(".dict.dz") -> fileName.removeSuffix(".dict.dz")
                fileName.endsWith(".idx.gz") -> fileName.removeSuffix(".idx.gz")
                else -> fileName
            }
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }
    }
}
