import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.data.PublishStats
import com.althmany.extractor.engine.PublishSpeedProfile

fun main() {
    val stats = PublishStats(total = 10, pending = 1, sent = 3, verified = 3, failed = 1, skipped = 1, uncertain = 1)
    check(stats.completed == 9)
    check(PublishSpeedProfile.TURBO.betweenGroupsMs == 1_000L)
    check(PublishSpeedProfile.TURBO.betweenGroupsMs < PublishSpeedProfile.FAST.betweenGroupsMs)
    check(PublishSpeedProfile.FAST.betweenGroupsMs < PublishSpeedProfile.ADAPTIVE.betweenGroupsMs)
    check(PublishSpeedProfile.ADAPTIVE.betweenGroupsMs < PublishSpeedProfile.SAFE.betweenGroupsMs)
    check(PublishSpeedProfile.entries.all { it.uiTimeoutMs >= 5_000L })
    check(PublishSpeedProfile.entries.all { it.betweenGroupsMs >= 1_000L })
    check(PublishSpeedProfile.entries.none { it.labelAr.contains("0.32") })
    check(PublishContentMode.entries.count { it.attachmentRequired } == 3)
    println("PurePublishChecks v2.13: OK")
}
