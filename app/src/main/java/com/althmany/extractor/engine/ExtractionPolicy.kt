package com.althmany.extractor.engine

import com.althmany.extractor.data.SpeedProfile

data class TimingPolicy(
    val searchOpenMs: Long,
    val searchResultMs: Long,
    val groupOpenMs: Long,
    val eventQuietMs: Long,
    val hardSettleMs: Long,
    val endQuietMs: Long,
    val recoveryMs: Long,
    val gestureDurationMs: Long,
    val eventSampleDelayMs: Long
)

/**
 * Event-first timing matrix.
 *
 * These values are not blind sleeps: the controller wakes on Accessibility events and only uses the
 * values as upper bounds / renderer settle guards. End-proof thresholds remain intentionally stricter
 * than normal scrolling so speed does not trade away completeness.
 */
object ExtractionPolicy {
    fun timing(speed: SpeedProfile): TimingPolicy = when (speed) {
        SpeedProfile.HYPER -> TimingPolicy(
            searchOpenMs = 70, searchResultMs = 105, groupOpenMs = 150,
            eventQuietMs = 28, hardSettleMs = 105, endQuietMs = 200,
            recoveryMs = 145, gestureDurationMs = 100, eventSampleDelayMs = 6
        )
        SpeedProfile.ADAPTIVE -> TimingPolicy(
            searchOpenMs = 95, searchResultMs = 145, groupOpenMs = 205,
            eventQuietMs = 38, hardSettleMs = 130, endQuietMs = 250,
            recoveryMs = 185, gestureDurationMs = 115, eventSampleDelayMs = 8
        )
        SpeedProfile.SMART -> TimingPolicy(
            searchOpenMs = 135, searchResultMs = 205, groupOpenMs = 285,
            eventQuietMs = 55, hardSettleMs = 180, endQuietMs = 325,
            recoveryMs = 250, gestureDurationMs = 135, eventSampleDelayMs = 10
        )
        SpeedProfile.BALANCED -> TimingPolicy(
            searchOpenMs = 225, searchResultMs = 330, groupOpenMs = 450,
            eventQuietMs = 105, hardSettleMs = 330, endQuietMs = 520,
            recoveryMs = 395, gestureDurationMs = 185, eventSampleDelayMs = 16
        )
        SpeedProfile.SAFE -> TimingPolicy(
            searchOpenMs = 330, searchResultMs = 470, groupOpenMs = 640,
            eventQuietMs = 155, hardSettleMs = 490, endQuietMs = 740,
            recoveryMs = 570, gestureDurationMs = 230, eventSampleDelayMs = 18
        )
    }

    const val REQUIRED_STABLE_ROUNDS = 4
    const val REQUIRED_SCROLL_FAILURES = 2
    const val EMPTY_NON_SCROLLABLE_PASSES = 3
    const val QUIET_END_PASSES = 2
    const val NEW_ONLY_BOOTSTRAP_PAGES = 10
    const val MAX_SYNC_ITEMS = 4_000
}
