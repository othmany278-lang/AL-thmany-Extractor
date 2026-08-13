package com.althmany.extractor.data

enum class GroupStatus {
    PENDING,
    DISCOVERED,
    SEARCHING,
    OPENING,
    VERIFYING,
    EXTRACTING,
    COMPLETED,
    FAILED,
    FAILED_FINAL,
    SKIPPED_NOT_GROUP,
    PAUSED
}

enum class ExtractionMode(val labelAr: String) {
    LINKS_TAB("استخراج سريع"),
    DEEP("عميق موثوق"),
    ALL_CHATS("جميع الدردشات"),
    NEW_ONLY("الجديد فقط"),
    SMART("ذكي تلقائي")
}

enum class SpeedProfile(val labelAr: String) {
    ADAPTIVE("Turbo دقيق"),
    SMART("ذكي مستمر"),
    HYPER("HyperDrive"),
    BALANCED("متوازن"),
    SAFE("دقيق وآمن")
}

data class ExtractionPreferences(
    val mode: ExtractionMode = ExtractionMode.DEEP,
    val speed: SpeedProfile = SpeedProfile.ADAPTIVE,
    val maxScrollIterations: Int = 2_000,
    val maxSameGroupRetries: Int = 3,
    val strictEndProof: Boolean = true,
    val autoRecoverWhatsApp: Boolean = true,
    val targetWhatsAppPackage: String? = null
)

data class TargetGroup(
    val id: Long,
    val name: String,
    val selected: Boolean,
    val status: GroupStatus,
    val extractedCount: Int,
    val lastError: String?,
    val discovered: Boolean = false,
    val verifiedGroup: Boolean = false
)

data class LinkRecord(
    val id: Long,
    val url: String,
    val normalizedUrl: String,
    val groupName: String,
    val occurrences: Int,
    val firstSeen: Long,
    val lastSeen: Long
)

data class GroupCheckpoint(
    val groupName: String,
    val anchorTokens: List<String>,
    val signature: Int,
    val iteration: Int,
    val uniqueLinks: Int,
    val mode: ExtractionMode,
    val completed: Boolean,
    val updatedAt: Long
)

data class ExtractionLog(
    val id: Long,
    val timestamp: Long,
    val groupName: String?,
    val level: String,
    val code: String,
    val message: String
)

enum class EngineStatus {
    IDLE,
    PREPARING,
    SYNCING_GROUPS,
    OPENING_WHATSAPP,
    SEARCHING_GROUP,
    OPENING_GROUP,
    VERIFYING_GROUP,
    EXTRACTING,
    LINKS_TAB,
    VERIFYING_END,
    RECOVERING,
    PROFILE_MISMATCH,
    PAUSED,
    COMPLETED,
    STOPPED,
    ERROR
}

data class ExtractionStats(
    val totalGroups: Int = 0,
    val completedGroups: Int = 0,
    val failedGroups: Int = 0,
    val totalUniqueLinks: Int = 0,
    val totalOccurrences: Int = 0
)
