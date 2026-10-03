package com.personal.screenmacro.core

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

class FrameTimeoutException : IllegalStateException("새 화면 프레임을 받지 못했습니다. 화면 공유 상태를 확인하세요.")

/** Recover a silent producer once, but never reuse or retimestamp an older frame. */
suspend fun <T : Any> awaitFreshFrame(frame: Deferred<T>, refresh: () -> Unit,
    initialWaitMs: Long = 500, recoveryWaitMs: Long = 3500): T {
    withTimeoutOrNull(initialWaitMs) { frame.await() }?.let { return it }
    refresh()
    return withTimeoutOrNull(recoveryWaitMs) { frame.await() } ?: throw FrameTimeoutException()
}
