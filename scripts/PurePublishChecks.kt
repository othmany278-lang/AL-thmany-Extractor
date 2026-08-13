import com.althmany.extractor.data.PublishStats
import com.althmany.extractor.engine.PublishSpeedProfile

fun main() {
    val stats = PublishStats(total = 10, pending = 2, sent = 3, verified = 3, failed = 1, skipped = 1)
    check(stats.completed == 8)
    check(PublishSpeedProfile.FAST.betweenGroupsMs < PublishSpeedProfile.ADAPTIVE.betweenGroupsMs)
    check(PublishSpeedProfile.ADAPTIVE.betweenGroupsMs < PublishSpeedProfile.SAFE.betweenGroupsMs)
    check(PublishSpeedProfile.entries.all { it.uiTimeoutMs >= 5_000L })
    check(PublishSpeedProfile.entries.all { it.betweenGroupsMs >= 1_800L })
    check(PublishSpeedProfile.FAST.settleMs < PublishSpeedProfile.ADAPTIVE.settleMs)
    println("PurePublishChecks v2.10: OK")
}
