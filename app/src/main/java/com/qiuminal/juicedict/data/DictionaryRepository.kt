package com.qiuminal.juicedict.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.qiuminal.juicedict.engine.Article
import com.qiuminal.juicedict.engine.DictZipReader
import com.qiuminal.juicedict.engine.Ifo
import com.qiuminal.juicedict.engine.PlainDictReader
import com.qiuminal.juicedict.engine.StarDict
import com.qiuminal.juicedict.engine.StarDictIndex
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
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
                    order = entry?.optInt("order", Int.MAX_VALUE) ?: Int.MAX_VALUE,
                )
            )
        }
        out.sortWith(compareBy<DictionaryInfo> { it.order }.thenBy { it.bookName })
        return out
    }

    fun listEnabled(): List<DictionaryInfo> = listDictionaries().filter { it.enabled }

    fun setOrder(ids: List<String>) {
        val meta = loadMeta()
        ids.forEachIndexed { index, id ->
            val entry = meta.optJSONObject(id) ?: JSONObject()
            entry.put("order", index)
            meta.put(id, entry)
        }
        saveMeta(meta)
    }

    /**
     * Load (and cache) the [StarDict] instance for a dictionary.
     *
     * v0.1.1 起整个加载过程兜底捕获 Throwable：超大词典可能 OOM、损坏词典可能
     * 抛任何异常——单部词典失败只让该词典不可查询（返回 null），绝不允许
     * 异常穿出到调用方协程把整个进程带崩（曾导致导入大词典后查询必闪退）。
     */
    fun open(info: DictionaryInfo): StarDict? = synchronized(mutex) {
        loaded[info.id]?.let { return it }
        try {
            openLocked(info)
        } catch (t: Throwable) {
            Log.w("JuiceDict", "open dictionary failed: ${info.id}", t)
            null
        }
    }

    private fun openLocked(info: DictionaryInfo): StarDict? {
        val dir = File(dictRoot, info.baseName)
        val ifo = try {
            Ifo.parse(File(dir, info.baseName + ".ifo").readText())
        } catch (e: Exception) {
            return null
        }
        // 预建索引缓存优先：校验通过则免去 .idx/.syn 重解析与排序，冷启动更快。
        val cacheFile = File(dir, info.baseName + ".jidx")
        val index = StarDictIndex.loadCache(cacheFile, ifo) ?: run {
            val idxFile = File(dir, info.baseName + ".idx")
            val idxGzFile = File(dir, info.baseName + ".idx.gz")
            val idxStream: InputStream = when {
                idxFile.exists() -> idxFile.inputStream()
                idxGzFile.exists() -> GZIPInputStream(idxGzFile.inputStream())
                else -> return null
            }
            val synStream = File(dir, info.baseName + ".syn").takeIf { it.exists() }?.inputStream()
            val parsed = idxStream.use { ins ->
                try {
                    StarDictIndex.load(ifo, ins, synStream)
                } finally {
                    synStream?.close()
                }
            }
            // 写缓存失败不影响本次查询（下次启动重新解析即可）；OOM 同样吞掉。
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
        return sd
    }

    /** 后台预热：加载（并在必要时构建缓存）指定词典，供后续查询直接复用。 */
    fun prewarm(id: String) {
        listDictionaries().firstOrNull { it.id == id }?.let { open(it) }
    }

    /** 后台预热所有启用词典（应用启动 / 导入完成后调用）；单部失败不影响其余。 */
    fun prewarmAll() {
        for (info in listEnabled()) runCatching { open(info) }
    }

    fun article(dictId: String, offset: Long, size: Int): Article? = synchronized(mutex) {
        try {
            loaded[dictId]?.article(StarDict.Hit("", offset, size))
        } catch (t: Throwable) {
            Log.w("JuiceDict", "read article failed: $dictId@$offset", t)
            null
        }
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
        val copy = { doc: DocumentFile, file: File ->
            context.contentResolver.openInputStream(doc.uri)?.use { input ->
                FileOutputStream(file).use { out -> input.copyTo(out) }
            } ?: throw IllegalStateException("open failed")
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

            // 与 Wi-Fi 导入共用同一安装通路：先写入临时目录，再原子替换正式目录
            val status = installStaged(base) { targetDir ->
                copy(ifoDoc, File(targetDir, base + ".ifo"))
                copy(idxDoc, File(targetDir, if (map["idx"] != null) base + ".idx" else base + ".idx.gz"))
                copy(dictDoc, File(targetDir, if (map["dict"] != null) base + ".dict" else base + ".dict.dz"))
                map["syn"]?.let { copy(it, File(targetDir, base + ".syn")) }
            }
            if (status.ok) {
                ok++
                importedIds.add(base)
            } else {
                failed++
            }
        }
        val message = if (ok > 0) "成功导入 $ok 部词典" else "未找到可导入的词典"
        return ImportReport(ok, failed, message, importedIds)
    }

    /**
     * 安装一部词典（Wi-Fi 传输 / 其他本地文件来源共用）：
     * [files] 的键为目标文件名（<base>.ifo 等），值为暂存区来源文件。
     * 来源文件采用「复制」而非移动——暂存区保留至 /finish 才清空，这样组内晚到的
     * 文件（如 .syn）仍能触发一次携带全量文件的重导入，覆盖出完整词典。
     * 安装过程：复制到 dicts/.incoming-<base>/ 临时目录 → 删除旧目录 → 原子改名。
     * 覆盖安装（重传同名词典）会一并失效内存中的旧实例与 .jidx 缓存。
     */
    fun installFromFiles(base: String, files: Map<String, File>): InstallStatus =
        installStaged(base) { targetDir ->
            for ((name, src) in files) {
                src.copyTo(File(targetDir, name), overwrite = true)
            }
        }

    /** 临时目录准备好后，经 [copyInto] 填充文件，然后原子替换 dicts/<base>/。 */
    private fun installStaged(base: String, copyInto: (File) -> Unit): InstallStatus {
        synchronized(mutex) {
            // 覆盖安装前先丢弃内存中的旧实例，避免查询继续用旧数据
            loaded.remove(base)?.let { runCatching { it.close() } }
        }
        val incoming = File(dictRoot, ".incoming-$base")
        incoming.deleteRecursively()
        if (!incoming.mkdirs()) {
            return InstallStatus(false, null, "无法创建临时目录")
        }
        try {
            copyInto(incoming)
        } catch (e: Exception) {
            incoming.deleteRecursively()
            return InstallStatus(false, null, "复制词典文件失败：${e.message}")
        }
        val target = File(dictRoot, base)
        target.deleteRecursively()
        return if (incoming.renameTo(target)) {
            InstallStatus(true, readBookName(File(target, "$base.ifo"), base), null)
        } else {
            incoming.deleteRecursively()
            InstallStatus(false, null, "词典目录替换失败")
        }
    }

    private fun readBookName(ifoFile: File, fallback: String): String =
        runCatching { Ifo.parse(ifoFile.readText()).bookName }
            .getOrNull()?.ifBlank { fallback } ?: fallback

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

    /** installFromFiles / installStaged 的安装结果。 */
    data class InstallStatus(
        val ok: Boolean,
        val bookName: String?,
        val error: String?,
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
