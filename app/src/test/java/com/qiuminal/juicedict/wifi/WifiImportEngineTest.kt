package com.qiuminal.juicedict.wifi

import com.qiuminal.juicedict.engine.Ifo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/**
 * Wi-Fi 传词典引擎的分组 / 校验 / 自动导入 / 收尾逻辑测试（纯 JVM）。
 */
class WifiImportEngineTest {

    private lateinit var staging: File
    private lateinit var installedRoot: File
    private lateinit var installer: RecordingInstaller
    private lateinit var engine: WifiImportEngine
    private val events = ArrayList<TransferEvent>()

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("staging").toFile()
        installedRoot = Files.createTempDirectory("installed").toFile()
        events.clear()
        installer = RecordingInstaller(installedRoot)
        engine = WifiImportEngine(staging, { events.add(it) }) { base, files -> installer.install(base, files) }
        engine.reset()
    }

    @After
    fun tearDown() {
        staging.deleteRecursively()
        installedRoot.deleteRecursively()
    }

    // ---------- 帮助函数 ----------

    private fun ifoText(name: String, wordCount: Long = 100): String =
        "StarDict's dict ifo file\nversion=2.4.2\nbookname=$name\nwordcount=$wordCount\n" +
            "idxfilesize=9999\nsametypesequence=m\n"

    private fun put(relPath: String, content: String): WifiImportEngine.StoreOutcome {
        val bytes = content.toByteArray(Charsets.UTF_8)
        return engine.store(relPath, bytes.size.toLong(), ByteArrayInputStream(bytes))
    }

    private fun stored(outcome: WifiImportEngine.StoreOutcome): WifiImportEngine.StoreOutcome.Stored {
        assertTrue("期望 Stored 实为 $outcome", outcome is WifiImportEngine.StoreOutcome.Stored)
        return outcome as WifiImportEngine.StoreOutcome.Stored
    }

    // ---------- 用例 ----------

    @Test
    fun `松散多文件按词干成组凑齐即导入`() {
        assertNull(stored(put("ld.ifo", ifoText("朗道"))).imported)
        assertNull(stored(put("ld.idx", "idx-bytes")).imported)
        val third = stored(put("ld.dict", "dict-bytes"))
        val record = third.imported
        assertNotNull("第三个文件应触发导入", record)
        assertEquals("朗道", record!!.bookName)
        assertTrue(record.ok)

        assertEquals(1, installer.calls.size)
        val (base, files) = installer.calls[0]
        assertEquals("ld", base)
        assertEquals(setOf("ld.ifo", "ld.idx", "ld.dict"), files.keys)
        // 安装为「复制」语义：暂存文件保留到 finish，晚到文件可触发全量重导入
        assertEquals("dict-bytes", File(installedRoot, "ld/ld.dict").readText())
        assertTrue(File(staging, "ld.dict").exists())

        // finish 不再重复导入，且清空暂存区
        val report = engine.finish()
        assertTrue(report.imported.isEmpty())
        assertTrue(report.incomplete.isEmpty())
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `文件夹模式与一次多部词典`() {
        put("dictA/a.ifo", ifoText("词典A"))
        put("dictA/a.idx", "idx-a")
        put("dictA/a.dict", "dict-a")
        put("dictB/b.ifo", ifoText("词典B"))
        put("dictB/b.idx.gz", "idx-gz-b")
        put("dictB/b.dict.dz", "dict-dz-b")

        assertEquals(2, installer.calls.size)
        val bases = installer.calls.map { it.first }.toSet()
        assertEquals(setOf("a", "b"), bases)
        // 压缩变体保留其规范名
        val bFiles = installer.calls.first { it.first == "b" }.second.keys
        assertEquals(setOf("b.ifo", "b.idx.gz", "b.dict.dz"), bFiles)
        assertTrue(File(installedRoot, "a/a.dict").exists())
        assertTrue(File(installedRoot, "b/b.dict.dz").exists())
    }

    @Test
    fun `不完整的组由finish汇报并清理`() {
        put("c.ifo", ifoText("残缺"))
        put("c.idx", "idx-c")
        val report = engine.finish()
        assertTrue(report.imported.isEmpty())
        assertEquals(1, report.incomplete.size)
        assertEquals("c", report.incomplete[0].name)
        assertTrue(report.incomplete[0].reason.contains(".dict"))
        // finish 后暂存区清空
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `只有数据文件没有ifo时提示缺少ifo`() {
        put("d.idx", "idx-d")
        put("d.dict", "dict-d")
        val report = engine.finish()
        assertEquals(1, report.incomplete.size)
        assertTrue(report.incomplete[0].reason.contains(".ifo"))
    }

    @Test
    fun `拒绝非法路径与非法类型`() {
        assertTrue(engine.store("../evil.ifo", 1, ByteArrayInputStream("x".toByteArray())) is WifiImportEngine.StoreOutcome.Rejected)
        assertTrue(engine.store("/abs/a.ifo", 1, ByteArrayInputStream("x".toByteArray())) is WifiImportEngine.StoreOutcome.Rejected)
        assertTrue(engine.store("a/../../b.ifo", 1, ByteArrayInputStream("x".toByteArray())) is WifiImportEngine.StoreOutcome.Rejected)
        assertTrue(engine.store("x.exe", 1, ByteArrayInputStream("x".toByteArray())) is WifiImportEngine.StoreOutcome.Rejected)
        assertTrue(engine.store(".hidden.ifo", 1, ByteArrayInputStream("x".toByteArray())) is WifiImportEngine.StoreOutcome.Rejected)
        assertTrue(engine.store("deep/a/b/c/d.ifo", 1, ByteArrayInputStream("x".toByteArray())) is WifiImportEngine.StoreOutcome.Rejected)
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `超过单文件上限被拒绝且不消费输入流`() {
        val outcome = engine.store("big.dict", WifiImportEngine.MAX_FILE_BYTES + 1, ByteArrayInputStream(ByteArray(0)))
        assertTrue(outcome is WifiImportEngine.StoreOutcome.Rejected)
        assertEquals(0L, outcome.consumed)
    }

    @Test
    fun `重新拖入同名词典会再次导入覆盖`() {
        put("e.ifo", ifoText("旧版"))
        put("e.idx", "idx")
        put("e.dict", "old")
        assertTrue(installer.calls.size >= 1)

        // 重新上传全部文件 → 再次导入（覆盖安装），最终以新内容为准
        put("e.ifo", ifoText("新版"))
        put("e.idx", "idx")
        put("e.dict", "new")
        assertTrue(installer.calls.size >= 2)
        assertEquals("new", File(installedRoot, "e/e.dict").readText())
        assertEquals("新版", Ifo.parse(File(installedRoot, "e/e.ifo").readText()).bookName)
    }

    @Test
    fun `idx与idxgz同时存在时优先普通idx`() {
        put("f.ifo", ifoText("F"))
        put("f.idx.gz", "idxgz")
        put("f.idx", "idxplain")
        put("f.dict", "dict")
        val files = installer.calls[0].second
        assertEquals(setOf("f.ifo", "f.idx", "f.dict"), files.keys)
    }

    @Test
    fun `syn文件随组带入`() {
        put("g.ifo", ifoText("G"))
        put("g.idx", "idx")
        put("g.dict", "dict")
        put("g.syn", "syn")
        // .syn 晚到时触发一次带全量文件的重导入，最终安装包含 .syn
        val last = installer.calls.last().second.keys
        assertEquals(setOf("g.ifo", "g.idx", "g.dict", "g.syn"), last)
        assertEquals("syn", File(installedRoot, "g/g.syn").readText())
    }

    @Test
    fun `ifo无效时标记失败并汇报`() {
        val bad = "StarDict's dict ifo file\nversion=2.4.2\nbookname=坏\nwordcount=0\n"
        put("h.ifo", bad)
        put("h.idx", "idx")
        put("h.dict", "dict")
        val report = engine.finish()
        // finish 对 FAILED 组给出失败原因
        assertEquals(1, report.incomplete.size)
        assertTrue(report.incomplete[0].reason.isNotEmpty())
        assertTrue(events.any { !it.ok })
    }

    @Test
    fun `安装失败后重新上传可恢复`() {
        installer.failNext = true
        put("k.ifo", ifoText("K"))
        put("k.idx", "idx")
        put("k.dict", "dict-v1")
        assertEquals(1, installer.calls.size)

        put("k.ifo", ifoText("K"))
        put("k.idx", "idx")
        put("k.dict", "dict-v2")
        assertTrue(installer.calls.size >= 2)
        // 最终安装以最后一次（完整）上传为准
        assertEquals("dict-v2", File(installedRoot, "k/k.dict").readText())
    }

    @Test
    fun `事件流包含导入结果`() {
        put("m.ifo", ifoText("事件词典"))
        put("m.idx", "idx")
        put("m.dict", "dict")
        assertTrue(events.any { it.ok && it.text.contains("事件词典") })
    }

    /** 记录调用并模拟真实安装器：以「复制」语义把暂存文件装进安装目录（暂存保留）。 */
    private class RecordingInstaller(private val root: File) {
        val calls = ArrayList<Pair<String, Map<String, File>>>()
        var failNext = false

        fun install(base: String, files: Map<String, File>): InstallResult {
            calls.add(base to files)
            if (failNext) {
                failNext = false
                return InstallResult(false, null, "boom")
            }
            val dir = File(root, base).apply { mkdirs() }
            for ((name, src) in files) {
                src.copyTo(File(dir, name), overwrite = true)
            }
            val bookName = runCatching { Ifo.parse(File(dir, "$base.ifo").readText()).bookName }
                .getOrNull()?.ifBlank { base } ?: base
            return InstallResult(true, bookName, null)
        }
    }
}
