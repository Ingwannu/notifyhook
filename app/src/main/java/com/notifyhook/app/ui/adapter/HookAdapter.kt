package com.notifyhook.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifyhook.app.R
import com.notifyhook.app.databinding.ItemHookBinding
import com.notifyhook.app.model.Hook

class HookAdapter(
    private val onEdit: (Hook) -> Unit,
    private val onDelete: (Hook) -> Unit,
    private val onToggle: (Hook, Boolean) -> Unit,
    private val onMore: (Hook, View) -> Unit
) : ListAdapter<Hook, HookAdapter.ViewHolder>(HookDiffCallback()) {

    inner class ViewHolder(private val binding: ItemHookBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(hook: Hook) {
            val context = binding.root.context

            binding.tvHookName.text = hook.name.ifEmpty {
                context.getString(R.string.hook_name_unnamed)
            }
            binding.tvMethod.text = hook.method.name
            binding.tvUrl.text = hook.url.ifEmpty {
                context.getString(R.string.hook_url_unset)
            }

            val appCount = hook.targetPackages.size
            binding.tvTargetApps.text = if (appCount == 0) {
                context.getString(R.string.target_apps_all)
            } else {
                context.getString(R.string.target_apps_selected_count, appCount)
            }

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = hook.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggle(hook, isChecked)
            }

            binding.btnEdit.setOnClickListener { onEdit(hook) }
            binding.btnDelete.setOnClickListener { onDelete(hook) }
            binding.btnMore.setOnClickListener { onMore(hook, it) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHookBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class HookDiffCallback : DiffUtil.ItemCallback<Hook>() {
        override fun areItemsTheSame(oldItem: Hook, newItem: Hook) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Hook, newItem: Hook) = oldItem == newItem
    }
}
