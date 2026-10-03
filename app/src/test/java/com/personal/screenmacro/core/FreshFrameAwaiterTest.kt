package com.personal.screenmacro.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FreshFrameAwaiterTest {
    @Test fun timelyFrameDoesNotResetSurface() = runTest {
        val frame=CompletableDeferred<String>(); var refreshes=0
        val result=async { awaitFreshFrame(frame,{ refreshes++ }) }
        advanceTimeBy(100); frame.complete("new frame"); runCurrent()
        assertEquals("new frame",result.await()); assertEquals(0,refreshes)
    }
    @Test fun silentProducerResumesWithARealReplacementFrame() = runTest {
        val frame=CompletableDeferred<String>(); var refreshes=0
        val result=async { awaitFreshFrame(frame,{ refreshes++; frame.complete("new queue frame") }) }
        advanceTimeBy(501); runCurrent()
        assertEquals("new queue frame",result.await()); assertEquals(1,refreshes)
    }
    @Test fun failedRecoveryTimesOutWithoutProvidingCachedCoordinates() = runTest {
        val frame=CompletableDeferred<String>(); var refreshes=0
        val result=async { runCatching { awaitFreshFrame(frame,{ refreshes++ }) } }
        advanceTimeBy(4001); runCurrent()
        assertTrue(result.await().exceptionOrNull() is FrameTimeoutException)
        assertEquals(1,refreshes); assertFalse(frame.isCompleted)
    }
    @Test fun cancelledSessionDoesNotRefreshOrCompleteWithAnOldFrame() = runTest {
        val frame=CompletableDeferred<String>(); var refreshes=0
        val job=launch { awaitFreshFrame(frame,{ refreshes++ }) }
        advanceTimeBy(100); job.cancelAndJoin(); advanceTimeBy(5000)
        assertEquals(0,refreshes); assertFalse(frame.isCompleted)
    }
}
