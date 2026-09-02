package com.qiuminal.juicedict

import android.app.Application
import com.qiuminal.juicedict.data.DictionaryRepository
import com.qiuminal.juicedict.data.LookupEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    lateinit var repository: DictionaryRepository
        private set
    lateinit var lookupEngine: LookupEngine
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repository = DictionaryRepository(this)
        lookupEngine = LookupEngine(repository)
        // Install the bundled dictionary in the background.
        appScope.launch {
            repository.ensureBundledDict()
            // 内置词库就位后后台建索引（首次）或直接载入预建缓存，避免首次查询转圈。
            repository.prewarmAll()
        }
    }
}
