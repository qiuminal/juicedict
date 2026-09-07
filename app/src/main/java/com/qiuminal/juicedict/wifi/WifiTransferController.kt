package com.qiuminal.juicedict.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import com.qiuminal.juicedict.App
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Wi-Fi 传输的全局控制器（进程内单例）。
 *
 * 生命周期与前台服务绑定：[WifiTransferService] 启动时调用 [startAsync]，
 * 销毁时调用 [stop]。页面退出不停止服务（保活），用户经通知栏「停止」按钮
 * 或页面上的「停止服务」结束会话。
 *
 * 保活措施：
 * - 前台服务（dataSync 类型）保证进程不被回收；
 * - WifiLock：会话期间保持 Wi-Fi 电台不休眠（息屏传大文件更稳）；
 * - WakeLock（partial）：仅在确实有上传进行时短暂持有。
 */
object WifiTransferController {

    const val DEFAULT_PORT = 7800
    private const val SOCKET_TIMEOUT_MS = 20_000
    private const val WAKE_LOCK_TIMEOUT_MS = 30 * 60_000L

    /** 页面与通知栏共同消费的状态。 */
    sealed class UiState {
        data object Stopped : UiState()
        data object Starting : UiState()
        data class Running(val display: String, val url: String) : UiState()
        data class Failed(val reason: String) : UiState()
    }

    private var server: TransferServer? = null
    private var appContext: Context? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wifi-transfer-ctrl").apply { isDaemon = true }
    }

    /** 预热独立线程池：大词典建索引可达数秒，不应阻塞服务的启停控制。 */
    private val prewarmExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "wifi-prewarm").apply { isDaemon = true }
    }

    private val _state = MutableStateFlow<UiState>(UiState.Stopped)
    val state: StateFlow<UiState> = _state

    private val _events = MutableStateFlow<List<TransferEvent>>(emptyList())
    val events: StateFlow<List<TransferEvent>> = _events

    val isRunning: Boolean
        get() = server?.isAlive == true

    /** 幂等启动；实际工作在后台线程完成，结果经 [state] 发布。 */
    fun startAsync(context: Context) {
        if (isRunning) return
        val app = context.applicationContext
        appContext = app
        _state.value = UiState.Starting
        executor.execute {
            if (isRunning) return@execute // 与并发调用去重
            try {
                startLocked(app)
            } catch (e: Exception) {
                _state.value = UiState.Failed(e.message ?: "启动失败")
            }
        }
    }

    private fun startLocked(app: Context) {
        val ip = bestLanAddress()
        if (ip == null) {
            _state.value = UiState.Failed("未找到可用的局域网地址，请确认手机已连接 Wi-Fi")
            return
        }
        val engine = WifiImportEngine(
            stagingRoot = File(app.filesDir, "wifi_import"),
            listener = ::onEngineEvent,
        ) { base, files -> installDictionary(app, base, files) }
        engine.reset()
        val page = app.assets.open("wifi/import.html").use { it.readBytes() }
        val srv = try {
            TransferServer(DEFAULT_PORT, page, engine, ::onActiveUploads).also {
                it.start(SOCKET_TIMEOUT_MS)
            }
        } catch (e: IOException) {
            // 7800 被占用时退回系统随机端口
            TransferServer(0, page, engine, ::onActiveUploads).also { it.start(SOCKET_TIMEOUT_MS) }
        }
        server = srv
        acquireSessionLocks(app)
        val port = srv.listeningPort
        _state.value = UiState.Running(display = "$ip:$port", url = "http://$ip:$port")
    }

    /** 停止服务并释放全部资源。 */
    fun stop() {
        executor.execute {
            server?.let { runCatching { it.stop() } }
            server = null
            releaseLocks()
            _state.value = UiState.Stopped
        }
    }

    private fun installDictionary(app: Context, base: String, files: Map<String, File>): InstallResult {
        val repo = (app as? App)?.repository
            ?: return InstallResult(false, null, "应用上下文不可用")
        val status = repo.installFromFiles(base, files)
        if (status.ok) {
            // 后台预热（建 .jidx），不阻塞 HTTP 响应
            prewarmExecutor.execute { runCatching { repo.prewarm(base) } }
        }
        return InstallResult(status.ok, status.bookName, status.error)
    }

    private fun onEngineEvent(event: TransferEvent) {
        _events.update { (it + event).takeLast(EVENT_LIMIT) }
    }

    private fun onActiveUploads(count: Int) {
        val app = appContext ?: return
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (count > 0) {
            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "juicedict:wifitransfer").apply {
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            }
        } else {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    private fun acquireSessionLocks(app: Context) {
        val wm = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION") // WIFI_MODE_FULL 在 API 24-28 上是唯一可靠取值
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL, "juicedict:wifitransfer")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        onActiveUploads(0) // 复位 WakeLock 状态
    }

    private fun releaseLocks() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /**
     * 取最适合展示给电脑的局域网 IPv4：优先无线网卡（wlan*），其次有线/USB；
     * 排除蜂窝网卡（rmnet/ccmni，其地址对电脑侧无意义）。
     */
    private fun bestLanAddress(): String? {
        var fallback: String? = null
        return try {
            val nets = NetworkInterface.getNetworkInterfaces() ?: return null
            for (ni in nets) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue
                for (addr in ni.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue
                        if (name.startsWith("wlan")) return host
                        if (fallback == null && (name.startsWith("eth") || name.startsWith("usb"))) {
                            fallback = host
                        }
                    }
                }
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    private const val EVENT_LIMIT = 50
}
