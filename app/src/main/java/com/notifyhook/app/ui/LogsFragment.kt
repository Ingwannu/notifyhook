package com.notifyhook.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyhook.app.R
import com.notifyhook.app.data.HookStorage
import com.notifyhook.app.databinding.FragmentLogsBinding
import com.notifyhook.app.model.HookLog
import com.notifyhook.app.ui.adapter.LogAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsFragment : Fragment() {

    private var _binding: FragmentLogsBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: HookStorage
    private lateinit var adapter: LogAdapter
    private val detailTimeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = HookStorage.getInstance(requireContext())

        adapter = LogAdapter(
            onLogClick = { log ->
                showLogDetailDialog(log)
            },
            onLogLongClick = { log ->
                copyLogSummary(log)
            }
        )
        binding.recyclerLogs.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLogs.adapter = adapter

        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menu.add(0, 1, 0, R.string.clear_log).apply {
                    setIcon(R.drawable.ic_delete_logs)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId == 1) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.clear_log)
                        .setMessage(R.string.clear_log_message)
                        .setPositiveButton(R.string.delete) { _, _ ->
                            storage.clearLogs()
                            loadLogs()
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                    return true
                }
                return false
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        loadLogs()
    }

    override fun onResume() {
        super.onResume()
        loadLogs()
    }

    private fun loadLogs() {
        val logs = storage.getLogs()
        adapter.submitList(logs)
        binding.tvEmptyLog.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerLogs.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun showLogDetailDialog(log: HookLog) {
        val context = requireContext()
        val detailText = buildLogDetailText(log)
        val padding = (16 * resources.displayMetrics.density).toInt()

        val detailTextView = TextView(context).apply {
            text = detailText
            setTextColor(context.getColor(R.color.on_surface))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.MONOSPACE
            setPadding(padding, padding, padding, padding)
        }

        val scrollView = ScrollView(context).apply {
            setBackgroundColor(context.getColor(R.color.surface))
            isFillViewport = true
            addView(
                detailTextView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.log_detail_title)
            .setView(scrollView)
            .setPositiveButton(R.string.log_copy) { _, _ ->
                copyTextToClipboard(getString(R.string.log_detail_title), detailText)
            }
            .setNegativeButton(R.string.log_close, null)
            .show()

        val width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
        val maxHeight = (resources.displayMetrics.heightPixels * 0.8f).toInt()
        dialog.window?.setLayout(width, maxHeight)
    }

    private fun buildLogDetailText(log: HookLog): String {
        val formattedTime = detailTimeFormat.format(Date(log.timestamp))
        val status = getString(if (log.success) R.string.status_success else R.string.status_failure)
        val responseCode = if (log.responseCode > 0) {
            log.responseCode.toString()
        } else {
            getString(R.string.log_placeholder)
        }

        return buildString {
            appendLine("📋 ${getString(R.string.log_detail_title)}")
            appendLine("━━━━━━━━━━━━━━━━━━━━")
            appendLine("${getString(R.string.log_detail_time)}: $formattedTime")
            appendLine("${getString(R.string.log_detail_status)}: $status")
            appendLine("${getString(R.string.log_detail_hook)}: ${detailValue(log.hookName, R.string.hook_name_unnamed)}")
            appendLine()
            appendLine("📱 알림 정보")
            appendLine("${getString(R.string.log_detail_app)}: ${detailValue(log.notificationApp)}")
            appendLine("${getString(R.string.log_detail_notification_title)}: ${detailValue(log.notificationTitle)}")
            appendLine("${getString(R.string.log_detail_notification_text)}: ${detailValue(log.notificationText)}")
            appendLine()
            appendLine("🌐 요청")
            appendLine("${getString(R.string.log_detail_url)}: ${detailValue(log.requestUrl)}")
            appendLine("${getString(R.string.log_detail_method)}: ${detailValue(log.requestMethod)}")
            if (log.requestHeaders.isNotBlank()) {
                appendLine("${getString(R.string.log_detail_request_headers)}:")
                appendLine(log.requestHeaders)
            }
            appendLine("${getString(R.string.log_detail_request_body)}:")
            appendLine(detailValue(log.requestBody))
            appendLine()
            appendLine("📨 응답")
            appendLine("${getString(R.string.log_detail_response_code)}: $responseCode")
            appendLine("${getString(R.string.log_detail_response_body)}:")
            appendLine(detailValue(log.responseBody))

            if (log.errorMessage.isNotBlank()) {
                appendLine()
                appendLine("❌ ${getString(R.string.log_detail_error)}: ${log.errorMessage}")
            }
        }.trimEnd()
    }

    private fun copyLogSummary(log: HookLog) {
        val status = getString(if (log.success) R.string.status_success else R.string.status_failure)
        val responseCode = if (log.responseCode > 0) {
            log.responseCode.toString()
        } else {
            getString(R.string.log_placeholder)
        }
        val summary = "[$status] ${detailValue(log.hookName, R.string.hook_name_unnamed)} - ${detailValue(log.notificationApp)}: ${detailValue(log.notificationTitle)} ($responseCode)"
        copyTextToClipboard(getString(R.string.log), summary)
    }

    private fun copyTextToClipboard(label: String, text: String) {
        val clipboard = requireContext().getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(requireContext(), R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun detailValue(value: String, fallbackResId: Int = R.string.log_placeholder): String {
        return value.ifBlank { getString(fallbackResId) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
