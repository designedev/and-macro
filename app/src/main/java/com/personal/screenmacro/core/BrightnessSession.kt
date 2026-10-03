package com.personal.screenmacro.core

data class BrightnessSnapshot(val level: Int, val mode: Int)

interface BrightnessPort {
    fun allowed(): Boolean
    fun current(): BrightnessSnapshot
    fun pending(): BrightnessSnapshot?
    fun save(snapshot: BrightnessSnapshot): Boolean
    fun clear(): Boolean
    fun level(value: Int): Boolean
    fun mode(value: Int): Boolean
}

/** Persist the original before changing settings; keep it until both writes recover. */
class BrightnessSession(private val port: BrightnessPort) {
    fun begin() {
        check(port.allowed()) { "밝기 조절을 위해 시스템 설정 변경 권한을 허용하세요." }
        check(port.pending() == null) { "이전 밝기를 먼저 복구하세요." }
        check(port.save(port.current())) { "밝기 복구 정보를 저장하지 못했습니다." }
        try {
            check(port.mode(0) && port.level(1)) { "화면 밝기를 변경하지 못했습니다." }
        } catch (e: Exception) {
            restore()
            throw e
        }
    }

    fun restore(): Boolean {
        val original = port.pending() ?: return true
        if (!port.allowed()) return false
        // Try both settings even when one fails. Do not lose recovery data on failure.
        val level = runCatching { port.level(original.level) }.getOrDefault(false)
        val mode = runCatching { port.mode(original.mode) }.getOrDefault(false)
        return level && mode && runCatching { port.clear() }.getOrDefault(false)
    }
}
