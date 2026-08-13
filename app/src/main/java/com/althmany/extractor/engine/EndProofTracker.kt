package com.althmany.extractor.engine

data class EndEvidence(
    val signature: Int,
    val scrolled: Boolean,
    val scrollable: Boolean,
    val messageTokenCount: Int,
    val urlCount: Int,
    val olderLoaderVisible: Boolean,
    val terminalBoundary: TerminalBoundary? = null
)

data class TerminalBoundary(
    val code: String,
    val label: String,
    val structural: Boolean
)

class EndProofTracker {
    private var lastSignature: Int? = null
    var stableRounds: Int = 0
        private set
    var scrollFailures: Int = 0
        private set
    var emptyNonScrollablePasses: Int = 0
        private set

    fun resetProgress() {
        stableRounds = 0
        scrollFailures = 0
        emptyNonScrollablePasses = 0
    }

    fun observe(evidence: EndEvidence) {
        stableRounds = if (lastSignature == evidence.signature) stableRounds + 1 else 0
        lastSignature = evidence.signature

        scrollFailures = if (evidence.scrolled) 0 else scrollFailures + 1
        emptyNonScrollablePasses = if (
            !evidence.scrollable && evidence.messageTokenCount == 0 && evidence.urlCount == 0
        ) emptyNonScrollablePasses + 1 else 0

        if (evidence.olderLoaderVisible) {
            stableRounds = 0
            scrollFailures = 0
        }
    }

    fun immediateCompletionReason(evidence: EndEvidence): String? {
        evidence.terminalBoundary?.takeIf { it.structural }?.let { return it.code }
        if (emptyNonScrollablePasses >= ExtractionPolicy.EMPTY_NON_SCROLLABLE_PASSES) {
            return "empty-non-scrollable-3-pass"
        }
        return null
    }

    fun shouldStartQuietEndProof(): Boolean {
        return stableRounds >= ExtractionPolicy.REQUIRED_STABLE_ROUNDS &&
            scrollFailures >= ExtractionPolicy.REQUIRED_SCROLL_FAILURES
    }
}
