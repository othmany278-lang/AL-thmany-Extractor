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
    LINKS_TAB("استخراج Links Tab"),
    DEEP("Deep — عميق"),
    ALL_CHATS("All Chats"),
    NEW_ONLY("غير المقروء/الجديد"),
    SMART("ذكي تلقائي")
}

enum class SpeedProfile(val labelAr: String) {
    ADAPTIVE("Turbo دقيق"),
    SMART("ذكي مستمر"),
    HYPER("HyperDrive"),
    BALANCED("متوازن"),
    SAFE("دقيق وآمن")
}

enum class GroupSelectionPreset(val labelAr: String) {
    ALL("تحديد الكل"),
    NONE("إلغاء التحديد"),
    UNREAD("غير المقروءة"),
    ACTIVE("النشطة"),
    PUBLISHABLE("القابلة للنشر"),
    UNVERIFIED("غير المؤكدة")
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

/**
 * Metadata visible from a WhatsApp chat-list row. Android does not expose WhatsApp's private
 * message/group database, so these are UI-derived hints. Group identity is still verified by
 * opening the chat before extraction/publishing.
 */
data class GroupSyncCandidate(
    val name: String,
    val unreadCount: Int = 0,
    val activityText: String? = null,
    val active: Boolean = true,
    val publishableHint: Boolean = true,
    val communityParentHint: Boolean = false
)

data class TargetGroup(
    val id: Long,
    val name: String,
    val selected: Boolean,
    val status: GroupStatus,
    val extractedCount: Int,
    val lastError: String?,
    val discovered: Boolean = false,
    val verifiedGroup: Boolean = false,
    val unreadCount: Int = 0,
    val activityText: String? = null,
    val active: Boolean = true,
    val publishable: Boolean = true,
    val communityParent: Boolean = false,
    val lastSyncedAt: Long? = null
)


enum class LinkCategory(val labelAr: String) {
    WHATSAPP("WhatsApp"),
    TELEGRAM("Telegram"),
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    GOOGLE("Google"),
    PDF("PDF"),
    OTHER("أخرى")
}

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
    val totalOccurrences: Int = 0,
    val syncedGroups: Int = 0,
    val unreadGroups: Int = 0,
    val activeGroups: Int = 0,
    val publishableGroups: Int = 0
)
