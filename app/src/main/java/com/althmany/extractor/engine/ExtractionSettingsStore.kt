package com.althmany.extractor.engine

import android.content.Context
import com.althmany.extractor.data.ExtractionMode
import com.althmany.extractor.data.ExtractionPreferences
import com.althmany.extractor.data.SpeedProfile

class ExtractionSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("extraction_settings", Context.MODE_PRIVATE)

    fun get(): ExtractionPreferences = ExtractionPreferences(
        mode = runCatching {
            ExtractionMode.valueOf(prefs.getString("mode", ExtractionMode.DEEP.name)!!)
        }.getOrDefault(ExtractionMode.DEEP),
        speed = runCatching {
            SpeedProfile.valueOf(prefs.getString("speed", SpeedProfile.ADAPTIVE.name)!!)
        }.getOrDefault(SpeedProfile.ADAPTIVE),
        maxScrollIterations = prefs.getInt("max_scroll_iterations", 2_000).coerceIn(100, 10_000),
        maxSameGroupRetries = prefs.getInt("same_group_retries", 3).coerceIn(1, 5),
        betweenItemsDelayMs = prefs.getLong("between_items_delay_ms", 0L).coerceIn(0L, 60_000L),
        strictEndProof = prefs.getBoolean("strict_end", true),
        autoRecoverWhatsApp = prefs.getBoolean("auto_recover", true),
        targetWhatsAppPackage = prefs.getString("target_whatsapp_package", null)
    )

    fun setMode(mode: ExtractionMode) {
        prefs.edit().putString("mode", mode.name).apply()
    }

    fun setSpeed(speed: SpeedProfile) {
        prefs.edit().putString("speed", speed.name).apply()
    }

    fun setMaxScrollIterations(value: Int) {
        prefs.edit().putInt("max_scroll_iterations", value.coerceIn(100, 10_000)).apply()
    }

    fun setMaxSameGroupRetries(value: Int) {
        prefs.edit().putInt("same_group_retries", value.coerceIn(1, 5)).apply()
    }

    fun setBetweenItemsDelayMs(value: Long) {
        prefs.edit().putLong("between_items_delay_ms", value.coerceIn(0L, 60_000L)).apply()
    }

    fun setTargetWhatsAppPackage(packageName: String?) {
        prefs.edit().apply {
            if (packageName == null) remove("target_whatsapp_package") else putString("target_whatsapp_package", packageName)
        }.apply()
    }
}
