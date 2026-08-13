package com.althmany.extractor.engine

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-local single-operation gate.
 *
 * Accessibility events are shared by extraction, scan and publish. Without an atomic gate two UI
 * actions started at nearly the same moment could both pass their individual state checks. This
 * coordinator guarantees that only one engine owns the WhatsApp UI at a time.
 */
enum class RuntimeOperation(val labelAr: String) {
    EXTRACTION("الاستخراج"),
    SCAN("الفحص"),
    PUBLISH("النشر")
}

object RuntimeOperationCoordinator {
    private val owner = AtomicReference<RuntimeOperation?>(null)

    fun tryAcquire(operation: RuntimeOperation): Boolean {
        val current = owner.get()
        return current == operation || owner.compareAndSet(null, operation)
    }

    fun release(operation: RuntimeOperation) {
        owner.compareAndSet(operation, null)
    }

    fun current(): RuntimeOperation? = owner.get()

    fun isOwnedByOther(operation: RuntimeOperation): Boolean {
        val current = owner.get()
        return current != null && current != operation
    }

    // Pure regression checks only; production code should release its own operation explicitly.
    internal fun resetForTests() {
        owner.set(null)
    }
}
