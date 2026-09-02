package com.qiuminal.juicedict

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 最新 Release 信息。 */
data class ReleaseInfo(val versionName: String, val apkUrl: String)

/**
 * 轻量更新器（复刻虎助手 AppUpdater）：查询 GitHub Releases 最新版 -> 下载 APK -> 拉起系统安装器。
 * 数据源：https://api.github.com/repos/qiuminal/juicedict/releases/latest
 * 仓库发布到 GitHub 前接口会返回 404，此时视为“无更新”，不影响正常使用。
 */
object AppUpdater {

    private const val REPO_API = "https://api.github.com/repos/qiuminal/juicedict/releases/latest"
    private const val USER_AGENT = "juicedict-android"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 后台查询最新 Release；无网络 / 无 Release / 解析失败时回调 null。 */
    fun checkLatest(onResult: (ReleaseInfo?) -> Unit) {
        Thread {
            var info: ReleaseInfo? = null
            try {
                val conn = URL(REPO_API).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name", "").removePrefix("v")
                    var apkUrl: String? = null
                    val assets = json.optJSONArray("assets")
                    if (assets != null) {
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.optString("name").endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url")
                                break
                            }
                        }
                    }
                    if (tag.isNotEmpty() && apkUrl != null) {
                        info = ReleaseInfo(tag, apkUrl)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            mainHandler.post { onResult(info) }
        }.start()
    }

    /** 语义化版本比较：latest 是否比 current 新。 */
    fun isNewer(latest: String?, current: String?): Boolean {
        if (latest.isNullOrBlank() || current.isNullOrBlank()) {
            return false
        }
        val a = latest.trim().split('.').mapNotNull { it.toIntOrNull() }
        val b = current.trim().split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) {
                return x > y
            }
        }
        return false
    }

    /**
     * 后台下载 APK 到 cacheDir/updates/，完成后拉起系统安装器。
     * onProgress / onDone 都在主线程回调。
     */
    fun downloadAndInstall(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit,
        onDone: (Boolean, String?) -> Unit
    ) {
        Thread {
            var ok = false
            var error: String? = null
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val target = File(dir, "juicedict-latest.apk")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                if (conn.responseCode != 200) {
                    error = "HTTP ${conn.responseCode}"
                } else {
                    val total = conn.contentLength
                    val input = conn.inputStream
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(8192)
                        var done = 0L
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val percent = (done * 100 / total).toInt()
                                mainHandler.post { onProgress(percent.coerceIn(0, 100)) }
                            }
                        }
                    }
                    input.close()
                    ok = target.exists() && target.length() > 0L
                    if (ok) {
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            target
                        )
                        val intent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        mainHandler.post {
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                error = "无法打开安装器：${e.message}"
                                onDone(false, error)
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                error = e.message
            }
            if (ok) {
                mainHandler.post { onDone(true, null) }
            } else if (error != null) {
                mainHandler.post { onDone(false, error) }
            }
        }.start()
    }
}