package com.althmany.extractor.profile

import android.content.Context
import com.althmany.extractor.shizuku.ShizukuBridge

enum class RuntimeBackendKind { ACCESSIBILITY, SHIZUKU, NONE }

data class NativeProfileEngineSnapshot(
    val profileKey: String,
    val accessibilityLocalReady: Boolean,
    val shizukuReady: Boolean,
    val recommended: RuntimeBackendKind,
    val reason: String
)

object NativeProfileEngineRouter {
    fun inspect(context: Context): NativeProfileEngineSnapshot {
        val profile = RuntimeProfileDetector.detect(context)
        val access = ProfileAccessibilityRuntime.snapshot(context).localServiceConnected
        val shizuku = runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
        val recommended = when { access -> RuntimeBackendKind.ACCESSIBILITY; shizuku -> RuntimeBackendKind.SHIZUKU; else -> RuntimeBackendKind.NONE }
        val reason = when (recommended) {
            RuntimeBackendKind.ACCESSIBILITY -> "Accessibility محلية ومتصلة"
            RuntimeBackendKind.SHIZUKU -> "Shizuku لديه Binder+إذن؛ سيتم إثبات UIAutomation وواتساب عند بدء العملية"
            RuntimeBackendKind.NONE -> "فعّل Accessibility داخل نفس Profile أو شغّل Shizuku واختبر الرؤية"
        }
        return NativeProfileEngineSnapshot(profile.profileKey, access, shizuku, recommended, reason)
    }
}
