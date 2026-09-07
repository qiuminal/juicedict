package com.qiuminal.juicedict.wifi

import com.qiuminal.juicedict.engine.Ifo
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Wi-Fi 传词典的接收端核心逻辑（纯 JVM，不依赖 Android，可单元测试）。
 *
 * 工作方式：
 * 1. 电脑端逐个 PUT 上传文件，[store] 把文件按相对路径落到暂存区（staging），
 *    路径与文件名先经白名单/安全性校验；
 * 2. 每收到一个文件就检查它所属的「词典组」是否已凑齐（.ifo + .idx/.idx.gz +
 *    .dict/.dict.dz，可选 .syn），凑齐立即经 [installer] 回调装入词典仓库——
 *    这样手机端和电脑端都能实时看到「已导入」；
 * 3. 电脑端上传完一批后 POST /finish，[finish] 对剩余暂存内容做兜底导入，
 *    汇报不完整的组并清空暂存区。
 *
 * 词典分组规则与 app 仓库布局一致：同一目录下、同名词干的文件属于同一部词典。
 * 因此电脑端「松散多文件」与「按文件夹拖入」两种方式都能识别，也能一次拖入多部词典。
 */
class WifiImportEngine(
    private val stagingRoot: File,
    private val listener: (TransferEvent) -> Unit = {},
    private val installer: (base: String, files: Map<String, File>) -> InstallResult,
) {

    /** 上传文件在词典组内的角色；suffix 为规范（小写）扩展名。 */
    enum class DictFileRole(val suffix: String) {
        IFO(".ifo"), IDX(".idx"), IDX_GZ(".idx.gz"), DICT(".dict"), DICT_DZ(".dict.dz"), SYN(".syn");

        /** 从文件名剥离角色扩展名后的词干（保留原大小写）。 */
        fun stemOf(fileName: String): String = fileName.substring(0, fileName.length - suffix.length)

        companion object {
            fun of(fileName: String): DictFileRole? {
                // 双扩展名优先判断，避免 .idx.gz 被当成 .gz/.idx 误判
                val n = fileName.lowercase()
                return when {
                    n.endsWith(IDX_GZ.suffix) -> IDX_GZ
                    n.endsWith(DICT_DZ.suffix) -> DICT_DZ
                    n.endsWith(IFO.suffix) -> IFO
                    n.endsWith(IDX.suffix) -> IDX
                    n.endsWith(DICT.suffix) -> DICT
                    n.endsWith(SYN.suffix) -> SYN
                    else -> null
                }
            }
        }
    }

    /** 单个文件接收结果。consumed = 已从输入流消费的字节数（余下由调用方排空）。 */
    sealed class StoreOutcome(val consumed: Long) {
        data class Stored(val bytes: Long, val imported: ImportRecord?) : StoreOutcome(bytes)
        class Rejected(val reason: String) : StoreOutcome(0L)
        class StoreError(val reason: String, consumed: Long) : StoreOutcome(consumed)
    }

    private enum class GroupState { PENDING, IMPORTED, FAILED }

    private class GroupInfo(var state: GroupState = GroupState.PENDING, var reason: String? = null)

    private val groups = HashMap<String, GroupInfo>() // key: "<目录相对路径>|<词干>"

    /** 清空暂存区并复位会话状态（服务启动时调用，顺带清理上次异常退出留下的残余）。 */
    fun reset() {
        synchronized(this) {
            stagingRoot.deleteRecursively()
            stagingRoot.mkdirs()
            groups.clear()
        }
    }

    /**
     * 接收一个上传文件。若该文件使其词典组凑齐，立即导入并在结果里带回导入记录
     * （供电脑端实时显示）。校验失败返回 [StoreOutcome.Rejected]，此时不消费输入流。
     */
    fun store(relPath: String, declaredSize: Long, input: InputStream): StoreOutcome = synchronized(this) {
        val normalized = sanitize(relPath) ?: return StoreOutcome.Rejected("文件名或路径不合法")
        if (declaredSize < 0 || declaredSize > MAX_FILE_BYTES) {
            return StoreOutcome.Rejected("文件过大（单文件上限 2GB）")
        }
        val role = DictFileRole.of(normalized.fileName) ?: return StoreOutcome.Rejected(
            "不支持的文件类型（仅支持 StarDict 词典文件）"
        )
        val stem = role.stemOf(normalized.fileName)
        if (stem.isEmpty() || stem.length > MAX_STEM_LEN || stem.startsWith(".")) {
            return StoreOutcome.Rejected("文件名不合法")
        }
        val target = File(stagingRoot, normalized.relativePath)
        target.parentFile?.mkdirs()
        var written = 0L
        try {
            FileOutputStream(target).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                var remaining = declaredSize
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("上传中断")
                    out.write(buf, 0, n)
                    written += n
                    remaining -= n
                }
            }
        } catch (e: IOException) {
            target.delete()
            return StoreOutcome.StoreError("写入失败：${e.message}", written)
        }
        val record = maybeImport(normalized.dirRelative, stem)
        StoreOutcome.Stored(written, record)
    }

    /**
     * 一批上传结束后的收尾：把仍待处理的完整组导入，汇总不完整的组，
     * 然后清空暂存区。可多次调用（电脑端每拖一批调用一次）。
     */
    fun finish(): FinishReport = synchronized(this) {
        val imported = ArrayList<ImportRecord>()
        val incomplete = ArrayList<IncompleteRecord>()
        val seen = HashSet<String>()
        walkStagedFiles { relDir, name ->
            val role = DictFileRole.of(name) ?: return@walkStagedFiles
            val stem = role.stemOf(name)
            if (!seen.add("$relDir|$stem")) return@walkStagedFiles
            val info = groups.getOrPut("$relDir|$stem") { GroupInfo() }
            when (info.state) {
                GroupState.IMPORTED -> Unit
                GroupState.FAILED -> incomplete.add(IncompleteRecord(stem, info.reason ?: "导入失败"))
                GroupState.PENDING -> {
                    val group = collectGroup(relDir, stem)
                    val ifoFile = group?.files?.get(DictFileRole.IFO)
                    if (ifoFile == null) {
                        incomplete.add(IncompleteRecord(stem, "缺少 .ifo 文件"))
                    } else {
                        val hasIdx = group.files.containsKey(DictFileRole.IDX) ||
                            group.files.containsKey(DictFileRole.IDX_GZ)
                        val hasDict = group.files.containsKey(DictFileRole.DICT) ||
                            group.files.containsKey(DictFileRole.DICT_DZ)
                        if (!hasIdx || !hasDict) {
                            val missing = if (!hasIdx) ".idx/.idx.gz" else ".dict/.dict.dz"
                            incomplete.add(IncompleteRecord(stem, "缺少 $missing 数据文件"))
                        } else {
                            maybeImport(relDir, stem)?.let { rec ->
                                if (rec.ok) imported.add(rec)
                                else incomplete.add(IncompleteRecord(rec.bookName, rec.reason ?: "导入失败"))
                            }
                        }
                    }
                }
            }
        }
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()
        groups.clear()
        FinishReport(imported, incomplete)
    }

    /** 尝试导入 (dirRel, stem) 对应的组；不完整或已导入过则返回 null。 */
    private fun maybeImport(dirRel: String, stem: String): ImportRecord? {
        val info = groups.getOrPut("$dirRel|$stem") { GroupInfo() }
        // 已导入/已失败的组若重新收到文件（用户重新拖入），重置为待定再判定一次
        if (info.state != GroupState.PENDING) info.state = GroupState.PENDING
        val group = collectGroup(dirRel, stem) ?: return null
        val ifoFile = group.files[DictFileRole.IFO] ?: return null
        val idx = group.files[DictFileRole.IDX] ?: group.files[DictFileRole.IDX_GZ] ?: return null
        val dict = group.files[DictFileRole.DICT] ?: group.files[DictFileRole.DICT_DZ] ?: return null

        val ifo = try {
            Ifo.parse(ifoFile.readText())
        } catch (e: Exception) {
            null
        }
        if (ifo == null || ifo.wordCount <= 0) {
            info.state = GroupState.FAILED
            info.reason = "无法解析 .ifo 或 wordcount 无效"
            emit(false, "《$stem》导入失败：${info.reason}")
            return ImportRecord(stem, stem, false, info.reason)
        }

        val files = LinkedHashMap<String, File>()
        files[stem + DictFileRole.IFO.suffix] = ifoFile
        val idxName = if (group.files.containsKey(DictFileRole.IDX)) stem + DictFileRole.IDX.suffix
        else stem + DictFileRole.IDX_GZ.suffix
        files[idxName] = idx
        val dictName = if (group.files.containsKey(DictFileRole.DICT)) stem + DictFileRole.DICT.suffix
        else stem + DictFileRole.DICT_DZ.suffix
        files[dictName] = dict
        group.files[DictFileRole.SYN]?.let { files[stem + DictFileRole.SYN.suffix] = it }

        val result = try {
            installer(stem, files)
        } catch (e: Exception) {
            InstallResult(false, null, e.message ?: "安装异常")
        }
        return if (result.ok) {
            info.state = GroupState.IMPORTED
            info.reason = null
            val bookName = result.bookName?.ifBlank { stem } ?: stem
            emit(true, "已导入《$bookName》")
            ImportRecord(stem, bookName, true, null)
        } else {
            info.state = GroupState.FAILED
            info.reason = result.error ?: "未知错误"
            emit(false, "《$stem》导入失败：${info.reason}")
            ImportRecord(stem, stem, false, info.reason)
        }
    }

    /** 扫描暂存区内 (dirRel, stem) 组的现有文件。 */
    private fun collectGroup(dirRel: String, stem: String): StagedGroup? {
        val dir = if (dirRel.isEmpty()) stagingRoot else File(stagingRoot, dirRel)
        val names = dir.list() ?: return null
        val map = HashMap<DictFileRole, File>()
        for (n in names) {
            val f = File(dir, n)
            if (!f.isFile) continue
            val role = DictFileRole.of(n) ?: continue
            if (role.stemOf(n) != stem) continue
            if (!map.containsKey(role)) map[role] = f
        }
        return if (map.isEmpty()) null else StagedGroup(map)
    }

    private fun walkStagedFiles(block: (relDir: String, name: String) -> Unit) {
        fun walk(dir: File, relDir: String) {
            val names = dir.list() ?: return
            for (n in names) {
                val f = File(dir, n)
                if (f.isDirectory) {
                    walk(f, if (relDir.isEmpty()) n else "$relDir/$n")
                } else {
                    block(relDir, n)
                }
            }
        }
        walk(stagingRoot, "")
    }

    private fun emit(ok: Boolean, text: String) {
        listener(TransferEvent(System.currentTimeMillis(), ok, text))
    }

    private class NormalizedPath(val relativePath: String) {
        val dirRelative: String = relativePath.substringBeforeLast('/', "")
        val fileName: String = relativePath.substringAfterLast('/')
    }

    /** 校验并规范化相对路径：拒绝绝对路径、.. 穿越、隐藏文件、过深/过长路径。 */
    private fun sanitize(raw: String): NormalizedPath? {
        val path = raw.replace('\\', '/').trim()
        if (path.isEmpty() || path.startsWith("/")) return null
        val segs = path.split('/').filter { it.isNotEmpty() && it != "." }
        if (segs.isEmpty() || segs.size > MAX_PATH_DEPTH) return null
        for (s in segs) {
            if (s == ".." || s.length > MAX_SEGMENT_LEN || s.startsWith(".")) {
                return null
            }
        }
        return NormalizedPath(segs.joinToString("/"))
    }

    private class StagedGroup(val files: Map<DictFileRole, File>)

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024 // 单文件 2GB
        private const val MAX_PATH_DEPTH = 3
        private const val MAX_SEGMENT_LEN = 200
        private const val MAX_STEM_LEN = 100
        private const val BUFFER_SIZE = 64 * 1024
    }
}

/** 安装回调的结果。 */
data class InstallResult(val ok: Boolean, val bookName: String?, val error: String?)

/** 一次词典导入的记录（也用于 /upload 响应的实时反馈）。 */
data class ImportRecord(val base: String, val bookName: String, val ok: Boolean, val reason: String?)

/** /finish 汇报中不完整/失败的组。 */
data class IncompleteRecord(val name: String, val reason: String)

/** /finish 的汇总报告。 */
data class FinishReport(val imported: List<ImportRecord>, val incomplete: List<IncompleteRecord>)

/** 面向手机端会话日志的一条事件。 */
data class TransferEvent(val timeMillis: Long, val ok: Boolean, val text: String)
