package com.notifyhook.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator
import com.notifyhook.app.R
import com.notifyhook.app.data.HookStorage
import com.notifyhook.app.databinding.ActivityMainBinding
import com.notifyhook.app.model.HttpHeaderMasking
import com.notifyhook.app.service.DeliveryWorkScheduler
import com.notifyhook.app.service.ForegroundService
import com.notifyhook.app.service.NotificationListenerAccess
import com.notifyhook.app.util.TextReadLimits
import com.notifyhook.app.util.TextReadLimiter

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var storage: HookStorage
    private var tabLayoutMediator: TabLayoutMediator? = null
    private var pageChangeCallback: ViewPager2.OnPageChangeCallback? = null
    private var isPermissionBannerDismissed: Boolean = false

    private val importPresetLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { importPresetFromUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        storage = HookStorage.getInstance(this)

        setupTabs()
        setupFab()
        setupToolbarMenu()
        setupPermissionBanner()

        checkPermissions()
    }

    override fun onDestroy() {
        pageChangeCallback?.let { binding.viewPager.unregisterOnPageChangeCallback(it) }
        tabLayoutMediator?.detach()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        isPermissionBannerDismissed = false
        checkPermissions()
        ForegroundService.startIfNeeded(this)
        DeliveryWorkScheduler.enqueueIfPending(this)
    }

    private fun setupTabs() {
        binding.viewPager.adapter = MainPagerAdapter(this)
        binding.viewPager.offscreenPageLimit = 2
        tabLayoutMediator = TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) "훅" else "로그"
        }.apply {
            attach()
        }

        pageChangeCallback = object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateFabVisibility(position)
            }
        }
        binding.viewPager.registerOnPageChangeCallback(pageChangeCallback!!)
        updateFabVisibility(binding.viewPager.currentItem)
    }

    private fun setupFab() {
        binding.fabAdd.setOnClickListener {
            val intent = Intent(this, HookEditActivity::class.java)
            startActivity(intent)
        }
    }

    private fun setupToolbarMenu() {
        binding.toolbar.inflateMenu(R.menu.menu_main)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_import_preset) {
                importPresetLauncher.launch(arrayOf("*/*"))
                true
            } else {
                false
            }
        }
    }

    private fun setupPermissionBanner() {
        binding.btnPermissionSettings.setOnClickListener {
            openPermissionSettings()
        }
        binding.btnPermissionDismiss.setOnClickListener {
            isPermissionBannerDismissed = true
            hidePermissionBanner()
        }
    }

    private fun updateFabVisibility(position: Int) {
        if (position == 0) {
            binding.fabAdd.show()
        } else {
            binding.fabAdd.hide()
        }
    }

    private fun checkPermissions() {
        val notificationMissing = !isNotificationListenerEnabled()
        val batteryMissing = !isBatteryOptimizationDisabled()

        if (!notificationMissing && !batteryMissing) {
            hidePermissionBanner()
            return
        }

        if (isPermissionBannerDismissed) {
            hidePermissionBanner()
            return
        }

        val messageResId = when {
            notificationMissing && batteryMissing -> R.string.permission_banner_both
            notificationMissing -> R.string.permission_banner_notification
            else -> R.string.permission_banner_battery
        }
        binding.tvPermissionBanner.setText(messageResId)
        binding.cardPermissionBanner.visibility = View.VISIBLE
    }

    private fun isNotificationListenerEnabled(): Boolean {
        return NotificationListenerAccess.isEnabled(this)
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun openPermissionSettings() {
        when {
            !isNotificationListenerEnabled() -> {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }

            !isBatteryOptimizationDisabled() -> {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun hidePermissionBanner() {
        binding.cardPermissionBanner.visibility = View.GONE
    }

    private fun importPresetFromUri(uri: Uri) {
        val importedHook = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader().use { reader ->
                    storage.importPreset(
                        TextReadLimiter.read(reader, TextReadLimits.PRESET_FILE_MAX_CHARS)
                    )
                }
            }
        }.getOrNull()

        if (importedHook == null) {
            Toast.makeText(this, R.string.preset_import_failed, Toast.LENGTH_SHORT).show()
            return
        }

        binding.viewPager.setCurrentItem(0, false)
        refreshHooksFragment()
        if (HttpHeaderMasking.hasMissingSecretValue(importedHook.headers)) {
            Toast.makeText(this, R.string.preset_import_success_missing_secrets, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, R.string.preset_import_success, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshHooksFragment() {
        supportFragmentManager.fragments
            .filterIsInstance<HooksFragment>()
            .forEach { fragment ->
                fragment.refreshHooks()
            }
    }
}
