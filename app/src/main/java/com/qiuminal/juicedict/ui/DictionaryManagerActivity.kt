package com.qiuminal.juicedict.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.qiuminal.juicedict.App
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.data.DictionaryInfo
import com.qiuminal.juicedict.databinding.ActivityManagerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DictionaryManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityManagerBinding
    private val repo get() = (application as App).repository

    private val adapter = DictionaryAdapter(
        onToggle = { info, enabled ->
            repo.setEnabled(info.id, enabled)
            refresh()
        },
        onDelete = { info -> confirmDelete(info) },
    )

    private val pickTree =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                lifecycleScope.launch {
                    val report = withContext(Dispatchers.IO) { repo.importFromTree(uri) }
                    Toast.makeText(
                        this@DictionaryManagerActivity,
                        report.message,
                        if (report.imported > 0) Toast.LENGTH_SHORT else Toast.LENGTH_LONG,
                    ).show()
                    // 新导入的词典立即在后台建立预建索引，后续查询不再等解析。
                    if (report.importedIds.isNotEmpty()) {
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                report.importedIds.forEach { repo.prewarm(it) }
                            }
                        }
                    }
                    refresh()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.dictList.layoutManager = LinearLayoutManager(this)
        binding.dictList.adapter = adapter
        binding.importButton.setOnClickListener {
            pickTree.launch(null)
        }
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) { repo.listDictionaries() }
            adapter.submitList(list)
            binding.emptyView.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDelete(info: DictionaryInfo) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_msg, info.bookName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_dict) { _, _ ->
                repo.delete(info.id)
                refresh()
            }
            .show()
    }
}
