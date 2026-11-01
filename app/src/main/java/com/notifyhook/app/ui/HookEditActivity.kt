package com.notifyhook.app.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.widget.PopupMenu
import androidx.core.widget.doOnTextChanged
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.notifyhook.app.R
import com.notifyhook.app.data.GlobalVariableStore
import com.notifyhook.app.data.HookStorage
import com.notifyhook.app.data.TemplateEngine
import com.notifyhook.app.databinding.ActivityHookEditBinding
import com.notifyhook.app.databinding.DialogAppSelectBinding
import com.notifyhook.app.databinding.ItemRegexRuleBinding
import com.notifyhook.app.databinding.ItemRegexVariableBinding
import com.notifyhook.app.model.AppInfo
import com.notifyhook.app.model.BodyFileSaveState
import com.notifyhook.app.model.BodySourceType
import com.notifyhook.app.model.ContentType
import com.notifyhook.app.model.Hook
import com.notifyhook.app.model.HookDelivery
import com.notifyhook.app.model.HttpHeader
import com.notifyhook.app.model.HttpHeaderMasking
import com.notifyhook.app.model.HttpHeaderValidator
import com.notifyhook.app.model.HttpMethod
import com.notifyhook.app.model.HookUrlValidator
import com.notifyhook.app.model.RegexRule
import com.notifyhook.app.model.RegexRuleValidator
import com.notifyhook.app.model.RegexSourceField
import com.notifyhook.app.model.RegexVariable
import com.notifyhook.app.service.ForegroundService
import com.notifyhook.app.service.HookDeliverySender
import com.notifyhook.app.ui.adapter.AppSelectAdapter
import com.notifyhook.app.util.TextReadLimits
import com.notifyhook.app.util.TextReadLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.regex.PatternSyntaxException
import kotlin.coroutines.coroutineContext

class HookEditActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        private const val MIN_TIMEOUT_SECONDS = 1
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val TEST_RESPONSE_MAX_CHARS = 500
        private const val HEADER_PRESET_CUSTOM = 1
        private const val HEADER_PRESET_BEARER = 2
        private const val HEADER_PRESET_API_KEY = 3
        private const val TAG = "HookEditActivity"
    }

    private data class RegexVariableEditorItem(
        var captureGroup: Int = 1,
        var variableName: String = "",
        var isGlobal: Boolean = false
    )

    private data class RegexRuleEditorItem(
        var pattern: String = "",
        var sourceField: RegexSourceField = RegexSourceField.NOTIFICATION,
        var testInput: String = "",
        val variables: MutableList<RegexVariableEditorItem> = mutableListOf()
    )

    private data class SourceFieldOption(
        val field: RegexSourceField,
        val label: String
    )

    private lateinit var binding: ActivityHookEditBinding
    private lateinit var storage: HookStorage
    private var hook: Hook = Hook()
    private var isEditing = false
    private val selectedPackages = mutableSetOf<String>()
    private val regexRuleItems = mutableListOf<RegexRuleEditorItem>()
    private lateinit var regexRuleAdapter: RegexRuleAdapter
    private lateinit var sourceFieldOptions: List<SourceFieldOption>
    private val headerItems = mutableListOf<HeaderEditorItem>()
    private lateinit var headerAdapter: HeaderEditorAdapter
    private val testRequestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isTestingWebhook = false
    private var originalBodyFilePath: String? = null
    private var didSaveHook = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { persistBodyFileUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHookEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        storage = HookStorage.getInstance(this)

        val hookId = intent.getStringExtra("hook_id")
        if (hookId != null) {
            storage.getHook(hookId)?.let {
                hook = it
                isEditing = true
                originalBodyFilePath = it.bodyFilePath
                selectedPackages.addAll(it.targetPackages)
            }
        }

        setupToolbar()
        setupMethodToggle()
        setupContentType()
        setupBodySource()
        setupFileSelect()
        setupAppSelect()
        setupRegexRulesEditor()
        setupHeadersEditor()
        setupSave()
        setupTestWebhook()
        setupValidation()
        populateFields()
    }

    private fun setupToolbar() {
        binding.toolbar.title = if (isEditing) "훅 수정" else "훅 생성"
        binding.toolbar.setNavigationOnClickListener { finish() }
    }

    private fun setupMethodToggle() {
        binding.toggleMethod.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                hook.method = when (checkedId) {
                    R.id.btnPost -> HttpMethod.POST
                    R.id.btnPut -> HttpMethod.PUT
                    else -> HttpMethod.POST
                }
            }
        }
    }

    private fun setupContentType() {
        val types = ContentType.entries.map { it.displayName }
        val adapter = ArrayAdapter(this, R.layout.dropdown_item, types)
        binding.actvContentType.setAdapter(adapter)
        binding.actvContentType.setOnItemClickListener { _, _, position, _ ->
            hook.contentType = ContentType.entries[position]
        }
    }

    private fun setupBodySource() {
        binding.toggleBodySource.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnTextInput -> {
                        hook.bodySourceType = BodySourceType.TEXT
                        binding.tilBody.visibility = View.VISIBLE
                        binding.layoutFileSelect.visibility = View.GONE
                    }
                    R.id.btnFileInput -> {
                        hook.bodySourceType = BodySourceType.FILE
                        binding.tilBody.visibility = View.GONE
                        binding.layoutFileSelect.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun setupFileSelect() {
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("*/*"))
        }
    }

    private fun persistBodyFileUri(uri: Uri) {
        val nextUri = uri.toString()
        val previousUri = hook.bodyFilePath
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { e ->
            Log.e(TAG, "Failed to persist body file permission: ${e.message}")
            Toast.makeText(this, R.string.file_select_failed, Toast.LENGTH_SHORT).show()
            return
        }

        hook.bodyFilePath = nextUri
        binding.tvFilePath.text = uri.lastPathSegment ?: nextUri
        binding.tvFilePath.visibility = View.VISIBLE
        if (previousUri != originalBodyFilePath && previousUri != nextUri) {
            releaseBodyFilePermissionIfUnused(previousUri)
        }
    }

    private fun releaseBodyFilePermissionIfUnused(uriString: String?) {
        if (uriString.isNullOrBlank()) {
            return
        }
        if (storage.isBodyFileReferenced(uriString, hook.id)) {
            return
        }
        runCatching {
            contentResolver.releasePersistableUriPermission(
                Uri.parse(uriString),
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { e ->
            Log.d(TAG, "Body file permission was not released: ${e.message}")
        }
    }

    private fun setupAppSelect() {
        binding.btnSelectApps.setOnClickListener {
            showAppSelectDialog()
        }
    }

    private fun showAppSelectDialog() {
        val dialogBinding = DialogAppSelectBinding.inflate(layoutInflater)

        val dialogSelectedPackages = selectedPackages.toMutableSet()
        val adapter = AppSelectAdapter(dialogSelectedPackages)
        var isLoadingApps = true

        fun updateDialogState() {
            dialogBinding.progressLoading.visibility = if (isLoadingApps) View.VISIBLE else View.GONE
            val hasItems = adapter.itemCount > 0
            dialogBinding.recyclerApps.visibility = if (!isLoadingApps && hasItems) View.VISIBLE else View.GONE
            dialogBinding.tvEmptyState.visibility = if (!isLoadingApps && !hasItems) View.VISIBLE else View.GONE
        }

        dialogBinding.recyclerApps.layoutManager = LinearLayoutManager(this)
        dialogBinding.recyclerApps.adapter = adapter
        updateDialogState()

        dialogBinding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString() ?: "") {
                    updateDialogState()
                }
            }
        })

        val loadGuard = AppSelectLoadGuard()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_apps)
            .setView(dialogBinding.root)
            .setPositiveButton("확인") { _, _ ->
                selectedPackages.clear()
                selectedPackages.addAll(dialogSelectedPackages)
                updateSelectedAppsText()
            }
            .setNeutralButton("전체 해제") { _, _ ->
                selectedPackages.clear()
                updateSelectedAppsText()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.setOnDismissListener {
            loadGuard.dismiss()
        }

        val appLoadJob = lifecycleScope.launch {
            val apps = withContext(Dispatchers.Default) {
                loadSelectableApps()
            }
            if (!loadGuard.shouldApplyResult(isFinishing, isDestroyed)) {
                return@launch
            }
            val currentQuery = dialogBinding.etSearch.text?.toString().orEmpty()
            adapter.setApps(apps) {
                isLoadingApps = false
                if (currentQuery.isBlank()) {
                    updateDialogState()
                } else {
                    adapter.filter(currentQuery) {
                        updateDialogState()
                    }
                }
            }
        }
        loadGuard.trackLoad { appLoadJob.cancel() }
    }

    private fun loadSelectableApps(): List<AppInfo> {
        val pm = applicationContext.packageManager
        return pm.getInstalledApplications(0)
            .filter {
                it.flags and ApplicationInfo.FLAG_SYSTEM == 0 ||
                    pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .filter { it.packageName != packageName }
            .map {
                AppInfo(
                    packageName = it.packageName,
                    appName = pm.getApplicationLabel(it).toString(),
                    icon = try {
                        pm.getApplicationIcon(it)
                    } catch (e: Exception) {
                        null
                    }
                )
            }
            .sortedBy { it.appName }
    }

    private fun updateSelectedAppsText() {
        if (selectedPackages.isEmpty()) {
            binding.tvSelectedApps.text = "모든 앱 (선택하지 않으면 모든 알림 감지)"
        } else {
            val pm = packageManager
            val names = selectedPackages.mapNotNull { pkg ->
                try {
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) {
                    pkg
                }
            }
            binding.tvSelectedApps.text = names.joinToString(", ")
        }
    }

    private fun setupHeadersEditor() {
        headerAdapter = HeaderEditorAdapter(headerItems) {
            updateCleartextWarning()
        }
        binding.recyclerHeaders.layoutManager = LinearLayoutManager(this)
        binding.recyclerHeaders.adapter = headerAdapter
        binding.recyclerHeaders.isNestedScrollingEnabled = false

        binding.btnAddHeader.setOnClickListener { anchor ->
            PopupMenu(this, anchor).apply {
                menu.add(0, HEADER_PRESET_CUSTOM, 0, R.string.header_preset_custom)
                menu.add(0, HEADER_PRESET_BEARER, 1, R.string.header_preset_bearer)
                menu.add(0, HEADER_PRESET_API_KEY, 2, R.string.header_preset_api_key)
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        HEADER_PRESET_BEARER -> addHeader(
                            HeaderEditorItem(name = "Authorization", value = "Bearer ", sensitive = true, sensitiveDecided = true)
                        )
                        HEADER_PRESET_API_KEY -> addHeader(
                            HeaderEditorItem(name = "X-API-Key", sensitive = true, sensitiveDecided = true)
                        )
                        else -> addHeader(HeaderEditorItem())
                    }
                    true
                }
                show()
            }
        }
    }

    private fun addHeader(item: HeaderEditorItem) {
        if (headerItems.size >= HttpHeaderValidator.MAX_HEADERS) {
            Toast.makeText(
                this,
                getString(R.string.header_error_too_many, HttpHeaderValidator.MAX_HEADERS),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        headerItems.add(item)
        headerAdapter.notifyItemInserted(headerItems.lastIndex)
        updateCleartextWarning()
    }

    private fun setHeadersFromHook(headers: List<HttpHeader>) {
        val oldSize = headerItems.size
        if (oldSize > 0) {
            headerItems.clear()
            headerAdapter.notifyItemRangeRemoved(0, oldSize)
        }
        if (headers.isNotEmpty()) {
            headerItems.addAll(headers.map { HeaderEditorItem.from(it) })
            headerAdapter.notifyItemRangeInserted(0, headers.size)
        }
    }

    // 이름과 값이 모두 빈 행은 "추가만 하고 안 쓴 행"으로 보고 버린다(정규식 규칙의 빈 패턴과 같은 처리).
    private fun buildHeadersFromEditor(): List<HttpHeader> {
        return headerItems.filterNot { it.isBlank }.map { it.toHeader() }
    }

    private fun validateHeaders(showErrors: Boolean): Boolean {
        val filledItems = headerItems.filterNot { it.isBlank }
        if (filledItems.size > HttpHeaderValidator.MAX_HEADERS) {
            if (showErrors) {
                Toast.makeText(
                    this,
                    getString(R.string.header_error_too_many, HttpHeaderValidator.MAX_HEADERS),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }

        var isValid = true
        filledItems.forEach { item ->
            val header = item.toHeader()
            var nameError: CharSequence? = null
            var valueError: CharSequence? = null
            when (HttpHeaderValidator.problemOf(header)) {
                HttpHeaderValidator.Problem.EMPTY_NAME ->
                    nameError = getString(R.string.header_error_empty_name)
                HttpHeaderValidator.Problem.NAME_TOO_LONG ->
                    nameError = getString(R.string.header_error_name_too_long, HttpHeaderValidator.MAX_NAME_LENGTH)
                HttpHeaderValidator.Problem.INVALID_NAME ->
                    nameError = getString(R.string.header_error_invalid_name)
                HttpHeaderValidator.Problem.RESERVED_NAME ->
                    nameError = getString(R.string.header_error_reserved_name)
                HttpHeaderValidator.Problem.VALUE_TOO_LONG ->
                    valueError = getString(R.string.header_error_value_too_long, HttpHeaderValidator.MAX_VALUE_LENGTH)
                HttpHeaderValidator.Problem.INVALID_VALUE ->
                    valueError = getString(R.string.header_error_invalid_value)
                HttpHeaderValidator.Problem.TOO_MANY, null -> Unit
            }
            // 가져온 프리셋에서 키 값이 빠진 경우처럼, 민감 헤더를 빈 값으로 보내면 인증 실패만 반복된다.
            if (nameError == null && valueError == null &&
                HttpHeaderMasking.hasMissingSecretValue(listOf(header))
            ) {
                valueError = getString(R.string.header_error_secret_value_required)
            }
            if (nameError != null || valueError != null) {
                isValid = false
            }
            if (showErrors) {
                item.nameError = nameError
                item.valueError = valueError
            }
        }

        if (showErrors) {
            headerAdapter.notifyItemRangeChanged(0, headerItems.size)
            if (!isValid) {
                Toast.makeText(this, R.string.header_fix_errors, Toast.LENGTH_SHORT).show()
            }
        }
        return isValid
    }

    private fun updateCleartextWarning() {
        val url = binding.etUrl.text?.toString()?.trim().orEmpty()
        val sendsSecretInCleartext = url.startsWith("http://", ignoreCase = true) &&
            buildHeadersFromEditor().any { HttpHeaderMasking.shouldMask(it) }
        binding.tilUrl.helperText = if (sendsSecretInCleartext) {
            getString(R.string.header_cleartext_warning)
        } else {
            null
        }
    }

    private fun setupRegexRulesEditor() {
        sourceFieldOptions = listOf(
            SourceFieldOption(RegexSourceField.TITLE, getString(R.string.regex_source_title)),
            SourceFieldOption(RegexSourceField.TEXT, getString(R.string.regex_source_text)),
            SourceFieldOption(RegexSourceField.NOTIFICATION, getString(R.string.regex_source_notification))
        )

        regexRuleAdapter = RegexRuleAdapter(regexRuleItems)
        binding.recyclerRegexRules.layoutManager = LinearLayoutManager(this)
        binding.recyclerRegexRules.adapter = regexRuleAdapter
        binding.recyclerRegexRules.isNestedScrollingEnabled = false

        binding.btnAddRegexRule.setOnClickListener {
            addRegexRule()
        }
    }

    private fun addRegexRule(rule: RegexRuleEditorItem = RegexRuleEditorItem()) {
        regexRuleItems.add(rule)
        regexRuleAdapter.notifyItemInserted(regexRuleItems.lastIndex)
    }

    private fun setRegexRulesFromHook(rules: List<RegexRule>) {
        val oldSize = regexRuleItems.size
        if (oldSize > 0) {
            regexRuleItems.clear()
            regexRuleAdapter.notifyItemRangeRemoved(0, oldSize)
        }
        val newItems = rules.map { rule ->
            RegexRuleEditorItem(
                pattern = rule.pattern,
                sourceField = rule.sourceField,
                variables = rule.variables.map {
                    RegexVariableEditorItem(
                        captureGroup = it.captureGroup,
                        variableName = it.variableName,
                        isGlobal = it.isGlobal
                    )
                }.toMutableList()
            )
        }
        if (newItems.isNotEmpty()) {
            regexRuleItems.addAll(newItems)
            regexRuleAdapter.notifyItemRangeInserted(0, newItems.size)
        }
    }

    private fun buildRegexRulesFromEditor(): List<RegexRule> {
        return regexRuleItems.mapNotNull ruleMap@{ rule ->
            val pattern = rule.pattern.trim()
            if (pattern.isEmpty()) {
                return@ruleMap null
            }

            RegexRule(
                pattern = pattern,
                sourceField = rule.sourceField,
                variables = rule.variables.mapNotNull variableMap@{ variable ->
                    val variableName = variable.variableName.trim()
                    if (variableName.isEmpty() || variable.captureGroup < 0) {
                        return@variableMap null
                    }
                    RegexVariable(
                        captureGroup = variable.captureGroup,
                        variableName = variableName,
                        isGlobal = variable.isGlobal
                    )
                }
            )
        }
    }

    private fun sourceFieldLabel(sourceField: RegexSourceField): String {
        return sourceFieldOptions.firstOrNull { it.field == sourceField }?.label
            ?: getString(R.string.regex_source_notification)
    }

    private fun buildRegexTestResult(pattern: String, input: String): String {
        if (pattern.isBlank()) {
            return getString(R.string.regex_test_enter_pattern)
        }

        if (input.isBlank()) {
            return getString(R.string.regex_test_enter_input)
        }

        return try {
            val match = Regex(pattern).find(input) ?: return getString(R.string.regex_test_no_match)
            match.groupValues.mapIndexed { index, value ->
                getString(R.string.regex_test_group_format, index, value)
            }.joinToString("\n")
        } catch (e: PatternSyntaxException) {
            getString(R.string.regex_invalid_pattern_format, e.description)
        }
    }

    private inner class RegexRuleAdapter(
        private val rules: MutableList<RegexRuleEditorItem>
    ) : RecyclerView.Adapter<RegexRuleAdapter.RegexRuleViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RegexRuleViewHolder {
            val itemBinding = ItemRegexRuleBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return RegexRuleViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: RegexRuleViewHolder, position: Int) {
            holder.bind(rules[position], position)
        }

        override fun getItemCount(): Int = rules.size

        inner class RegexRuleViewHolder(
            private val itemBinding: ItemRegexRuleBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            private var patternWatcher: TextWatcher? = null
            private var testInputWatcher: TextWatcher? = null
            private val variableAdapter = RegexVariableAdapter()

            init {
                itemBinding.recyclerRegexVariables.layoutManager =
                    LinearLayoutManager(itemBinding.root.context)
                itemBinding.recyclerRegexVariables.adapter = variableAdapter
                itemBinding.recyclerRegexVariables.isNestedScrollingEnabled = false

                val sourceFieldAdapter = ArrayAdapter(
                    itemBinding.root.context,
                    R.layout.dropdown_item,
                    sourceFieldOptions.map { it.label }
                )
                itemBinding.actvRegexSourceField.setAdapter(sourceFieldAdapter)

                itemBinding.btnAddRegexVariable.setOnClickListener {
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@setOnClickListener
                    }

                    val rule = rules[adapterPosition]
                    rule.variables.add(RegexVariableEditorItem())
                    variableAdapter.notifyItemInserted(rule.variables.lastIndex)
                }

                itemBinding.btnRemoveRegexRule.setOnClickListener {
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@setOnClickListener
                    }

                    rules.removeAt(adapterPosition)
                    notifyItemRemoved(adapterPosition)
                    notifyItemRangeChanged(adapterPosition, itemCount - adapterPosition)
                }

                itemBinding.actvRegexSourceField.setOnItemClickListener { _, _, position, _ ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@setOnItemClickListener
                    }

                    if (position in sourceFieldOptions.indices) {
                        rules[adapterPosition].sourceField = sourceFieldOptions[position].field
                    }
                }
            }

            fun bind(rule: RegexRuleEditorItem, position: Int) {
                itemBinding.tvRegexRuleTitle.text =
                    getString(R.string.regex_rule_title_format, position + 1)

                patternWatcher?.let { itemBinding.etRegexPattern.removeTextChangedListener(it) }
                testInputWatcher?.let { itemBinding.etRegexTestInput.removeTextChangedListener(it) }

                if (itemBinding.etRegexPattern.text?.toString() != rule.pattern) {
                    itemBinding.etRegexPattern.setText(rule.pattern)
                }

                if (itemBinding.etRegexTestInput.text?.toString() != rule.testInput) {
                    itemBinding.etRegexTestInput.setText(rule.testInput)
                }

                itemBinding.actvRegexSourceField.setText(sourceFieldLabel(rule.sourceField), false)

                patternWatcher = itemBinding.etRegexPattern.doOnTextChanged { text, _, _, _ ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@doOnTextChanged
                    }

                    val currentRule = rules[adapterPosition]
                    currentRule.pattern = text?.toString().orEmpty()
                    itemBinding.tvRegexTestResult.text =
                        buildRegexTestResult(currentRule.pattern, currentRule.testInput)
                }

                testInputWatcher = itemBinding.etRegexTestInput.doOnTextChanged { text, _, _, _ ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@doOnTextChanged
                    }

                    val currentRule = rules[adapterPosition]
                    currentRule.testInput = text?.toString().orEmpty()
                    itemBinding.tvRegexTestResult.text =
                        buildRegexTestResult(currentRule.pattern, currentRule.testInput)
                }

                variableAdapter.setVariables(rule.variables)
                itemBinding.tvRegexTestResult.text =
                    buildRegexTestResult(rule.pattern, rule.testInput)
            }
        }
    }

    private inner class RegexVariableAdapter :
        RecyclerView.Adapter<RegexVariableAdapter.RegexVariableViewHolder>() {

        private var variables: MutableList<RegexVariableEditorItem> = mutableListOf()

        fun setVariables(newVariables: MutableList<RegexVariableEditorItem>) {
            if (variables === newVariables) {
                return
            }
            val oldSize = variables.size
            if (oldSize > 0) {
                notifyItemRangeRemoved(0, oldSize)
            }
            variables = newVariables
            if (variables.isNotEmpty()) {
                notifyItemRangeInserted(0, variables.size)
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RegexVariableViewHolder {
            val itemBinding = ItemRegexVariableBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return RegexVariableViewHolder(itemBinding)
        }

        override fun onBindViewHolder(holder: RegexVariableViewHolder, position: Int) {
            holder.bind(variables[position])
        }

        override fun getItemCount(): Int = variables.size

        inner class RegexVariableViewHolder(
            private val itemBinding: ItemRegexVariableBinding
        ) : RecyclerView.ViewHolder(itemBinding.root) {

            private var captureGroupWatcher: TextWatcher? = null
            private var variableNameWatcher: TextWatcher? = null

            fun bind(variable: RegexVariableEditorItem) {
                captureGroupWatcher?.let {
                    itemBinding.etRegexCaptureGroup.removeTextChangedListener(it)
                }
                variableNameWatcher?.let {
                    itemBinding.etRegexVariableName.removeTextChangedListener(it)
                }

                val captureGroupValue = variable.captureGroup.toString()
                if (itemBinding.etRegexCaptureGroup.text?.toString() != captureGroupValue) {
                    itemBinding.etRegexCaptureGroup.setText(captureGroupValue)
                }

                if (itemBinding.etRegexVariableName.text?.toString() != variable.variableName) {
                    itemBinding.etRegexVariableName.setText(variable.variableName)
                }

                itemBinding.switchRegexGlobal.setOnCheckedChangeListener(null)
                itemBinding.switchRegexGlobal.isChecked = variable.isGlobal
                itemBinding.switchRegexGlobal.setOnCheckedChangeListener { _, isChecked ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@setOnCheckedChangeListener
                    }
                    variables[adapterPosition].isGlobal = isChecked
                }

                captureGroupWatcher = itemBinding.etRegexCaptureGroup.doOnTextChanged { text, _, _, _ ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@doOnTextChanged
                    }

                    variables[adapterPosition].captureGroup = text?.toString()?.toIntOrNull() ?: 0
                }

                variableNameWatcher = itemBinding.etRegexVariableName.doOnTextChanged { text, _, _, _ ->
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@doOnTextChanged
                    }

                    variables[adapterPosition].variableName = text?.toString().orEmpty()
                }

                itemBinding.btnRemoveRegexVariable.setOnClickListener {
                    val adapterPosition = bindingAdapterPosition
                    if (adapterPosition == RecyclerView.NO_POSITION) {
                        return@setOnClickListener
                    }

                    variables.removeAt(adapterPosition)
                    notifyItemRemoved(adapterPosition)
                }
            }
        }
    }

    private fun setupSave() {
        binding.btnSave.setOnClickListener {
            if (!validateForm(showErrors = true)) {
                return@setOnClickListener
            }

            val name = binding.etName.text?.toString()?.trim() ?: ""
            val url = binding.etUrl.text?.toString()?.trim() ?: ""
            val timeout = binding.etTimeout.text?.toString()?.toIntOrNull() ?: DEFAULT_TIMEOUT_SECONDS
            val body = binding.etBody.text?.toString() ?: ""
            val regexRules = buildRegexRulesFromEditor()

            hook.name = name
            hook.url = url
            hook.timeoutSeconds = timeout
            hook.bodyTemplate = body
            hook.targetPackages = selectedPackages.toList()
            hook.regexRules = regexRules
            hook.headers = buildHeadersFromEditor()
            val bodyFileSaveState = BodyFileSaveState.prepare(
                bodySourceType = hook.bodySourceType,
                selectedBodyFilePath = hook.bodyFilePath
            )
            hook.bodyFilePath = bodyFileSaveState.pathForStorage

            if (!storage.saveHook(hook)) {
                hook.bodyFilePath = bodyFileSaveState.selectedPath
                Toast.makeText(this, R.string.hook_save_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            didSaveHook = true
            releaseStaleBodyFilePermissionsAfterSave(bodyFileSaveState.selectedPath)
            ForegroundService.startIfNeeded(this)
            Toast.makeText(this, if (isEditing) "훅이 수정되었습니다" else "훅이 생성되었습니다", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupTestWebhook() {
        binding.btnTest.setOnClickListener {
            val url = validateUrl(showErrors = true) ?: return@setOnClickListener
            val timeoutSeconds = validateTimeout(showErrors = true) ?: return@setOnClickListener
            if (!validateHeaders(showErrors = true)) {
                return@setOnClickListener
            }

            setTestingState(true)

            // 실제 전송과 같은 HookDelivery를 만들어 같은 경로(HookDeliverySender)로 보낸다.
            val delivery = HookDelivery(
                hookId = hook.id,
                hookName = binding.etName.text?.toString()?.trim().orEmpty(),
                requestUrl = url,
                requestMethod = getSelectedMethod().name,
                contentType = getSelectedContentType().value,
                requestHeaders = buildHeadersFromEditor(),
                requestBody = applyTestTemplateValues(getCurrentBodyTemplate()),
                timeoutSeconds = timeoutSeconds
            )

            testRequestScope.launch {
                val resultMessage = try {
                    sendTestWebhookRequest(delivery)
                } catch (e: Exception) {
                    getString(R.string.test_error_format_exact, e.message ?: "Unknown error")
                }

                withContext(Dispatchers.Main) {
                    if (isFinishing || isDestroyed) {
                        return@withContext
                    }
                    setTestingState(false)
                    MaterialAlertDialogBuilder(this@HookEditActivity)
                        .setTitle(R.string.test_result_title_exact)
                        .setMessage(resultMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    private fun setupValidation() {
        binding.etName.doOnTextChanged { _, _, _, _ ->
            binding.tilName.error = null
            updateActionButtonsState()
        }

        binding.etUrl.doOnTextChanged { _, _, _, _ ->
            binding.tilUrl.error = null
            updateActionButtonsState()
            updateCleartextWarning()
        }

        binding.etTimeout.doOnTextChanged { _, _, _, _ ->
            binding.tilTimeout.error = null
        }

        updateActionButtonsState()
    }

    private fun updateActionButtonsState() {
        val hasName = binding.etName.text?.toString()?.trim()?.isNotEmpty() == true
        val hasUrl = binding.etUrl.text?.toString()?.trim()?.isNotEmpty() == true
        binding.btnSave.isEnabled = hasName && hasUrl && !isTestingWebhook
        binding.btnTest.isEnabled = hasUrl && !isTestingWebhook
    }

    private fun setTestingState(isTesting: Boolean) {
        isTestingWebhook = isTesting
        binding.btnTest.text = getString(if (isTesting) R.string.test_sending_exact else R.string.test_hook)
        updateActionButtonsState()
    }

    private fun validateForm(showErrors: Boolean): Boolean {
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        val url = validateUrl(showErrors)
        val timeoutValue = validateTimeout(showErrors)
        val regexRulesValid = validateRegexRules(showErrors)
        val headersValid = validateHeaders(showErrors)

        var isValid = true

        if (name.isEmpty()) {
            if (showErrors) {
                binding.tilName.error = getString(R.string.name_required_exact)
            }
            isValid = false
        } else {
            binding.tilName.error = null
        }

        if (url == null) isValid = false
        if (timeoutValue == null) isValid = false
        if (!regexRulesValid) isValid = false
        if (!headersValid) isValid = false

        return isValid
    }

    private fun validateRegexRules(showErrors: Boolean): Boolean {
        val invalidPattern = RegexRuleValidator.firstInvalidPattern(buildRegexRulesFromEditor())
        if (invalidPattern == null) {
            return true
        }
        if (showErrors) {
            Toast.makeText(
                this,
                getString(R.string.regex_invalid_save_error, invalidPattern),
                Toast.LENGTH_SHORT
            ).show()
        }
        return false
    }

    private fun validateUrl(showErrors: Boolean): String? {
        val url = binding.etUrl.text?.toString()?.trim().orEmpty()
        return when {
            url.isEmpty() -> {
                if (showErrors) {
                    binding.tilUrl.error = getString(R.string.url_required_exact)
                }
                null
            }
            !HookUrlValidator.isSupported(url) -> {
                if (showErrors) {
                    binding.tilUrl.error = "http:// 또는 https://로 시작해야 합니다"
                }
                null
            }
            else -> {
                binding.tilUrl.error = null
                url
            }
        }
    }

    private fun validateTimeout(showErrors: Boolean): Int? {
        val timeout = binding.etTimeout.text?.toString()?.trim()?.toIntOrNull()
        return if (timeout == null || timeout !in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS) {
            if (showErrors) {
                binding.tilTimeout.error = getString(R.string.timeout_range_error_exact)
            }
            null
        } else {
            binding.tilTimeout.error = null
            timeout
        }
    }

    private fun getSelectedMethod(): HttpMethod {
        return when (binding.toggleMethod.checkedButtonId) {
            R.id.btnPut -> HttpMethod.PUT
            else -> HttpMethod.POST
        }
    }

    private fun getSelectedContentType(): ContentType {
        val selected = binding.actvContentType.text?.toString().orEmpty()
        return ContentType.entries.find { it.displayName == selected || it.value == selected }
            ?: hook.contentType
    }

    private fun getCurrentBodyTemplate(): String {
        if (binding.toggleBodySource.checkedButtonId != R.id.btnFileInput) {
            return binding.etBody.text?.toString().orEmpty()
        }

        val bodyFilePath = hook.bodyFilePath ?: return binding.etBody.text?.toString().orEmpty()
        return try {
            contentResolver.openInputStream(Uri.parse(bodyFilePath))
                ?.use { input ->
                    input.bufferedReader().use { reader ->
                        TextReadLimiter.read(reader, TextReadLimits.BODY_FILE_MAX_CHARS)
                    }
                }
                ?: binding.etBody.text?.toString().orEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read test body file: ${e.message}")
            binding.etBody.text?.toString().orEmpty()
        }
    }

    // 실제 전송과 같은 TemplateEngine으로 치환해, 테스트 결과가 실제 본문과 다르게 보이지 않게 한다.
    // onGlobalVariable을 넘기지 않으므로 테스트 전송은 저장된 전역 변수 값을 바꾸지 않는다.
    private fun applyTestTemplateValues(template: String): String {
        return TemplateEngine.processFields(
            template = template,
            packageName = "com.test.app",
            appName = getString(R.string.regex_default_test_app_name),
            postTime = System.currentTimeMillis(),
            title = getString(R.string.regex_default_test_title),
            text = getString(R.string.regex_default_test_text),
            subText = getString(R.string.regex_default_test_subtext),
            ticker = getString(R.string.regex_default_test_ticker),
            bigText = getString(R.string.regex_default_test_big_text),
            summary = getString(R.string.regex_default_test_summary),
            regexRules = buildRegexRulesFromEditor(),
            globalVariables = GlobalVariableStore.getInstance(this).getAll()
        )
    }

    private suspend fun sendTestWebhookRequest(delivery: HookDelivery): String {
        val call = HookDeliverySender.newCall(delivery)
        val startedAt = System.currentTimeMillis()
        val cancellationHandle = coroutineContext[Job]?.invokeOnCompletion {
            call.cancel()
        }
        try {
            val result = HookDeliverySender.execute(call, TEST_RESPONSE_MAX_CHARS)
            val elapsed = System.currentTimeMillis() - startedAt
            if (result.responseCode < 0) {
                return getString(R.string.test_error_format_exact, result.errorMessage)
            }
            return buildString {
                append(getString(R.string.test_result_format_exact, result.responseCode, elapsed, result.responseBody))
                // 리다이렉트처럼 상태 코드만으로는 원인을 알기 어려운 실패는 안내 문구를 덧붙인다.
                if (!result.success && result.errorMessage != "HTTP ${result.responseCode}") {
                    append("\n\n")
                    append(result.errorMessage)
                }
                if (delivery.requestHeaders.isNotEmpty()) {
                    append("\n\n")
                    append(getString(R.string.test_sent_headers, HttpHeaderMasking.summary(delivery.requestHeaders)))
                }
            }
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun releaseStaleBodyFilePermissionsAfterSave(selectedBodyFilePath: String?) {
        if (originalBodyFilePath != hook.bodyFilePath) {
            releaseBodyFilePermissionIfUnused(originalBodyFilePath)
        }
        if (selectedBodyFilePath != hook.bodyFilePath && selectedBodyFilePath != originalBodyFilePath) {
            releaseBodyFilePermissionIfUnused(selectedBodyFilePath)
        }
    }

    private fun populateFields() {
        if (isEditing) {
            binding.etName.setText(hook.name)
            binding.etUrl.setText(hook.url)
            binding.etTimeout.setText(hook.timeoutSeconds.toString())
            binding.etBody.setText(hook.bodyTemplate)

            when (hook.method) {
                HttpMethod.POST -> binding.toggleMethod.check(R.id.btnPost)
                HttpMethod.PUT -> binding.toggleMethod.check(R.id.btnPut)
            }

            binding.actvContentType.setText(hook.contentType.displayName, false)

            when (hook.bodySourceType) {
                BodySourceType.TEXT -> {
                    binding.toggleBodySource.check(R.id.btnTextInput)
                    binding.tilBody.visibility = View.VISIBLE
                    binding.layoutFileSelect.visibility = View.GONE
                }
                BodySourceType.FILE -> {
                    binding.toggleBodySource.check(R.id.btnFileInput)
                    binding.tilBody.visibility = View.GONE
                    binding.layoutFileSelect.visibility = View.VISIBLE
                    hook.bodyFilePath?.let {
                        binding.tvFilePath.text = Uri.parse(it).lastPathSegment ?: it
                        binding.tvFilePath.visibility = View.VISIBLE
                    }
                }
            }

            updateSelectedAppsText()
            setRegexRulesFromHook(hook.regexRules)
            setHeadersFromHook(hook.headers)
        } else {
            binding.toggleMethod.check(R.id.btnPost)
            binding.toggleBodySource.check(R.id.btnTextInput)
            binding.actvContentType.setText(ContentType.APPLICATION_JSON.displayName, false)
            binding.etBody.setText("{\n  \"notification\": \"{notification}\",\n  \"app\": \"{app_name}\",\n  \"title\": \"{title}\",\n  \"text\": \"{text}\",\n  \"timestamp\": \"{timestamp}\"\n}")
            setRegexRulesFromHook(emptyList())
        }

        updateCleartextWarning()
        updateActionButtonsState()
    }

    override fun onDestroy() {
        if (!didSaveHook && hook.bodyFilePath != originalBodyFilePath) {
            releaseBodyFilePermissionIfUnused(hook.bodyFilePath)
        }
        testRequestScope.cancel()
        super.onDestroy()
    }
}
