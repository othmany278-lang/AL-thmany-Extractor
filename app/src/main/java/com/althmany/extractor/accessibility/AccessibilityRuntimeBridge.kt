package com.althmany.extractor.accessibility

import android.os.Process
import android.os.SystemClock

data class AccessibilityBridgeSnapshot(
    val connected: Boolean,
    val androidUserId: Int,
    val heartbeatAgeMs: Long?,
    val lastObservedPackage: String?
)

object AccessibilityRuntimeBridge {
    @Volatile private var active: WhatsAppAccessibilityService? = null
    @Volatile private var connected: Boolean = false
    @Volatile private var lastHeartbeatElapsed: Long = 0L
    @Volatile private var lastObservedPackage: String? = null
    @Volatile private var boundAndroidUserId: Int = -1

    fun bind(service: WhatsAppAccessibilityService) {
        active = service
        connected = true
        boundAndroidUserId = currentAndroidUserId()
        heartbeat()
    }

    fun event(service: WhatsAppAccessibilityService, packageName: String?) {
        if (active !== service) active = service
        connected = true
        boundAndroidUserId = currentAndroidUserId()
        if (!packageName.isNullOrBlank()) lastObservedPackage = packageName
        heartbeat()
    }

    fun heartbeat() {
        lastHeartbeatElapsed = SystemClock.elapsedRealtime()
    }

    fun unbind(service: WhatsAppAccessibilityService) {
        if (active === service) {
            active = null
            connected = false
            lastObservedPackage = null
            boundAndroidUserId = -1
            lastHeartbeatElapsed = 0L
        }
    }

    fun current(maxHeartbeatAgeMs: Long = 5_000L): WhatsAppAccessibilityService? {
        val service = active ?: return null
        if (!connected || boundAndroidUserId != currentAndroidUserId()) return null
        val age = SystemClock.elapsedRealtime() - lastHeartbeatElapsed
        return service.takeIf { age in 0..maxHeartbeatAgeMs }
    }

    fun currentEvenIfQuiet(): WhatsAppAccessibilityService? {
        val service = active ?: return null
        if (!connected) return null
        return service.takeIf { boundAndroidUserId == currentAndroidUserId() }
    }

    fun isConnected(): Boolean = currentEvenIfQuiet() != null
    fun lastPackage(): String? = lastObservedPackage

    fun snapshot(): AccessibilityBridgeSnapshot {
        val age = lastHeartbeatElapsed.takeIf { it > 0L }?.let {
            (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L)
        }
        return AccessibilityBridgeSnapshot(
            connected = isConnected(),
            androidUserId = boundAndroidUserId,
            heartbeatAgeMs = age,
            lastObservedPackage = lastObservedPackage
        )
    }

    fun currentAndroidUserId(): Int = (Process.myUid() / 100_000).coerceAtLeast(0)
}
