package com.personal.screenmacro

import android.content.Context
import androidx.core.content.edit
import com.personal.screenmacro.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class ExecutionSettings(context: Context, private val blocked: () -> Boolean, name: String = "execution_settings") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val age = MutableStateFlow(preferences.getLong("result_age_ms", DEFAULT_RESULT_AGE_MS)
        .takeIf(::validResultAge) ?: DEFAULT_RESULT_AGE_MS)
    val resultAgeMs = age.asStateFlow()
    fun setResultAge(value: Long) {
        check(!blocked()) { "실행 중에는 유효 시간을 변경할 수 없습니다. 먼저 정지하세요." }
        require(validResultAge(value)) { "유효 시간은 1.0~3.0초, 0.1초 단위입니다." }
        preferences.edit { putLong("result_age_ms", value) }
        age.value = value
    }
}
