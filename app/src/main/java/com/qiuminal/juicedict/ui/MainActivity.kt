package com.qiuminal.juicedict.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
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

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val text = s?.toString().orEmpty()
                if (text.isEmpty()) {
                    // 点击搜索框清除按钮（×）：清空输入、收起详情，回到初始状态
                    hideDetailAndReset()
                } else if (binding.detailView.visibility == View.VISIBLE) {
                    // 详情打开时继续输入：收起详情、回到候选列表
                    showListMode()
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
            binding.emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            if (items.isEmpty()) binding.emptyView.setText(R.string.no_result)
        }
    }

    /** 点击候选词：收起候选列表，原地展开词条详情。 */
    private fun showDetail(item: LookupItem) {
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

    /** 从详情返回候选列表（保留当前查询）。 */
    private fun showListMode() {
        detailJob?.cancel()
        binding.detailView.visibility = View.GONE
        binding.resultList.visibility = View.VISIBLE
    }

    /** 清空输入（点 ×）：详情消失，回到初始空状态，可发起新查询。 */
    private fun hideDetailAndReset() {
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