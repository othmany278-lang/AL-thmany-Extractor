package com.althmany.extractor.engine

import com.althmany.extractor.data.PublishStats

enum class PublishEngineStatus { IDLE, PREPARING, OPENING_GROUP, WRITING, SENDING, VERIFYING, RETRYING, PAUSED, COMPLETED, STOPPED, ERROR }

enum class PublishSpeedProfile(val labelAr: String, val betweenGroupsMs: Long, val uiTimeoutMs: Long, val settleMs: Long) {
    // Keep a non-zero inter-group pacing guard to reduce duplicate/rate-limit risk; speed gains come
    // from event-first UI detection rather than blind rapid-fire sending.
    FAST("سريع", 1_800L, 5_000L, 40L),
    ADAPTIVE("ذكي", 3_000L, 7_000L, 70L),
    SAFE("دقيق", 5_000L, 10_000L, 125L)
}

data class PublishUiState(
    val status: PublishEngineStatus = PublishEngineStatus.IDLE,
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val currentGroup: String? = null,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val currentAttempt: Int = 0,
    val speed: PublishSpeedProfile = PublishSpeedProfile.ADAPTIVE,
    val maxAttempts: Int = 2,
    val messageText: String = "",
    val info: String = "جاهز للنشر",
    val activeRunId: Long? = null,
    val stats: PublishStats = PublishStats()
)
