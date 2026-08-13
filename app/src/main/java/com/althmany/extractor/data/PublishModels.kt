package com.althmany.extractor.data

enum class PublishStatus(val labelAr: String) {
    PENDING("بانتظار النشر"),
    OPENING("فتح القروب"),
    PREPARING("تحضير الرسالة"),
    SENDING("جارٍ الإرسال"),
    SENT("تم الإرسال"),
    VERIFIED("تم التحقق"),
    FAILED("فشل"),
    SKIPPED("تم التخطي")
}

enum class PublishRunStatus { RUNNING, PAUSED, COMPLETED, STOPPED, ERROR }

data class PublishRun(
    val id: Long,
    val message: String,
    val targetPackage: String,
    val status: PublishRunStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val delayMs: Long,
    val maxAttempts: Int
)

data class PublishItem(
    val id: Long,
    val runId: Long,
    val groupName: String,
    val status: PublishStatus,
    val detail: String?,
    val attempts: Int,
    val sentAt: Long?,
    val verified: Boolean
)

data class PublishStats(
    val total: Int = 0,
    val pending: Int = 0,
    val sent: Int = 0,
    val verified: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0
) {
    val completed: Int get() = (sent + verified + failed + skipped).coerceAtMost(total)
}
