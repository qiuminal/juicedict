package com.qiuminal.juicedict

import android.app.Application
import android.util.Log
import com.qiuminal.juicedict.data.DictionaryRepository
import com.qiuminal.juicedict.data.LookupEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var repository: DictionaryRepository
        private set
    lateinit var lookupEngine: LookupEngine
        private set

    /**
     * 后台任务的最后防线：预热/内置词库安装等任何未捕获异常只记日志，
     * 绝不允许把进程带崩（用户词典损坏、超大词典 OOM 等场景）。
     */
    private val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e ->
                Log.e("JuiceDict", "uncaught error in app scope", e)
            }
    )

    override fun onCreate() {
        super.onCreate()
        repository = DictionaryRepository(this)
        lookupEngine = LookupEngine(repository)
        // Install the bundled dictionary in the background.
        appScope.launch {
            runCatching { repository.ensureBundledDict() }
                .onFailure { Log.e("JuiceDict", "ensureBundledDict failed", it) }
            // 内置词库就位后后台建索引（首次）或直接载入预建缓存，避免首次查询转圈。
            repository.prewarmAll()
        }
    }
}
