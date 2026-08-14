package com.althmany.extractor.profile

enum class ProfileControlCapability { READY, SERVICE_DISABLED, SERVICE_NOT_CONNECTED_LOCALLY, WAITING_FOR_TARGET_EVENT, TARGET_UI_TREE_UNAVAILABLE }

object ProfileControlPolicy {
    const val SERVICE_HEARTBEAT_FRESH_MS = 12_000L
    const val TARGET_EVENT_FRESH_MS = 12_000L
    const val ROOT_SNAPSHOT_FRESH_MS = 12_000L

    fun classify(systemEnabled: Boolean, localServiceConnected: Boolean, targetEventAgeMs: Long?, rootAgeMs: Long?, rootAvailable: Boolean, targetEventMatches: Boolean = true, rootPackageMatches: Boolean = true): ProfileControlCapability {
        if (!systemEnabled) return ProfileControlCapability.SERVICE_DISABLED
        if (!localServiceConnected) return ProfileControlCapability.SERVICE_NOT_CONNECTED_LOCALLY
        if (!targetEventMatches || targetEventAgeMs == null || targetEventAgeMs !in 0..TARGET_EVENT_FRESH_MS) return ProfileControlCapability.WAITING_FOR_TARGET_EVENT
        if (!rootPackageMatches || rootAgeMs == null || rootAgeMs !in 0..ROOT_SNAPSHOT_FRESH_MS || !rootAvailable) return ProfileControlCapability.TARGET_UI_TREE_UNAVAILABLE
        return ProfileControlCapability.READY
    }
}
