package com.personal.screenmacro

import android.content.Context
import com.personal.screenmacro.core.StopNotice

/** Persist one termination notice and an active-session marker, not an unbounded log. */
class StopNoticeStore(context: Context, name: String = "last_stop_notice") {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    fun latest(): StopNotice? = preferences.getString("reason", null)?.let {
        StopNotice(preferences.getLong("time", 0), it)
    }
    fun recoverInterrupted(): StopNotice? {
        if (preferences.getBoolean("active", false)) {
            finish("이전 실행이 정상 종료되지 않았습니다. 앱 종료·시스템 중단 등의 원인일 수 있습니다. 다시 실행하세요.")
        }
        return latest()
    }
    // Synchronous writes only at session boundaries, so process termination cannot skip a queued save.
    fun begin() { preferences.edit().putBoolean("active", true).commit() }
    fun finish(reason: String? = null): StopNotice? {
        val editor = preferences.edit().putBoolean("active", false)
        if (reason != null) editor.putString("reason", reason).putLong("time", System.currentTimeMillis())
        editor.commit()
        return latest()
    }
}
