package com.althmany.extractor.engine

import android.content.Context

class PublishSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("publish_settings", Context.MODE_PRIVATE)

    fun speed(): PublishSpeedProfile = runCatching {
        PublishSpeedProfile.valueOf(prefs.getString("speed", PublishSpeedProfile.ADAPTIVE.name)!!)
    }.getOrDefault(PublishSpeedProfile.ADAPTIVE)

    fun setSpeed(value: PublishSpeedProfile) { prefs.edit().putString("speed", value.name).apply() }

    fun maxAttempts(): Int = prefs.getInt("max_attempts", 2).coerceIn(1, 3)
    fun setMaxAttempts(value: Int) { prefs.edit().putInt("max_attempts", value.coerceIn(1, 3)).apply() }

    fun lastMessage(): String = prefs.getString("last_message", "").orEmpty()
    fun setLastMessage(value: String) { prefs.edit().putString("last_message", value.take(8_000)).apply() }
}
