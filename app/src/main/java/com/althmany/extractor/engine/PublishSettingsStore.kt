package com.althmany.extractor.engine

import android.content.Context
import com.althmany.extractor.data.PublishContentMode

class PublishSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("publish_settings", Context.MODE_PRIVATE)

    fun speed(): PublishSpeedProfile = runCatching {
        PublishSpeedProfile.valueOf(prefs.getString("speed", PublishSpeedProfile.ADAPTIVE.name)!!)
    }.getOrDefault(PublishSpeedProfile.ADAPTIVE)

    fun setSpeed(value: PublishSpeedProfile) { prefs.edit().putString("speed", value.name).apply() }

    fun maxAttempts(): Int = prefs.getInt("max_attempts", 2).coerceIn(1, 3)
    fun setMaxAttempts(value: Int) { prefs.edit().putInt("max_attempts", value.coerceIn(1, 3)).apply() }

    fun lastMessage(): String = prefs.getString("last_message", "").orEmpty()
    fun setLastMessage(value: String) { prefs.edit().putString("last_message", value.take(16_000)).apply() }

    fun contentMode(): PublishContentMode = runCatching {
        PublishContentMode.valueOf(prefs.getString("content_mode", PublishContentMode.SINGLE_TEXT.name)!!)
    }.getOrDefault(PublishContentMode.SINGLE_TEXT)
    fun setContentMode(value: PublishContentMode) { prefs.edit().putString("content_mode", value.name).apply() }

    fun attachmentUri(): String? = prefs.getString("attachment_uri", null)
    fun attachmentMime(): String? = prefs.getString("attachment_mime", null)
    fun setAttachment(uri: String?, mime: String?) {
        prefs.edit().apply {
            if (uri == null) remove("attachment_uri") else putString("attachment_uri", uri)
            if (mime == null) remove("attachment_mime") else putString("attachment_mime", mime)
        }.apply()
    }
}
