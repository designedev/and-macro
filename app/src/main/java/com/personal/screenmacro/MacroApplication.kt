package com.personal.screenmacro
import android.app.Application
import com.personal.screenmacro.data.MacroRepository
import com.personal.screenmacro.brightness.BrightnessController
import com.personal.screenmacro.core.*
class MacroApplication : Application() {
    val repository by lazy { MacroRepository(this) { RuntimeStore.status.value.busy } }
    val brightness by lazy { BrightnessController(this) }
    override fun onCreate() {
        super.onCreate()
        if (brightness.pending) {
            val restored = brightness.restore()
            RuntimeStore.status.value = RuntimeStatus(if (restored) EngineState.IDLE else EngineState.ERROR,
                if (restored) "이전 실행의 밝기를 복구했습니다." else "이전 밝기 복구가 필요합니다 · 밝기 권한을 확인하세요.")
        }
    }
}
