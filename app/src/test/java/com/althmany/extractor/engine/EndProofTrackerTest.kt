package com.althmany.extractor.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndProofTrackerTest {
    @Test fun stableFailuresTriggerStrictProof() {
        val tracker = EndProofTracker()
        repeat(5) { tracker.observe(EndEvidence(12, false, true, 5, 0, false)) }
        assertTrue(tracker.shouldStartQuietEndProof())
    }

    @Test fun olderLoaderResetsEndProgress() {
        val tracker = EndProofTracker()
        repeat(5) { tracker.observe(EndEvidence(12, false, true, 5, 0, false)) }
        tracker.observe(EndEvidence(12, false, true, 5, 0, true))
        assertFalse(tracker.shouldStartQuietEndProof())
    }

    @Test fun structuralBoundaryCompletesImmediately() {
        val tracker = EndProofTracker()
        val evidence = EndEvidence(1, false, false, 0, 0, false, TerminalBoundary("joined", "joined", true))
        tracker.observe(evidence)
        assertEquals("joined", tracker.immediateCompletionReason(evidence))
    }
}
