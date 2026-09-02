package com.qiuminal.juicedict.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.data.DictionaryInfo
import com.qiuminal.juicedict.databinding.ItemDictionaryBinding

class DictionaryAdapter(
    private val onToggle: (DictionaryInfo, Boolean) -> Unit,
    private val onDelete: (DictionaryInfo) -> Unit,
) : ListAdapter<DictionaryInfo, DictionaryAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemDictionaryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemDictionaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = getItem(position)
        val b = holder.binding
        val ctx = b.root.context
        b.bookName.text = info.bookName
        b.metaText.text = ctx.getString(R.string.word_count, info.wordCount)
        b.statusText.text = ctx.getString(if (info.bundled) R.string.bundled_badge else R.string.imported_badge)
        b.enabledSwitch.isChecked = info.enabled
        b.enabledSwitch.setOnCheckedChangeListener { _, checked -> onToggle(info, checked) }
        b.deleteButton.setOnClickListener { onDelete(info) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<DictionaryInfo>() {
            override fun areItemsTheSame(oldItem: DictionaryInfo, newItem: DictionaryInfo): Boolean =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: DictionaryInfo, newItem: DictionaryInfo): Boolean =
                oldItem == newItem
        }
    }
}
