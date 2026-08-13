package com.althmany.extractor.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeSnapshotTest {
    @Test fun requiresUsefulAnchorOverlap() {
        val s = NodeSnapshot(emptyList(), 1, 2, listOf("one token", "second token", "third token"), true, 10, 3)
        assertTrue(s.matchesAnchor(listOf("one token", "second token")))
        assertFalse(s.matchesAnchor(listOf("missing token", "second token")))
    }
}
