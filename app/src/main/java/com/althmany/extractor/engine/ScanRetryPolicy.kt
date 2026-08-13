package com.althmany.extractor.engine

import com.althmany.extractor.data.ScanStatus

object ScanRetryPolicy {
    fun shouldRetry(status: ScanStatus, attempt: Int, maxAttempts: Int): Boolean {
        if (attempt >= maxAttempts) return false
        return status == ScanStatus.UNKNOWN || status == ScanStatus.NETWORK_ERROR || status == ScanStatus.ERROR
    }

    fun backoffMs(status: ScanStatus, attempt: Int, speed: ScanSpeedProfile): Long {
        val base = when (status) {
            ScanStatus.NETWORK_ERROR -> 650L
            ScanStatus.ERROR -> 420L
            else -> 260L
        }
        val speedFactor = when (speed) {
            ScanSpeedProfile.HYPER -> 0.65
            ScanSpeedProfile.ADAPTIVE -> 1.0
            ScanSpeedProfile.SAFE -> 1.45
        }
        return (base * attempt.coerceAtLeast(1) * speedFactor).toLong().coerceIn(120L, 3_500L)
    }
}
