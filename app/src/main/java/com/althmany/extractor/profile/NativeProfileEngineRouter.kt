package com.althmany.extractor.profile

import android.content.Context
import com.althmany.extractor.accessibility.AccessibilityRuntimeBridge
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
        val access = AccessibilityRuntimeBridge.currentEvenIfQuiet() != null
        val shizuku = runCatching { ShizukuBridge.status().ready }.getOrDefault(false)
        val recommended = when {
            access -> RuntimeBackendKind.ACCESSIBILITY
            shizuku -> RuntimeBackendKind.SHIZUKU
            else -> RuntimeBackendKind.NONE
        }
        val reason = when (recommended) {
            RuntimeBackendKind.ACCESSIBILITY ->
                "Accessibility service instance حي داخل نفس Profile"
            RuntimeBackendKind.SHIZUKU ->
                "Shizuku لديه Binder+إذن؛ UIAutomation يُثبت عند بدء العملية"
            RuntimeBackendKind.NONE ->
                "لا يوجد Accessibility runtime حي ولا Shizuku جاهز"
        }
        return NativeProfileEngineSnapshot(
            profile.profileKey, access, shizuku, recommended, reason
        )
    }
}
