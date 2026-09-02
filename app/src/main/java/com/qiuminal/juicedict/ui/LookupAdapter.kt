package com.qiuminal.juicedict.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.qiuminal.juicedict.R
import com.qiuminal.juicedict.data.LookupItem
import com.qiuminal.juicedict.databinding.ItemLookupBinding

class LookupAdapter(private val onClick: (LookupItem) -> Unit) :
    ListAdapter<LookupItem, LookupAdapter.ViewHolder>(DIFF) {

    class ViewHolder(val binding: ItemLookupBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemLookupBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        val b = holder.binding
        b.word.text = item.word
        b.dictName.text = item.dictName
        b.preview.text = item.preview
        b.root.setOnClickListener { onClick(item) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<LookupItem>() {
            override fun areItemsTheSame(oldItem: LookupItem, newItem: LookupItem): Boolean =
                oldItem.dictId == newItem.dictId && oldItem.word == newItem.word && oldItem.offset == newItem.offset

            override fun areContentsTheSame(oldItem: LookupItem, newItem: LookupItem): Boolean =
                oldItem == newItem
        }
    }
}
