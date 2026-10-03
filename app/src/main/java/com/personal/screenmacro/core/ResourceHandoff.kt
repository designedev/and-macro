package com.personal.screenmacro.core

/** Own a produced resource until the receiving coroutine actually takes it. */
class ResourceHandoff<T : Any>(private val release: (T) -> Unit) : AutoCloseable {
    private var value: T? = null
    private var finished = false
    @Synchronized fun offer(resource: T) {
        if (finished) release(resource)
        else { check(value == null); value = resource }
    }
    @Synchronized fun take(): T {
        check(!finished)
        val resource = checkNotNull(value)
        value = null; finished = true
        return resource
    }
    @Synchronized override fun close() {
        finished = true
        value?.let { value = null; release(it) }
    }
}
