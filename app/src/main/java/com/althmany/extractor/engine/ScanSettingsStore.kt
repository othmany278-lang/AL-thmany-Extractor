package com.althmany.extractor.engine

import android.content.Context

class ScanSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan_settings", Context.MODE_PRIVATE)

    fun loadSpeed(): ScanSpeedProfile = runCatching {
        ScanSpeedProfile.valueOf(prefs.getString("speed", ScanSpeedProfile.ADAPTIVE.name)!!)
    }.getOrDefault(ScanSpeedProfile.ADAPTIVE)

    fun saveSpeed(value: ScanSpeedProfile) {
        prefs.edit().putString("speed", value.name).apply()
    }

    fun loadScope(): ScanScope = runCatching {
        ScanScope.valueOf(prefs.getString("scope", ScanScope.PENDING_ONLY.name)!!)
    }.getOrDefault(ScanScope.PENDING_ONLY)

    fun saveScope(value: ScanScope) {
        prefs.edit().putString("scope", value.name).apply()
    }

    fun loadActionMode(): ScanActionMode = runCatching {
        ScanActionMode.valueOf(prefs.getString("action_mode", ScanActionMode.SCAN_ONLY.name)!!)
    }.getOrDefault(ScanActionMode.SCAN_ONLY)

    fun saveActionMode(value: ScanActionMode) {
        prefs.edit().putString("action_mode", value.name).apply()
    }

    fun loadRequestToJoinEnabled(): Boolean = prefs.getBoolean("request_to_join", false)

    fun saveRequestToJoinEnabled(value: Boolean) {
        prefs.edit().putBoolean("request_to_join", value).apply()
    }

    fun loadMaxAttempts(): Int = prefs.getInt("max_attempts", 3).coerceIn(1, 5)

    fun saveMaxAttempts(value: Int) {
        prefs.edit().putInt("max_attempts", value.coerceIn(1, 5)).apply()
    }
}
