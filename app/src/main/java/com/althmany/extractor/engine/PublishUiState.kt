package com.althmany.extractor.engine

import com.althmany.extractor.data.PublishContentMode
import com.althmany.extractor.data.PublishStats

enum class PublishEngineStatus { IDLE, PREPARING, OPENING_GROUP, WRITING, SENDING, VERIFYING, RETRYING, PAUSED, COMPLETED, STOPPED, ERROR }

enum class PublishSpeedProfile(val labelAr: String, val betweenGroupsMs: Long, val uiTimeoutMs: Long, val settleMs: Long) {
    // The displayed interval is the real minimum pacing used by the engine.
    TURBO("Turbo • 1.0s", 1_000L, 5_000L, 32L),
    FAST("سريع • 1.8s", 1_800L, 5_000L, 40L),
    ADAPTIVE("ذكي • 3.0s", 3_000L, 7_000L, 70L),
    SAFE("دقيق • 5.0s", 5_000L, 10_000L, 125L)
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
    val contentMode: PublishContentMode = PublishContentMode.SINGLE_TEXT,
    val attachmentUri: String? = null,
    val attachmentMime: String? = null,
    val info: String = "جاهز للنشر",
    val activeRunId: Long? = null,
    val runToken: String? = null,
    val stats: PublishStats = PublishStats()
)
