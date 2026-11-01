package com.notifyhook.app.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.notifyhook.app.R
import com.notifyhook.app.databinding.ItemLogBinding
import com.notifyhook.app.model.HookLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogAdapter(
    private val onLogClick: (HookLog) -> Unit,
    private val onLogLongClick: (HookLog) -> Unit
) : ListAdapter<HookLog, LogAdapter.ViewHolder>(LogDiffCallback()) {

    inner class ViewHolder(private val binding: ItemLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val timeFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())

        fun bind(log: HookLog) {
            val context = binding.root.context
            binding.tvLogHookName.text = log.hookName
            binding.tvLogTime.text = timeFormat.format(Date(log.timestamp))

            val notification = buildString {
                if (log.notificationApp.isNotEmpty()) append("[${log.notificationApp}] ")
                if (log.notificationTitle.isNotEmpty()) append(log.notificationTitle)
                if (log.notificationText.isNotEmpty()) append(": ${log.notificationText}")
            }
            binding.tvLogNotification.text = notification.ifEmpty {
                context.getString(R.string.log_placeholder)
            }

            binding.tvLogMethod.text = log.requestMethod

            val isSuccess = log.success
            val statusTextRes = if (isSuccess) R.string.status_success else R.string.status_failure
            val statusColor = context.getColor(if (isSuccess) R.color.success else R.color.error)
            val statusText = context.getString(statusTextRes)

            binding.statusDot.setBackgroundResource(R.drawable.bg_status_dot)
            binding.statusDot.background.setTint(statusColor)

            binding.tvStatusIcon.text = if (isSuccess) "✓" else "✗"
            binding.tvStatusIcon.setTextColor(statusColor)
            binding.tvStatusText.setText(statusTextRes)
            binding.tvStatusText.setTextColor(statusColor)

            val statusDescription = context.getString(R.string.log_status_content_description, statusText)
            binding.tvStatusIcon.contentDescription = statusDescription
            binding.layoutStatusIndicator.contentDescription = statusDescription

            val statusDetail = if (log.responseCode > 0) {
                log.responseCode.toString()
            } else {
                log.errorMessage.take(30).ifEmpty { statusText }
            }
            binding.tvLogStatus.text = statusDetail
            binding.tvLogStatus.setTextColor(statusColor)
            binding.tvLogStatus.contentDescription = context.getString(
                R.string.log_status_detail_content_description,
                statusText,
                statusDetail
            )

            binding.tvLogUrl.text = log.requestUrl

            itemView.setOnClickListener {
                onLogClick(log)
            }
            itemView.setOnLongClickListener {
                onLogLongClick(log)
                true
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLogBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogDiffCallback : DiffUtil.ItemCallback<HookLog>() {
        override fun areItemsTheSame(oldItem: HookLog, newItem: HookLog) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: HookLog, newItem: HookLog) = oldItem == newItem
    }
}
