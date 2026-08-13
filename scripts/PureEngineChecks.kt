package checks

import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.engine.EndEvidence
import com.althmany.extractor.engine.EndProofTracker
import com.althmany.extractor.engine.ExtractionPolicy
import com.althmany.extractor.engine.LinkExtractor
import com.althmany.extractor.engine.NodeSnapshot
import com.althmany.extractor.engine.TerminalBoundary
import com.althmany.extractor.profile.ProfileLaunchPolicy

private fun checkThat(value: Boolean, message: String) {
    if (!value) error(message)
}

fun main() {
    val links = LinkExtractor.extract("A https://Example.com/a/, B www.test.com/x?y=1. C https://example.com/a/")
    checkThat(links.size == 3, "URL capture failed: $links")
    checkThat(LinkExtractor.normalize("HTTPS://WWW.Example.com/a/") == "https://example.com/a", "normalization failed")

    val anchor = listOf("رسالة قديمة مهمة", "sender token")
    val snapshot = NodeSnapshot(
        texts = anchor, signature = 1, contentSignature = 2,
        anchorTokens = listOf("رسالة قديمة مهمة", "sender token", "extra"),
        scrollableNodeFound = true, visibleNodeCount = 10, messageTokenCount = 3
    )
    checkThat(snapshot.matchesAnchor(anchor), "checkpoint anchor matching failed")

    val end = EndProofTracker()
    repeat(5) {
        end.observe(EndEvidence(42, scrolled = false, scrollable = true, messageTokenCount = 5, urlCount = 0, olderLoaderVisible = false))
    }
    checkThat(end.shouldStartQuietEndProof(), "strict end proof trigger failed")

    val empty = EndProofTracker()
    repeat(ExtractionPolicy.EMPTY_NON_SCROLLABLE_PASSES) {
        val evidence = EndEvidence(7, scrolled = false, scrollable = false, messageTokenCount = 0, urlCount = 0, olderLoaderVisible = false)
        empty.observe(evidence)
    }
    val emptyReason = empty.immediateCompletionReason(EndEvidence(7, false, false, 0, 0, false))
    checkThat(emptyReason == "empty-non-scrollable-3-pass", "empty structural exception failed")

    val terminal = EndProofTracker()
    val terminalEvidence = EndEvidence(9, false, false, 0, 0, false, TerminalBoundary("phone-history-limit", "limit", true))
    terminal.observe(terminalEvidence)
    checkThat(terminal.immediateCompletionReason(terminalEvidence) == "phone-history-limit", "terminal boundary failed")

    val hyper = ExtractionPolicy.timing(SpeedProfile.HYPER)
    val adaptive = ExtractionPolicy.timing(SpeedProfile.ADAPTIVE)
    val smart = ExtractionPolicy.timing(SpeedProfile.SMART)
    val balanced = ExtractionPolicy.timing(SpeedProfile.BALANCED)
    val safe = ExtractionPolicy.timing(SpeedProfile.SAFE)
    checkThat(hyper.groupOpenMs < adaptive.groupOpenMs && adaptive.groupOpenMs < smart.groupOpenMs && smart.groupOpenMs < balanced.groupOpenMs && balanced.groupOpenMs < safe.groupOpenMs, "speed ordering failed")
    checkThat(hyper.eventQuietMs <= 28 && adaptive.eventQuietMs <= 38, "Turbo event windows regressed")
    checkThat(ExtractionPolicy.REQUIRED_STABLE_ROUNDS == 4, "strict end stable rounds must remain enabled")
    checkThat(ExtractionPolicy.QUIET_END_PASSES == 2, "quiet end proof must remain two-pass")
    checkThat(ExtractionPolicy.MAX_SYNC_ITEMS == 4000, "sync cap changed unexpectedly")

    checkThat(ProfileLaunchPolicy.resolveSelected(null, listOf("com.whatsapp")) == "com.whatsapp", "single-instance selection failed")
    checkThat(ProfileLaunchPolicy.resolveSelected(null, listOf("com.whatsapp", "com.whatsapp.w4b")) == null, "two-instance guard must require explicit choice")
    checkThat(ProfileLaunchPolicy.resolveSelected("com.whatsapp.w4b", listOf("com.whatsapp", "com.whatsapp.w4b")) == "com.whatsapp.w4b", "saved business selection failed")
    checkThat(ProfileLaunchPolicy.isMismatch("com.whatsapp.w4b", "com.whatsapp"), "package mismatch guard failed")
    checkThat(!ProfileLaunchPolicy.isMismatch("com.whatsapp", "com.whatsapp"), "same package must not be mismatch")

    println("AL-thmany v2.10 pure engine/profile checks: PASS")
}
