package com.qiuminal.juicedict.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import java.util.Locale
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.qiuminal.juicedict.App
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.data.LookupItem
import com.qiuminal.juicedict.data.MatchRank
import com.qiuminal.juicedict.data.SharedTextParser
import com.qiuminal.juicedict.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val engine get() = (application as App).lookupEngine

    private val adapter = LookupAdapter { item -> showDetail(item) }
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var lastQuery: String? = null
    /** 当前详情对应的候选（词库互见链接跳转优先同一本词典）。 */
    private var currentItem: LookupItem? = null
    /** 程序性 setText（互见跳转 / 反查）时不触发输入框自动查询。 */
    private var suppressAutoSearch = false
    /** 系统 TTS：朗读详情页词条标题（不内置任何引擎/资源）。 */
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeak: String? = null
    private val ttsHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.drawerLayout) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.contentRoot.updatePadding(top = bars.top, bottom = bars.bottom)
            binding.navView.updatePadding(top = bars.top)
            insets
        }

        // 三横杠：打开左侧侧边菜单（首页 / 关于）
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_about -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_manage) {
                startActivity(Intent(this, DictionaryManagerActivity::class.java))
                true
            } else false
        }

        binding.resultList.layoutManager = LinearLayoutManager(this)
        binding.resultList.adapter = adapter

        binding.copyButton.setOnClickListener { copyDetail() }
        binding.shareButton.setOnClickListener { shareDetail() }
        binding.content.onWordTap = { word -> jumpToWord(word) }
        binding.content.onReverseLookup = { word -> queryInListMode(word) }

        // 详情区空白（含释义下方滚动区）点按即收起系统选中态；长按选词与菜单由
        // SelectableLinkTextView 走系统原生 ActionMode，此处的空白点击监听只是
        // 内容区之外的兜底。
        binding.detailView.setOnClickListener { binding.content.dismissSelection() }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var scrollDownX = 0f
        var scrollDownY = 0f
        binding.detailScroll.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    scrollDownX = event.x
                    scrollDownY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - scrollDownX
                    val dy = event.y - scrollDownY
                    if (dx * dx + dy * dy <= touchSlop * touchSlop) {
                        binding.content.dismissSelection()
                    }
                }
                else -> {}
            }
            false
        }

        // 朗读词条标题：走系统 TextToSpeech，轻量、零内置引擎
        binding.speakButton.setOnClickListener { speakTitle() }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (suppressAutoSearch) return
                val text = s?.toString().orEmpty()
                if (text.isEmpty()) {
                    // 点击搜索框清除按钮（×）：清空输入、收起详情，回到初始状态
                    hideDetailAndReset()
                } else if (binding.detailView.visibility == View.VISIBLE) {
                    // 详情打开时继续输入：收起详情、回到候选列表
                    showListMode()
                    // 详情页里回删文字属于发起新查询：先把候选列表拉回顶部，
                    // 避免屏幕焦点仍停留在上一个词条的位置。
                    binding.resultList.scrollToPosition(0)
                }
                scheduleSearch()
            }
        })

        // 返回键：抽屉开着先关抽屉；详情打开时回到候选列表（保留当前查询）
        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.drawerLayout.isDrawerOpen(GravityCompat.START) ->
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                binding.detailView.visibility == View.VISIBLE -> showListMode()
                else -> finish()
            }
        }

        // 外部软件（阅读器）经系统分享面板分享文本：直接切到候选列表查询
        handleSharedText(intent)
    }

    /** 系统分享（ACTION_SEND）把阅读器选中的文本发来：拉到前台并查询。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSharedText(intent)
    }

    private fun handleSharedText(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val query = SharedTextParser.extract(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (query.isNotEmpty()) queryInListMode(query)
    }

    /** 点击详情页 Speaker：朗读词条标题本身（不做正文朗读）。 */
    private fun speakTitle() {
        val word = binding.wordTitle.text?.toString()?.trim().orEmpty()
        if (word.isEmpty()) return
        pendingSpeak = word
        if (tts == null) {
            initTts()
        } else if (ttsReady) {
            speakPending()
        }
    }

    /**
     * 依次尝试系统 TTS 引擎：先是系统“默认引擎”（尊重用户设置）。若默认引擎
     * 初始化失败（国行机常见：默认项指向未安装/被禁用的包，如 Google TTS），
     * 再逐个尝试系统里其它已装引擎（厂商内置离线语音），全部失败才提示。
     */
    private fun initTts() {
        if (tts != null) return
        val candidates = ArrayList<String?>()
        candidates.add(null) // null = 系统默认引擎
        for (enginePackage in enumerateTtsEngines()) {
            if (!candidates.contains(enginePackage)) candidates.add(enginePackage)
        }
        tryNextTtsEngine(candidates, 0)
    }

    private fun tryNextTtsEngine(candidates: List<String?>, index: Int) {
        if (index >= candidates.size) {
            ttsUnavailable()
            return
        }
        val enginePackage = candidates[index]
        Log.i(TTS_TAG, "init TTS engine=$enginePackage (${index + 1}/${candidates.size})")
        val listener = TextToSpeech.OnInitListener { status ->
            Log.i(TTS_TAG, "TTS init status=$status engine=$enginePackage")
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                speakPending()
            } else {
                tts?.shutdown()
                tts = null
                tryNextTtsEngine(candidates, index + 1)
            }
        }
        tts = if (enginePackage == null) {
            TextToSpeech(applicationContext, listener)
        } else {
            TextToSpeech(applicationContext, listener, enginePackage)
        }
    }

    /** 枚举系统已安装的全部 TTS 引擎（需清单 <queries> 声明以便 Android 11+ 可见）。 */
    private fun enumerateTtsEngines(): List<String> = runCatching {
        val pm = applicationContext.packageManager
        val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        pm.queryIntentServices(intent, 0)
            .mapNotNull { it.serviceInfo?.packageName }
            .distinct()
    }.getOrDefault(emptyList())

    /** 按词条语种挑一个系统有语音数据的语言；没有可用项返回 null（不拦朗读）。 */
    private fun bestLocale(engine: TextToSpeech, word: String): Locale? {
        val candidates = when {
            containsHan(word) -> listOf(
                Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE,
                Locale.CHINESE, Locale.getDefault(),
            )
            containsLatin(word) -> listOf(
                Locale.US, Locale.UK, Locale.ENGLISH, Locale.getDefault(),
            )
            else -> listOf(Locale.getDefault())
        }
        return candidates.firstOrNull { engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE }
    }

    private fun speakPending() {
        val word = pendingSpeak ?: return
        pendingSpeak = null
        val engine = tts ?: return
        // 尽力切到合适的语言；个别引擎的语音包对个别语种缺失时，
        // 切语言失败也不拦截，仍用引擎默认语音试读一次。
        bestLocale(engine, word)?.let { engine.language = it }
        if (!trySpeak(engine, word)) {
            // setLanguage 后语音数据是异步加载的，偶发“未就绪”失败，
            // 延迟重试一次再下结论，避免误报“暂无发音”。
            Log.i(TTS_TAG, "speak failed once, retrying: $word")
            ttsHandler.postDelayed({
                // 重试仍失败才提示；不销毁引擎，避免误伤后续朗读
                if (!trySpeak(engine, word)) showSpeakUnavailable()
            }, 300)
        }
    }

    private fun trySpeak(engine: TextToSpeech, word: String): Boolean {
        val result = runCatching {
            engine.speak(word, TextToSpeech.QUEUE_FLUSH, null, "juice_" + System.currentTimeMillis())
        }.getOrDefault(TextToSpeech.ERROR)
        Log.i(TTS_TAG, "speak result=$result word=$word")
        return result == TextToSpeech.SUCCESS
    }

    private fun ttsUnavailable() {
        tts?.shutdown()
        tts = null
        ttsReady = false
        pendingSpeak = null
        runOnUiThread { showSpeakUnavailable() }
    }
    /** 暂无发音：仅轻提示，避免挫败感。 */
    private fun showSpeakUnavailable() {
        Toast.makeText(this, R.string.speak_failed, Toast.LENGTH_SHORT).show()
    }

    /** 是否含汉字（含扩展区代理对）。 */
    private fun containsHan(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (cp in 0x3400..0x4DBF || cp in 0x4E00..0x9FFF ||
                cp in 0xF900..0xFAFF || cp in 0x20000..0x3FFFF
            ) return true
            i += Character.charCount(cp)
        }
        return false
    }

    private fun containsLatin(s: String): Boolean =
        s.any { it in 'A'..'Z' || it in 'a'..'z' }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            delay(300)
            runSearch()
        }
    }

    private fun runSearch() {
        val query = binding.searchInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            lastQuery = null
            binding.progress.visibility = View.GONE
            binding.emptyView.visibility = View.VISIBLE
            binding.emptyView.setText(R.string.empty_query_hint)
            adapter.submitList(emptyList())
            return
        }
        binding.progress.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        lifecycleScope.launch {
            val items = engine.lookup(query)
            if (binding.searchInput.text?.toString()?.trim().orEmpty() != query) return@launch // stale
            lastQuery = query
            binding.progress.visibility = View.GONE
            adapter.submitList(items)
            scrollResultsToTop()
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) binding.emptyView.setText(getString(R.string.no_result, query))
        }
    }

    /** 点击候选词：收起候选列表，原地展开词条详情。 */
    private fun showDetail(item: LookupItem) {
        currentItem = item
        binding.resultList.visibility = View.GONE
        binding.emptyView.visibility = View.GONE
        binding.progress.visibility = View.GONE
        binding.detailView.visibility = View.VISIBLE

        binding.wordTitle.text = item.word
        binding.dictName.text = item.dictName
        binding.content.text = getString(R.string.loading)

        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)

        detailJob?.cancel()
        detailJob = lifecycleScope.launch {
            val article = withContext(Dispatchers.Default) {
                engine.article(item.dictId, item.offset, item.size)
            }
            if (binding.detailView.visibility != View.VISIBLE) return@launch
            if (article == null) {
                binding.content.text = getString(R.string.not_loaded)
            } else {
                binding.content.text = HtmlCompat.fromHtml(
                    article.toHtml(),
                    HtmlCompat.FROM_HTML_MODE_LEGACY,
                )
            }
        }
    }

    /**
     * 详情内的词库交叉链接（如 chibigenc 蓝色互见词“篳輅”）：更新搜索框并
     * 跳转到该词条。优先同一本词典的精确命中，其次任意词典的精确命中；
     * 都没有精确命中时退化为普通候选列表。
     */
    private fun jumpToWord(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        val sourceDict = currentItem?.dictId

        suppressAutoSearch = true
        binding.searchInput.setText(trimmed)
        binding.searchInput.setSelection(binding.searchInput.text?.length ?: 0)
        suppressAutoSearch = false

        detailJob?.cancel()
        binding.detailView.visibility = View.GONE
        binding.resultList.visibility = View.VISIBLE
        binding.progress.visibility = View.VISIBLE
        binding.emptyView.visibility = View.GONE
        adapter.submitList(emptyList())

        lifecycleScope.launch {
            val items = engine.lookup(trimmed)
            if (binding.searchInput.text?.toString()?.trim() != trimmed) return@launch // stale
            val exact = items.firstOrNull { it.rank == MatchRank.EXACT && it.dictId == sourceDict }
                ?: items.firstOrNull { it.rank == MatchRank.EXACT }
            if (exact != null) {
                showDetail(exact)
            } else {
                lastQuery = trimmed
                binding.progress.visibility = View.GONE
                adapter.submitList(items)
                scrollResultsToTop()
                binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                if (items.isEmpty()) binding.emptyView.setText(getString(R.string.no_result, trimmed))
            }
        }
    }

    /** 选中文本反查：以所选内容发起一次新查询，回到候选列表。 */
    private fun queryInListMode(word: String) {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return
        suppressAutoSearch = true
        binding.searchInput.setText(trimmed)
        binding.searchInput.setSelection(binding.searchInput.text?.length ?: 0)
        suppressAutoSearch = false
        showListMode()
        adapter.submitList(emptyList())
        searchJob?.cancel()
        runSearch()
    }

    /** 从详情返回候选列表（保留当前查询）。 */
    private fun showListMode() {
        tts?.stop()
        detailJob?.cancel()
        binding.detailView.visibility = View.GONE
        binding.resultList.visibility = View.VISIBLE
    }

    /** 新查询结果落地后把候选列表拉回顶部（详情回删、互见跳转回列表等场景）。 */
    private fun scrollResultsToTop() {
        binding.resultList.scrollToPosition(0)
        // submitList 的差异计算是异步的，post 一次确保新列表应用后也在顶部。
        binding.resultList.post { binding.resultList.scrollToPosition(0) }
    }

    /** 清空输入（点 ×）：详情消失，回到初始空状态，可发起新查询。 */
    private fun hideDetailAndReset() {
        tts?.stop()
        detailJob?.cancel()
        lastQuery = null
        binding.detailView.visibility = View.GONE
        binding.resultList.visibility = View.VISIBLE
        binding.progress.visibility = View.GONE
        binding.emptyView.visibility = View.VISIBLE
        binding.emptyView.setText(R.string.empty_query_hint)
        adapter.submitList(emptyList())
    }

    private fun copyDetail() {
        val text = binding.content.text?.toString().orEmpty()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(binding.wordTitle.text?.toString(), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TTS_TAG = "JuiceDictTTS"
    }

    private fun shareDetail() {
        val text = binding.content.text?.toString().orEmpty()
        val word = binding.wordTitle.text?.toString().orEmpty()
        val dictName = binding.dictName.text?.toString().orEmpty()
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, word)
            putExtra(
                Intent.EXTRA_TEXT,
                "$word\n\n$text\n\n${getString(R.string.share_footer, dictName)}",
            )
        }
        startActivity(Intent.createChooser(send, word))
    }
}
