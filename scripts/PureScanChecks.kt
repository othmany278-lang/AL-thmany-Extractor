import com.althmany.extractor.engine.InviteLinkParser
import com.althmany.extractor.engine.InviteScanClassifier
import com.althmany.extractor.engine.ScanRetryPolicy
import com.althmany.extractor.engine.ScanSpeedProfile
import com.althmany.extractor.data.InviteKind
import com.althmany.extractor.data.ScanStatus

fun main() {
    val parsed = InviteLinkParser.extract(
        """
        https://chat.whatsapp.com/AbCdEfGh12345678?mode=abc
        https://example.com/not-whatsapp
        https://chat.whatsapp.com/AbCdEfGh12345678
        """.trimIndent()
    )
    check(parsed.size == 1) { "dedupe failed" }
    check(parsed.single().normalizedUrl == "https://chat.whatsapp.com/AbCdEfGh12345678")

    fun status(vararg text: String) = InviteScanClassifier.classify(text.toList())
    check(status("الانضمام إلى المجموعة").status == ScanStatus.DIRECT)
    check(status("طلب الانضمام").status == ScanStatus.APPROVAL)
    check(status("إلغاء الطلب").status == ScanStatus.REQUEST_PENDING)
    check(status("أنت عضو بالفعل").status == ScanStatus.ALREADY_MEMBER)
    check(status("رابط الدعوة غير صالح").status == ScanStatus.INVALID)
    check(status("المجموعة ممتلئة").status == ScanStatus.FULL)
    check(status("تمت إزالتك").status == ScanStatus.REMOVED)
    check(status("لا يمكنك الانضمام إلى المزيد من المجموعات").status == ScanStatus.ACCOUNT_LIMIT)
    check(status("تحقق من اتصالك بالإنترنت").status == ScanStatus.NETWORK_ERROR)
    check(status("نص عادي").status == ScanStatus.UNKNOWN)

    val meta = status("مجتمع الوظائف", "245 مشاركًا", "طلب الانضمام", "المجتمع")
    check(meta.status == ScanStatus.APPROVAL)
    check(meta.inviteKind == InviteKind.COMMUNITY)
    check(meta.memberCountText?.contains("245") == true)
    check(meta.confidence >= 90)

    check(ScanRetryPolicy.shouldRetry(ScanStatus.UNKNOWN, 1, 3))
    check(ScanRetryPolicy.shouldRetry(ScanStatus.NETWORK_ERROR, 2, 3))
    check(!ScanRetryPolicy.shouldRetry(ScanStatus.DIRECT, 1, 3))
    check(!ScanRetryPolicy.shouldRetry(ScanStatus.UNKNOWN, 3, 3))
    check(ScanRetryPolicy.backoffMs(ScanStatus.UNKNOWN, 1, ScanSpeedProfile.HYPER) <
        ScanRetryPolicy.backoffMs(ScanStatus.UNKNOWN, 1, ScanSpeedProfile.SAFE))
    check(ScanSpeedProfile.HYPER.settleDelayMs < ScanSpeedProfile.ADAPTIVE.settleDelayMs)
    check(ScanSpeedProfile.ADAPTIVE.settleDelayMs < ScanSpeedProfile.SAFE.settleDelayMs)
    check(ScanSpeedProfile.HYPER.previewTimeoutMs >= 3_000L)
    check(ScanSpeedProfile.ADAPTIVE.previewTimeoutMs == 5_600L)

    println("PureScanChecks v2.15: PASS")
}
