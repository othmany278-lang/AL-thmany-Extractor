package com.althmany.extractor.engine

import com.althmany.extractor.data.ScanStats

enum class ScanEngineStatus { IDLE, PREPARING, OPENING, CLASSIFYING, RETRYING, PAUSED, COMPLETED, STOPPED, ERROR }

enum class ScanSpeedProfile(val labelAr: String, val previewTimeoutMs: Long, val eventWaitMs: Long, val settleDelayMs: Long) {
    HYPER("فائق", 3_000L, 65L, 14L),
    ADAPTIVE("ذكي", 4_800L, 105L, 24L),
    SAFE("دقيق", 8_000L, 190L, 50L)
}

enum class ScanScope(val labelAr: String) {
    PENDING_ONLY("الجديد وغير المؤكد"),
    UNCERTAIN_ONLY("غير المؤكد والأخطاء فقط"),
    RECHECK_ALL("إعادة فحص الكل")
}

data class ScanUiState(
    val status: ScanEngineStatus = ScanEngineStatus.IDLE,
    val serviceConnected: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val currentUrl: String? = null,
    val currentIndex: Int = 0,
    val total: Int = 0,
    val currentAttempt: Int = 0,
    val currentConfidence: Int = 0,
    val speed: ScanSpeedProfile = ScanSpeedProfile.ADAPTIVE,
    val scope: ScanScope = ScanScope.PENDING_ONLY,
    val maxAttempts: Int = 3,
    val message: String = "جاهز للفحص",
    val stats: ScanStats = ScanStats()
)
