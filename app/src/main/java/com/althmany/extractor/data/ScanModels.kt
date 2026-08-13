package com.althmany.extractor.data

enum class ScanStatus(val labelAr: String) {
    PENDING("بانتظار الفحص"),
    SCANNING("جارٍ الفحص"),
    DIRECT("انضمام مباشر"),
    APPROVAL("يتطلب موافقة"),
    REQUEST_PENDING("طلب الانضمام مرسل"),
    ALREADY_MEMBER("عضو بالفعل"),
    INVALID("غير صالح"),
    FULL("المجموعة ممتلئة"),
    REMOVED("تمت إزالتك"),
    ACCOUNT_LIMIT("حد الحساب/تعذر الانضمام"),
    NETWORK_ERROR("مشكلة اتصال"),
    UNKNOWN("غير مؤكد"),
    ERROR("خطأ")
}

enum class InviteKind(val labelAr: String) {
    GROUP("مجموعة"),
    COMMUNITY("مجتمع"),
    UNKNOWN("غير معروف")
}


data class ScanSeed(
    val url: String,
    val normalizedUrl: String,
    val inviteCode: String,
    val sourceGroup: String?
)

data class ScanRecord(
    val id: Long,
    val url: String,
    val normalizedUrl: String,
    val inviteCode: String,
    val sourceGroup: String?,
    val status: ScanStatus,
    val groupName: String?,
    val detail: String?,
    val attempts: Int,
    val addedAt: Long,
    val scannedAt: Long?,
    val confidence: Int = 0,
    val memberCountText: String? = null,
    val inviteKind: InviteKind = InviteKind.UNKNOWN,
    val signalCode: String? = null,
    val durationMs: Long? = null,
    val targetPackage: String? = null
)

data class ScanStats(
    val total: Int = 0,
    val pending: Int = 0,
    val direct: Int = 0,
    val approval: Int = 0,
    val requestPending: Int = 0,
    val alreadyMember: Int = 0,
    val invalid: Int = 0,
    val network: Int = 0,
    val unknown: Int = 0,
    val other: Int = 0
) {
    val completed: Int get() = (total - pending).coerceAtLeast(0)
}
