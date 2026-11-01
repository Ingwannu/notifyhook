package com.notifyhook.app.ui

import android.graphics.Typeface
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.core.widget.doOnTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.textfield.TextInputLayout
import com.notifyhook.app.databinding.ItemHttpHeaderBinding
import com.notifyhook.app.model.HttpHeader
import com.notifyhook.app.model.HttpHeaderMasking

internal class HeaderEditorItem(
    var name: String = "",
    var value: String = "",
    var sensitive: Boolean = false,
    // 사용자가 민감 스위치를 직접 건드렸거나 저장된 값을 불러온 경우, 이름 기반 자동 판단으로 덮어쓰지 않는다.
    var sensitiveDecided: Boolean = false,
    var nameError: CharSequence? = null,
    var valueError: CharSequence? = null
) {
    val isBlank: Boolean
        get() = name.isBlank() && value.isBlank()

    fun toHeader(): HttpHeader = HttpHeader(name = name.trim(), value = value.trim(), sensitive = sensitive)

    companion object {
        fun from(header: HttpHeader) = HeaderEditorItem(
            name = header.name,
            value = header.value,
            sensitive = header.sensitive,
            sensitiveDecided = true
        )
    }
}

/* [Decision Log]
- 목적: 훅 편집 화면에서 헤더 목록(이름/값/민감 여부)을 추가·수정·삭제한다.
- 대안 분석:
  1) HookEditActivity 안의 inner Adapter로 구현(정규식 규칙 편집기와 같은 방식): 이미 1,000줄이 넘는 Activity가 더 커진다.
  2) LinearLayout에 행 View를 직접 추가: 단순하지만 정규식 편집기와 상태 관리 방식이 달라져 일관성이 떨어진다.
  3) 같은 RecyclerView + TextWatcher 재바인딩 패턴을 별도 파일로 분리: 기존 편집기와 동작이 같고 Activity 크기를 늘리지 않는다.
- 선택 근거: 3). 행 상태는 HeaderEditorItem에 두고 Activity는 목록과 검증만 다룬다.
  이름이 Authorization/X-API-Key처럼 보이면 사용자가 스위치를 만지기 전까지 자동으로 민감 처리해, 키가 화면에 그대로 보이는 실수를 줄인다.
*/
internal class HeaderEditorAdapter(
    private val items: MutableList<HeaderEditorItem>,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<HeaderEditorAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHttpHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(
        private val binding: ItemHttpHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var nameWatcher: TextWatcher? = null
        private var valueWatcher: TextWatcher? = null
        private val sensitiveListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            currentItem()?.let { current ->
                current.sensitive = isChecked
                current.sensitiveDecided = true
                applySensitiveState(isChecked)
                onChanged()
            }
        }

        init {
            binding.btnRemoveHeader.setOnClickListener {
                val position = bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) {
                    return@setOnClickListener
                }
                items.removeAt(position)
                notifyItemRemoved(position)
                onChanged()
            }
        }

        fun bind(item: HeaderEditorItem) {
            nameWatcher?.let { binding.etHeaderName.removeTextChangedListener(it) }
            valueWatcher?.let { binding.etHeaderValue.removeTextChangedListener(it) }

            if (binding.etHeaderName.text?.toString() != item.name) {
                binding.etHeaderName.setText(item.name)
            }
            if (binding.etHeaderValue.text?.toString() != item.value) {
                binding.etHeaderValue.setText(item.value)
            }
            binding.tilHeaderName.error = item.nameError
            binding.tilHeaderValue.error = item.valueError
            applySensitiveState(item.sensitive)

            setSwitchSilently(item.sensitive)

            nameWatcher = binding.etHeaderName.doOnTextChanged { text, _, _, _ ->
                val current = currentItem() ?: return@doOnTextChanged
                current.name = text?.toString().orEmpty()
                clearError(current, binding.tilHeaderName, isName = true)
                if (!current.sensitiveDecided) {
                    val looksSensitive = HttpHeaderMasking.looksSensitive(current.name)
                    if (looksSensitive != current.sensitive) {
                        current.sensitive = looksSensitive
                        setSwitchSilently(looksSensitive)
                        applySensitiveState(looksSensitive)
                    }
                }
                onChanged()
            }

            valueWatcher = binding.etHeaderValue.doOnTextChanged { text, _, _, _ ->
                val current = currentItem() ?: return@doOnTextChanged
                current.value = text?.toString().orEmpty()
                clearError(current, binding.tilHeaderValue, isName = false)
                onChanged()
            }
        }

        private fun setSwitchSilently(checked: Boolean) {
            binding.switchHeaderSensitive.setOnCheckedChangeListener(null)
            binding.switchHeaderSensitive.isChecked = checked
            binding.switchHeaderSensitive.setOnCheckedChangeListener(sensitiveListener)
        }

        private fun currentItem(): HeaderEditorItem? {
            val position = bindingAdapterPosition
            return if (position == RecyclerView.NO_POSITION) null else items[position]
        }

        private fun clearError(item: HeaderEditorItem, layout: TextInputLayout, isName: Boolean) {
            if (isName) item.nameError = null else item.valueError = null
            layout.error = null
        }

        private fun applySensitiveState(sensitive: Boolean) {
            val editText = binding.etHeaderValue
            val selection = editText.selectionEnd.coerceAtLeast(0)
            val inputType = if (sensitive) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if (editText.inputType != inputType) {
                editText.inputType = inputType
                // inputType 변경은 글꼴과 커서 위치를 초기화하므로 다시 맞춘다.
                editText.typeface = Typeface.MONOSPACE
                editText.setSelection(selection.coerceAtMost(editText.text?.length ?: 0))
            }
            binding.tilHeaderValue.endIconMode = if (sensitive) {
                TextInputLayout.END_ICON_PASSWORD_TOGGLE
            } else {
                TextInputLayout.END_ICON_NONE
            }
        }
    }
}
