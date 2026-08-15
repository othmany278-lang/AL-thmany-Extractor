package com.althmany.extractor.data

enum class PublishContentMode(val labelAr: String, val attachmentRequired: Boolean = false) {
    SINGLE_TEXT("نص واحد"),
    MULTI_TEXT("رسائل متعددة"),
    CONTACT_TEXT("جهات اتصال كنص"),
    VCF("بطاقة VCF", true),
    VCF_WITH_TEXT("VCF + نص", true),
    IMAGE_WITH_CAPTION("صورة + تعليق", true)
}

enum class PublishStatus(val labelAr: String) {
    PENDING("بانتظار النشر"),
    OPENING("فتح القروب"),
    PREPARING("تحضير المحتوى"),
    SENDING("جارٍ الإرسال"),
    SENT("تم الإرسال"),
    VERIFIED("تم التحقق"),
    UNCERTAIN("غير محسوم — لن يُعاد تلقائيًا"),
    READ_ONLY("للقراءة فقط"),
    GROUP_NOT_FOUND("القروب غير موجود"),
    LEFT("تمت المغادرة/الإزالة"),
    BLOCKED("محظور/غير مسموح"),
    UI_ERROR("خطأ واجهة"),
    TIMEOUT("انتهت المهلة"),
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
    val maxAttempts: Int,
    val contentMode: PublishContentMode = PublishContentMode.SINGLE_TEXT,
    val attachmentUri: String? = null,
    val attachmentMime: String? = null,
    val runToken: String = ""
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
    val skipped: Int = 0,
    val uncertain: Int = 0
) {
    val completed: Int get() = (sent + verified + failed + skipped + uncertain).coerceAtMost(total)
}
