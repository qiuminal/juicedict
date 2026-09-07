package com.qiuminal.juicedict.wifi

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files

/**
 * TransferServer 的真实 HTTP 联调测试（纯 JVM，本地回环 + 临时端口）。
 * 覆盖：页面下发、流式上传、自动导入回告、非法请求、finish 汇报与暂存清理。
 */
class TransferServerTest {

    private lateinit var staging: File
    private lateinit var installedRoot: File
    private lateinit var installer: RecordingInstaller
    private lateinit var engine: WifiImportEngine
    private lateinit var server: TransferServer
    private val activeUploadSamples = ArrayList<Int>()

    @Before
    fun setUp() {
        staging = Files.createTempDirectory("staging").toFile()
        installedRoot = Files.createTempDirectory("installed").toFile()
        installer = RecordingInstaller(installedRoot)
        engine = WifiImportEngine(staging, listener = { }) { base, files ->
            installer.install(base, files)
        }
        engine.reset()
        server = TransferServer(0, PAGE_BYTES, engine) { activeUploadSamples.add(it) }
        server.start(5000)
    }

    @After
    fun tearDown() {
        server.stop()
        staging.deleteRecursively()
        installedRoot.deleteRecursively()
    }

    private fun baseUrl(): String = "http://127.0.0.1:${server.listeningPort}"

    private fun ifoText(name: String): String =
        "StarDict's dict ifo file\nversion=2.4.2\nbookname=$name\nwordcount=100\n" +
            "idxfilesize=9999\nsametypesequence=m\n"

    private fun put(path: String, body: ByteArray): Pair<Int, String> {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.outputStream.use { it.write(body) }
        return readResponse(conn)
    }

    private fun post(path: String): Pair<Int, String> {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        return readResponse(conn)
    }

    private fun get(path: String): Pair<Int, String> {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        return readResponse(conn)
    }

    private fun readResponse(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        return code to text
    }

    private fun up(name: String, content: String): Pair<Int, String> =
        put("/upload?name=" + URLEncoder.encode(name, "UTF-8"), content.toByteArray(Charsets.UTF_8))

    @Test
    fun `GET根路径返回上传页`() {
        val (code, body) = get("/")
        assertEquals(200, code)
        assertEquals(PAGE_TEXT, body)
    }

    @Test
    fun `凑齐词典的最后一个上传带回导入结果`() {
        assertEquals(200, up("ld.ifo", ifoText("朗道")).first)
        val second = up("ld.idx", "idx-bytes")
        assertTrue(!second.second.contains("\"import\":{"))
        val third = up("ld.dict", "dict-bytes")
        assertEquals(200, third.first)
        assertTrue(third.second.contains("\"import\":{"))
        assertTrue(third.second.contains("朗道"))
        assertEquals(1, installer.calls.size)
        assertEquals("dict-bytes", File(installedRoot, "ld/ld.dict").readText())
    }

    @Test
    fun `非法类型返回400且不影响后续请求`() {
        val bad = put("/upload?name=" + URLEncoder.encode("x.exe", "UTF-8"), "junk".toByteArray())
        assertEquals(400, bad.first)
        assertTrue(bad.second.contains("不支持的文件类型"))
        // 同一服务器继续正常工作
        assertEquals(200, up("ok.ifo", ifoText("OK")).first)
    }

    @Test
    fun `缺少name参数返回400`() {
        val (code, _) = put("/upload", "x".toByteArray())
        assertEquals(400, code)
    }

    @Test
    fun `finish汇报不完整组并清空暂存`() {
        up("half.ifo", ifoText("残缺"))
        up("half.idx", "idx")
        val (code, body) = post("/finish")
        assertEquals(200, code)
        assertTrue(body.contains("\"incomplete\":[{"))
        assertTrue(body.contains("half"))
        assertTrue(staging.listFiles().isNullOrEmpty())
    }

    @Test
    fun `finish后可开始新一轮上传`() {
        up("half.ifo", ifoText("残缺"))
        post("/finish")
        assertEquals(200, up("full.ifo", ifoText("完整")).first)
        up("full.idx", "idx")
        val last = up("full.dict", "dict")
        assertTrue(last.second.contains("\"import\":{"))
    }

    @Test
    fun `未知路径返回404`() {
        val (code, _) = get("/nope")
        assertEquals(404, code)
    }

    private class RecordingInstaller(private val root: File) {
        val calls = ArrayList<Pair<String, Map<String, File>>>()

        fun install(base: String, files: Map<String, File>): InstallResult {
            calls.add(base to files)
            val dir = File(root, base).apply { mkdirs() }
            for ((name, src) in files) {
                src.copyTo(File(dir, name), overwrite = true)
            }
            val bookName = runCatching {
                com.qiuminal.juicedict.engine.Ifo.parse(File(dir, "$base.ifo").readText()).bookName
            }.getOrNull()?.ifBlank { base } ?: base
            return InstallResult(true, bookName, null)
        }
    }

    companion object {
        private const val PAGE_TEXT = "<!DOCTYPE html><html><body>upload page</body></html>"
        private val PAGE_BYTES = PAGE_TEXT.toByteArray(Charsets.UTF_8)
    }
}
