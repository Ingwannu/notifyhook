package com.notifyhook.app.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyhook.app.R
import com.notifyhook.app.data.HookStorage
import com.notifyhook.app.databinding.FragmentHooksBinding
import com.notifyhook.app.model.Hook
import com.notifyhook.app.model.HttpHeaderMasking
import com.notifyhook.app.service.DeliveryWorkScheduler
import com.notifyhook.app.service.ForegroundService
import com.notifyhook.app.ui.adapter.HookAdapter
import com.notifyhook.app.model.BodySourceType

class HooksFragment : Fragment() {

    private var _binding: FragmentHooksBinding? = null
    private val binding get() = _binding!!
    private lateinit var storage: HookStorage
    private lateinit var adapter: HookAdapter
    private var pendingExportHook: Hook? = null
    private var pendingExportIncludesSecrets = false

    private val exportPresetLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val hookToExport = pendingExportHook
        pendingExportHook = null

        if (uri == null || hookToExport == null) {
            return@registerForActivityResult
        }

        exportHookToUri(hookToExport, uri, pendingExportIncludesSecrets)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHooksBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        storage = HookStorage.getInstance(requireContext())

        setupServiceStatusCard()

        adapter = HookAdapter(
            onEdit = { hook ->
                val intent = Intent(requireContext(), HookEditActivity::class.java).apply {
                    putExtra("hook_id", hook.id)
                }
                startActivity(intent)
            },
            onDelete = { hook ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.delete_hook_title)
                    .setMessage(getString(R.string.delete_hook_message, hook.name))
                    .setPositiveButton(R.string.delete) { _, _ ->
                        if (!storage.deleteHook(hook.id)) {
                            Toast.makeText(requireContext(), R.string.hook_delete_failed, Toast.LENGTH_SHORT).show()
                            return@setPositiveButton
                        }
                        releaseBodyFilePermissionIfUnused(hook)
                        DeliveryWorkScheduler.cancelIfNoPending(requireContext())
                        DeliveryWorkScheduler.enqueueIfPending(requireContext())
                        ForegroundService.startIfNeeded(requireContext())
                        loadHooks()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            },
            onToggle = onToggle@{ hook, enabled ->
                hook.enabled = enabled
                if (!storage.saveHook(hook)) {
                    hook.enabled = !enabled
                    Toast.makeText(requireContext(), R.string.hook_save_failed, Toast.LENGTH_SHORT).show()
                    loadHooks()
                    return@onToggle
                }
                ForegroundService.startIfNeeded(requireContext())
            },
            onMore = { hook, anchor ->
                showHookMenu(hook, anchor)
            }
        )

        binding.recyclerHooks.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerHooks.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadHooks()
        updateServiceStatus()
    }

    private fun setupServiceStatusCard() {
        binding.btnRestartService.setOnClickListener {
            ForegroundService.stop(requireContext())
            ForegroundService.startIfNeeded(requireContext())
            Toast.makeText(requireContext(), R.string.service_restarted, Toast.LENGTH_SHORT).show()
            updateServiceStatus()
        }
    }

    private fun updateServiceStatus() {
        val running = isServiceRunning()
        binding.tvServiceStatus.setText(
            if (running) R.string.service_running else R.string.service_stopped
        )
        val dot = binding.viewServiceStatus.background
        dot.setTint(
            resources.getColor(
                if (running) R.color.success else R.color.error,
                requireContext().theme
            )
        )
    }

    private fun isServiceRunning(): Boolean {
        val am = requireContext().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        for (service in am.getRunningServices(Int.MAX_VALUE)) {
            if (ForegroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun loadHooks() {
        val hooks = storage.getHooks()
        adapter.submitList(hooks)
        binding.tvEmpty.visibility = if (hooks.isEmpty()) View.VISIBLE else View.GONE
        binding.recyclerHooks.visibility = if (hooks.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun releaseBodyFilePermissionIfUnused(hook: Hook) {
        val uriString = hook.bodyFilePath
        if (hook.bodySourceType != BodySourceType.FILE || uriString.isNullOrBlank()) {
            return
        }
        if (storage.isBodyFileReferenced(uriString, hook.id)) {
            return
        }
        runCatching {
            requireContext().contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    fun refreshHooks() {
        if (_binding != null) {
            loadHooks()
        }
    }

    private fun showHookMenu(hook: Hook, anchor: View) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.menu_hook_item, menu)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_export_preset) {
                    startPresetExport(hook)
                    true
                } else {
                    false
                }
            }
            show()
        }
    }

    private fun startPresetExport(hook: Hook) {
        val latestHook = storage.getHook(hook.id) ?: hook
        val hasSecretValues = latestHook.headers.any {
            HttpHeaderMasking.shouldMask(it) && it.value.isNotBlank()
        }
        if (!hasSecretValues) {
            launchPresetExport(latestHook, includeSecrets = false)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.preset_export_secrets_title)
            .setMessage(R.string.preset_export_secrets_message)
            .setPositiveButton(R.string.preset_export_without_secrets) { _, _ ->
                launchPresetExport(latestHook, includeSecrets = false)
            }
            .setNeutralButton(R.string.preset_export_with_secrets) { _, _ ->
                launchPresetExport(latestHook, includeSecrets = true)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchPresetExport(hook: Hook, includeSecrets: Boolean) {
        pendingExportHook = hook
        pendingExportIncludesSecrets = includeSecrets
        exportPresetLauncher.launch(buildPresetFileName(hook))
    }

    private fun buildPresetFileName(hook: Hook): String {
        val baseName = hook.name.ifBlank {
            getString(R.string.hook_name_unnamed)
        }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$baseName.notif"
    }

    private fun exportHookToUri(hook: Hook, uri: Uri, includeSecrets: Boolean) {
        val success = runCatching {
            val json = storage.createPresetJson(hook, includeSensitiveHeaderValues = includeSecrets)
            requireContext().contentResolver.openOutputStream(uri)?.use { output ->
                output.bufferedWriter().use { writer ->
                    writer.write(json)
                }
            } ?: error("Unable to open output stream")
        }.isSuccess

        val messageResId = if (success) {
            R.string.preset_export_success
        } else {
            R.string.preset_export_failed
        }
        Toast.makeText(requireContext(), messageResId, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
