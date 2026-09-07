package com.qiuminal.juicedict.wifi

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wi-Fi 传词典的内嵌 HTTP 服务器（NanoHTTPD 2.3.1，纯 JVM 可单测）。
 *
 * 路由（电脑端浏览器访问）：
 * - GET  /            → 上传页（assets/wifi/import.html 的内容）
 * - GET  /favicon.ico → 204 空响应
 * - PUT  /upload?name=<相对路径> → 请求体原样落盘到暂存区；不使用 multipart，
 *   也不调用 NanoHTTPD 的 parseBody()（那会把整个请求体缓冲后再处理），
 *   而是直接流式读取 content-length 指定的字节数，大词典也不会撑爆内存。
 *   若本次上传恰好凑齐一部词典，响应 JSON 里带 "import" 字段实时回告。
 * - POST /finish      → 兜底导入 + 汇报不完整组 + 清空暂存区。
 *
 * 每个连接由 NanoHTTPD 的 DefaultAsyncRunner 在独立线程处理，天然支持并发上传。
 */
class TransferServer(
    port: Int,
    private val pageBytes: ByteArray,
    private val engine: WifiImportEngine,
    private val onActiveUploadsChanged: (Int) -> Unit = {},
) : NanoHTTPD(port) {

    private val activeUploads = AtomicInteger(0)

    /** NanoHTTPD 主监听线程之外，每个连接一个工作线程。 */
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        return when {
            session.method == Method.GET && (uri == "/" || uri == "/index.html") -> servePage()
            session.method == Method.GET && uri == "/favicon.ico" ->
                newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "")
            session.method == Method.PUT && uri == "/upload" -> handleUpload(session)
            session.method == Method.POST && uri == "/finish" -> handleFinish()
            else -> json(Response.Status.NOT_FOUND, """{"ok":false,"reason":"not found"}""")
        }
    }

    private fun servePage(): Response = newFixedLengthResponse(
        Response.Status.OK,
        "text/html; charset=utf-8",
        ByteArrayInputStream(pageBytes),
        pageBytes.size.toLong()
    )

    private fun handleUpload(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull()
        if (name.isNullOrEmpty() || declared == null) {
            return json(Response.Status.BAD_REQUEST, """{"ok":false,"reason":"缺少 name 或 content-length 参数"}""")
        }
        val entered = activeUploads.incrementAndGet()
        onActiveUploadsChanged(entered)
        return try {
            val outcome = engine.store(name, declared, session.inputStream)
            // 无论成功与否都把本请求体剩余字节读干净，保证 keep-alive 连接状态一致
            drain(session.inputStream, declared - outcome.consumed)
            when (outcome) {
                is WifiImportEngine.StoreOutcome.Stored -> {
                    val imp = outcome.imported?.let { importJson(it) } ?: "null"
                    json(Response.Status.OK, """{"ok":true,"bytes":${outcome.bytes},"import":$imp}""")
                }
                is WifiImportEngine.StoreOutcome.Rejected ->
                    json(Response.Status.BAD_REQUEST, """{"ok":false,"reason":${jstr(outcome.reason)}}""")
                is WifiImportEngine.StoreOutcome.StoreError ->
                    json(Response.Status.INTERNAL_ERROR, """{"ok":false,"reason":${jstr(outcome.reason)}}""")
            }
        } catch (e: Exception) {
            json(Response.Status.INTERNAL_ERROR, """{"ok":false,"reason":${jstr("接收失败：${e.message}")}}""")
        } finally {
            onActiveUploadsChanged(activeUploads.decrementAndGet())
        }
    }

    private fun handleFinish(): Response {
        val report = engine.finish()
        val imported = report.imported.joinToString(",") { importJson(it) }
        val incomplete = report.incomplete.joinToString(",") {
            """{"name":${jstr(it.name)},"reason":${jstr(it.reason)}}"""
        }
        return json(Response.Status.OK, """{"imported":[$imported],"incomplete":[$incomplete]}""")
    }

    private fun importJson(rec: ImportRecord): String =
        """{"base":${jstr(rec.base)},"bookName":${jstr(rec.bookName)},"ok":${rec.ok},""" +
            """"reason":${rec.reason?.let { jstr(it) } ?: "null"}}"""

    private fun json(status: Response.IStatus, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    /** 最多丢弃 [bytes] 字节，使连接回到下一个请求的边界。 */
    private fun drain(input: InputStream, bytes: Long) {
        if (bytes <= 0) return
        val buf = ByteArray(DRAIN_BUFFER)
        var remaining = bytes
        try {
            while (remaining > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                remaining -= n
            }
        } catch (ignored: Exception) {
            // 客户端已断开时排空失败无所谓，后续请求自然失败
        }
    }

    private fun jstr(s: String): String {
        val sb = StringBuilder(s.length + 8).append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append("\\u").append(String.format("%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }

    private companion object {
        const val DRAIN_BUFFER = 64 * 1024
    }
}
