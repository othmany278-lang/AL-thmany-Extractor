package com.althmany.extractor.engine

import com.althmany.extractor.data.ScanStats

enum class ScanEngineStatus { IDLE, PREPARING, WAITING_NETWORK, OPENING, CLASSIFYING, RETRYING, PAUSED, COMPLETED, STOPPED, ERROR }

enum class ScanSpeedProfile(val labelAr: String, val previewTimeoutMs: Long, val eventWaitMs: Long, val settleDelayMs: Long) {
    HYPER("فائق", 3_500L, 65L, 14L),
    ADAPTIVE("ذكي", 5_600L, 105L, 24L),
    SAFE("دقيق", 8_000L, 190L, 50L)
}

enum class ScanActionMode(val labelAr: String) {
    SCAN_ONLY("فحص فقط"),
    JOIN_ONLY("انضمام فقط"),
    SCAN_AND_JOIN("فحص + انضمام")
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
    val actionMode: ScanActionMode = ScanActionMode.SCAN_ONLY,
    val requestToJoinEnabled: Boolean = false,
    val message: String = "جاهز للفحص",
    val stats: ScanStats = ScanStats()
)
