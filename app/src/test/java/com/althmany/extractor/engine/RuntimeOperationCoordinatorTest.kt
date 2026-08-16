package com.althmany.extractor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RuntimeOperationCoordinatorTest {
    @Before fun reset() = RuntimeOperationCoordinator.resetForTests()

    @Test fun onlyOneEngineOwnsWhatsAppUi() {
        assertTrue(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION))
        assertFalse(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.SCAN))
        assertFalse(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.PUBLISH))
        assertEquals(RuntimeOperation.EXTRACTION, RuntimeOperationCoordinator.current())
    }

    @Test fun sameOperationCannotReenter() {
        assertTrue(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION))
        assertFalse(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.EXTRACTION))
        assertEquals(RuntimeOperation.EXTRACTION, RuntimeOperationCoordinator.current())
    }

    @Test fun releaseAllowsNextEngine() {
        assertTrue(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.SCAN))
        RuntimeOperationCoordinator.release(RuntimeOperation.SCAN)
        assertNull(RuntimeOperationCoordinator.current())
        assertTrue(RuntimeOperationCoordinator.tryAcquire(RuntimeOperation.PUBLISH))
    }
}
