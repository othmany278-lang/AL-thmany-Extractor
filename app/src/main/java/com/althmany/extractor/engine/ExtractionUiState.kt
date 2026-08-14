package com.althmany.extractor.engine

import com.althmany.extractor.data.EngineStatus
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.ExtractionStats
import com.althmany.extractor.data.SpeedProfile
import com.althmany.extractor.profile.RuntimeProfileInfo
import com.althmany.extractor.profile.WhatsAppInstance

data class ExtractionUiState(
    val status: EngineStatus = EngineStatus.IDLE,
    val serviceConnected: Boolean = false,
    /** Last WhatsApp package observed by Accessibility in the current profile. */
    val whatsappPackage: String? = null,
    val profileInfo: RuntimeProfileInfo = RuntimeProfileInfo.unknown(),
    val availableWhatsApp: List<WhatsAppInstance> = emptyList(),
    val selectedWhatsAppPackage: String? = null,
    val packageMismatch: Boolean = false,
    val profileAccessibilityConnected: Boolean = false,
    val shizukuReady: Boolean = false,
    val shizukuDetail: String = "غير مفحوص",
    val backendRecommendation: String = "Accessibility",
    val currentGroup: String? = null,
    val message: String = "جاهز",
    val stats: ExtractionStats = ExtractionStats(),
    val currentGroupIndex: Int = 0,
    val runGroupCount: Int = 0,
    val linksFoundThisGroup: Int = 0,
    val mode: ExtractionMode = ExtractionMode.DEEP,
    val speed: SpeedProfile = SpeedProfile.ADAPTIVE,
    val maxScrollIterations: Int = 2_000,
    val retry: Int = 0,
    val syncFound: Int = 0,
    val phaseDetail: String = ""
)
