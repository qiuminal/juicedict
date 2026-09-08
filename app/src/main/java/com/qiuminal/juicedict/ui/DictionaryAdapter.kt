package com.qiuminal.juicedict.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.data.DictionaryInfo
import com.qiuminal.juicedict.databinding.ItemDictionaryBinding

/** 词典管理列表；显示顺序由拖动手柄调整并由 Activity 持久化。 */
class DictionaryAdapter(
    private val onToggle: (DictionaryInfo, Boolean) -> Unit,
    private val onDelete: (DictionaryInfo) -> Unit,
    private val onStartDrag: (ViewHolder) -> Unit,
) : RecyclerView.Adapter<DictionaryAdapter.ViewHolder>() {

    private val items = ArrayList<DictionaryInfo>()

    class ViewHolder(val binding: ItemDictionaryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemDictionaryBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = items[position]
        val b = holder.binding
        val ctx = b.root.context
        b.bookName.text = info.bookName
        b.metaText.text = ctx.getString(R.string.word_count, info.wordCount)
        b.statusText.text = ctx.getString(if (info.bundled) R.string.bundled_badge else R.string.imported_badge)
        b.enabledSwitch.setOnCheckedChangeListener(null)
        b.enabledSwitch.isChecked = info.enabled
        b.enabledSwitch.setOnCheckedChangeListener { _, checked -> onToggle(info, checked) }
        b.deleteButton.setOnClickListener { onDelete(info) }
        b.dragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) onStartDrag(holder)
            false
        }
    }

    override fun getItemCount(): Int = items.size

    fun submitItems(newItems: List<DictionaryInfo>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun moveItem(from: Int, to: Int) {
        if (from !in items.indices || to !in items.indices) return
        java.util.Collections.swap(items, from, to)
        notifyItemMoved(from, to)
    }

    fun itemIds(): List<String> = items.map { it.id }
}
