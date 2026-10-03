package com.personal.screenmacro.brightness

import android.content.Context
import android.provider.Settings
import com.personal.screenmacro.core.*

class BrightnessController(context: Context) {
    private val app = context.applicationContext
    private val preferences = app.getSharedPreferences("brightness_recovery", Context.MODE_PRIVATE)
    private val port = object : BrightnessPort {
        override fun allowed() = Settings.System.canWrite(app)
        override fun current() = BrightnessSnapshot(
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS),
            Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE))
        override fun pending() = if (preferences.contains("level"))
            BrightnessSnapshot(preferences.getInt("level", 128), preferences.getInt("mode", 0)) else null
        override fun save(snapshot: BrightnessSnapshot) = preferences.edit()
            .putInt("level", snapshot.level).putInt("mode", snapshot.mode).commit()
        override fun clear() = preferences.edit().clear().commit()
        override fun level(value: Int) = Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value)
        override fun mode(value: Int) = Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, value)
    }
    private val session = BrightnessSession(port)
    val pending get() = port.pending() != null
    val allowed get() = port.allowed()
    fun begin() = session.begin()
    fun restore() = session.restore()
}
