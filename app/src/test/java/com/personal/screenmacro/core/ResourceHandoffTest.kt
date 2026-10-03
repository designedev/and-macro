package com.personal.screenmacro.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ResourceHandoffTest {
    @Test fun cancelledReceiverReleasesAlreadyProducedResourceOnce() {
        var released = 0
        val handoff = ResourceHandoff<Any> { released++ }
        handoff.offer(Any()); handoff.close(); handoff.close()
        assertEquals(1, released)
    }
    @Test fun lateProducerAfterCancellationStillReleasesResource() {
        var released = 0
        val handoff = ResourceHandoff<Any> { released++ }
        handoff.close(); handoff.offer(Any()); handoff.close()
        assertEquals(1, released)
    }
    @Test fun successfulReceiverOwnsResourceAfterHandoffCloses() {
        var released = 0
        val handoff = ResourceHandoff<Any> { released++ }
        val resource = Any()
        handoff.offer(resource)
        assertSame(resource, handoff.take())
        handoff.close()
        assertEquals(0, released)
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancellationAtDispatcherReturnReleasesUndeliveredResource() = runTest {
        var released = 0
        var received = false
        val handoff = ResourceHandoff<Any> { released++ }
        val dispatcher = StandardTestDispatcher(testScheduler)
        lateinit var receiver: Job
        receiver = launch {
            try {
                withContext(dispatcher) { handoff.offer(Any()); receiver.cancel() }
                handoff.take(); received = true
            } finally { handoff.close() }
        }
        receiver.join()
        assertFalse(received)
        assertEquals(1, released)
    }
    @Test fun simultaneousCancellationAndProductionReleaseExactlyOnce() {
        repeat(100) {
            val released = AtomicInteger()
            val handoff = ResourceHandoff<Any> { released.incrementAndGet() }
            val producer = thread { handoff.offer(Any()) }
            val receiver = thread { handoff.close() }
            producer.join(); receiver.join(); handoff.close()
            assertEquals(1, released.get())
        }
    }
}
