package com.notifyhook.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifyhook.app.databinding.ItemAppSelectBinding
import com.notifyhook.app.model.AppInfo

class AppSelectAdapter(
    private val selectedPackages: MutableSet<String>
) : ListAdapter<AppInfo, AppSelectAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppInfo>() {
            override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean {
                return oldItem.packageName == newItem.packageName &&
                        oldItem.appName == newItem.appName
            }
        }
    }

    private var allApps: List<AppInfo> = emptyList()

    inner class ViewHolder(private val binding: ItemAppSelectBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(appInfo: AppInfo) {
            binding.tvAppName.text = appInfo.appName
            binding.ivAppIcon.setImageDrawable(
                appInfo.icon ?: ContextCompat.getDrawable(
                    binding.root.context,
                    android.R.drawable.sym_def_app_icon
                )
            )

            binding.cbSelected.setOnCheckedChangeListener(null)
            binding.cbSelected.isChecked = appInfo.packageName in selectedPackages

            binding.cbSelected.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedPackages.add(appInfo.packageName)
                } else {
                    selectedPackages.remove(appInfo.packageName)
                }
            }

            binding.root.setOnClickListener {
                binding.cbSelected.isChecked = !binding.cbSelected.isChecked
            }
        }
    }

    fun setApps(apps: List<AppInfo>, onListUpdated: (() -> Unit)? = null) {
        allApps = apps
        submitList(apps.toList()) {
            onListUpdated?.invoke()
        }
    }

    fun filter(query: String, onListUpdated: (() -> Unit)? = null) {
        val filtered = if (query.isBlank()) {
            allApps
        } else {
            allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                        it.packageName.contains(query, ignoreCase = true)
            }
        }
        submitList(filtered.toList()) {
            onListUpdated?.invoke()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}
